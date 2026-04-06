package io.tradeflow.auth.service;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.tradeflow.auth.dto.AuthDtos;
import io.tradeflow.auth.dto.AuthDtos.*;
import io.tradeflow.auth.entity.AuthDevice;
import io.tradeflow.auth.entity.AuthOutbox;
import io.tradeflow.auth.entity.AuthUser;
import io.tradeflow.auth.security.JwtTokenService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AuthService — core business logic for all auth operations.
 *
 * TRACING STRATEGY:
 * All endpoints are auto-instrumented by Quarkus OTel (HTTP span is created automatically).
 * Inside each method we only ADD TAGS to the existing current span — we do NOT create
 * new child spans here. Child spans are created automatically by:
 *   - PostgreSQL queries  → via quarkus.datasource.jdbc.tracing=true
 *   - Redis operations    → via TracingHelper (injected into RefreshTokenService)
 *   - Kafka produce       → via TracingProducerInterceptor + OutboxRelayService
 *
 * We tag the span with business attributes so you can search/filter in Zipkin:
 *   Find all traces for a specific userId, operation, or error code.
 */
@ApplicationScoped
public class AuthService {

    private static final Logger LOG = Logger.getLogger(AuthService.class);

    @Inject JwtTokenService      jwtService;
    @Inject RefreshTokenService  refreshTokenService;
    @Inject MfaService           mfaService;
    @Inject ObjectMapper         objectMapper;

    // ----------------------------------------------------------------
    // ENDPOINT 1: POST /auth/login
    // ----------------------------------------------------------------

    @Transactional
    public TokenResponse login(LoginRequest request) {
        Span span = Span.current();
        span.setAttribute("auth.operation",          "login");
        span.setAttribute("auth.email.domain",       extractDomain(request.email()));
        span.setAttribute("auth.device.fingerprint", truncate(request.deviceFingerprint()));

        // 1. Find user
        AuthUser user = AuthUser.findByEmail(request.email())
                .orElseThrow(() -> {
                    span.setAttribute("auth.failure.reason", "user_not_found");
                    span.setStatus(StatusCode.ERROR, "invalid_credentials");
                    return new AuthException(Response.Status.UNAUTHORIZED,
                            "invalid_credentials", "Invalid email or password");
                });

        span.setAttribute("auth.userId", user.id);
        span.setAttribute("auth.roles",  user.roles);

        // 2. BCrypt password verification
        BCrypt.Result result = BCrypt.verifyer().verify(
                request.password().toCharArray(),
                user.hashedPassword
        );
        if (!result.verified) {
            span.setAttribute("auth.failure.reason", "invalid_password");
            span.setStatus(StatusCode.ERROR, "invalid_credentials");
            writeAuditEvent(user.id, "login.failed", request.deviceFingerprint(), null,
                    Map.of("reason", "invalid_password"));
            throw new AuthException(Response.Status.UNAUTHORIZED,
                    "invalid_credentials", "Invalid email or password");
        }

        // 3. Check account status
        if (!user.isActive()) {
            span.setAttribute("auth.failure.reason", "account_suspended");
            span.setStatus(StatusCode.ERROR, "account_suspended");
            throw new AuthException(Response.Status.FORBIDDEN,
                    "account_suspended", "Account is not active: " + user.status);
        }

        // 4. MFA check
        if (user.mfaEnabled) {
            span.setAttribute("auth.mfa.required", true);
            if (request.mfaCode() == null || request.mfaCode().isBlank()) {
                span.setAttribute("auth.failure.reason", "mfa_code_missing");
                span.setStatus(StatusCode.ERROR, "mfa_required");
                throw new AuthException(Response.Status.UNAUTHORIZED,
                        "mfa_required", "MFA code is required");
            }
            if (!mfaService.verifyCode(user.mfaSecret, request.mfaCode())) {
                span.setAttribute("auth.failure.reason", "invalid_mfa_code");
                span.setStatus(StatusCode.ERROR, "invalid_mfa_code");
                writeAuditEvent(user.id, "login.failed", request.deviceFingerprint(), null,
                        Map.of("reason", "invalid_mfa_code"));
                throw new AuthException(Response.Status.UNAUTHORIZED,
                        "invalid_mfa_code", "Invalid MFA code");
            }
            span.setAttribute("auth.mfa.verified", true);
        }

        // 5. Check device is not revoked
        validateDeviceNotRevoked(user.id, request.deviceFingerprint());

        // 6. Upsert device record
        upsertDevice(user.id, request.deviceFingerprint());

        // 7. Update last_login
        user.lastLogin = Instant.now();
        user.persist();

        // 8. Issue tokens
        String accessToken  = jwtService.buildAccessToken(user, request.deviceFingerprint());
        String refreshToken = refreshTokenService.issue(user, request.deviceFingerprint());

        // 9. Write outbox event
        writeOutboxEvent("user.login", user.id, Map.of(
                "user_id",            user.id,
                "email",              user.email,
                "device_fingerprint", request.deviceFingerprint(),
                "timestamp",          Instant.now().toString()
        ));

        span.setAttribute("auth.login.success", true);
        LOG.infof("User %s logged in successfully from device %s",
                user.id, truncate(request.deviceFingerprint()));

        return TokenResponse.of(accessToken, refreshToken);
    }

    // ----------------------------------------------------------------
    // ENDPOINT 2: POST /auth/refresh
    // ----------------------------------------------------------------

    @Transactional
    public TokenResponse refresh(RefreshRequest request) {
        Span span = Span.current();
        span.setAttribute("auth.operation",          "refresh");
        span.setAttribute("auth.device.fingerprint", truncate(request.deviceFingerprint()));

        // 1. Validate token exists in Redis
        RefreshTokenData tokenData = refreshTokenService.validate(request.refreshToken())
                .orElseThrow(() -> {
                    span.setAttribute("auth.failure.reason", "invalid_refresh_token");
                    span.setStatus(StatusCode.ERROR, "invalid_refresh_token");
                    return new AuthException(Response.Status.UNAUTHORIZED,
                            "invalid_refresh_token",
                            "Refresh token is invalid, expired, or already used");
                });

        span.setAttribute("auth.userId", tokenData.userId());

        // 2. Device fingerprint validation (theft detection)
        if (!request.deviceFingerprint().equals(tokenData.deviceFingerprint())) {
            refreshTokenService.revoke(request.refreshToken());
            span.setAttribute("auth.failure.reason", "device_fingerprint_mismatch");
            span.setAttribute("auth.security.alert", "possible_token_theft");
            span.setStatus(StatusCode.ERROR, "device_mismatch");
            LOG.warnf("Device fingerprint mismatch on refresh for user %s.", tokenData.userId());
            writeAuditEvent(tokenData.userId(), "refresh.device_mismatch",
                    request.deviceFingerprint(), null,
                    Map.of("stored_device_prefix", truncate(tokenData.deviceFingerprint())));
            throw new AuthException(Response.Status.UNAUTHORIZED,
                    "device_mismatch", "Token was used from an unexpected device");
        }

        // 3. Load user (verify still active)
        AuthUser user = AuthUser.<AuthUser>findByIdOptional(tokenData.userId())
                .orElseThrow(() -> {
                    span.setAttribute("auth.failure.reason", "user_not_found");
                    span.setStatus(StatusCode.ERROR, "user_not_found");
                    return new AuthException(Response.Status.UNAUTHORIZED,
                            "user_not_found", "User associated with token no longer exists");
                });

        if (!user.isActive()) {
            refreshTokenService.revoke(request.refreshToken());
            span.setAttribute("auth.failure.reason", "account_suspended");
            span.setStatus(StatusCode.ERROR, "account_suspended");
            throw new AuthException(Response.Status.FORBIDDEN,
                    "account_suspended", "Account is not active");
        }

        // 4. Rotate: delete old, issue new (atomic in RefreshTokenService)
        String newRefreshToken = refreshTokenService.rotate(
                request.refreshToken(), user, request.deviceFingerprint());

        // 5. Issue new access token
        String newAccessToken = jwtService.buildAccessToken(user, request.deviceFingerprint());

        span.setAttribute("auth.refresh.success", true);
        LOG.debugf("Rotated refresh token for user %s", user.id);

        return TokenResponse.of(newAccessToken, newRefreshToken);
    }

    // ----------------------------------------------------------------
    // ENDPOINT 3: POST /auth/logout
    // ----------------------------------------------------------------

    @Transactional
    public void logout(LogoutRequest request, String userId) {
        Span span = Span.current();
        span.setAttribute("auth.operation", "logout");
        span.setAttribute("auth.userId",    userId != null ? userId : "anonymous");

        refreshTokenService.revoke(request.refreshToken());

        Map<String, Object> payload = new HashMap<>();
        payload.put("user_id",   userId != null ? userId : "anonymous");
        payload.put("timestamp", Instant.now().toString());

        writeOutboxEvent("user.logout", userId, payload);

        span.setAttribute("auth.logout.success", true);
        LOG.infof("User %s logged out", userId != null ? userId : "anonymous");
    }

    // ----------------------------------------------------------------
    // ENDPOINT 4: DELETE /auth/devices/{deviceId}/revoke
    // ----------------------------------------------------------------

    @Transactional
    public DeviceRevokeResponse revokeDevice(String deviceId, String userId) {
        Span span = Span.current();
        span.setAttribute("auth.operation",   "device.revoke");
        span.setAttribute("auth.userId",      userId);
        span.setAttribute("auth.device.id",   truncate(deviceId));

        // 1. Validate device belongs to this user
        AuthDevice device = AuthDevice.findByUserAndFingerprint(userId, deviceId)
                .orElseThrow(() -> {
                    span.setAttribute("auth.failure.reason", "device_not_found");
                    span.setStatus(StatusCode.ERROR, "device_not_found");
                    return new AuthException(Response.Status.NOT_FOUND,
                            "device_not_found",
                            "Device not found or does not belong to this user");
                });

        if (device.revoked) {
            span.setAttribute("auth.failure.reason", "device_already_revoked");
            throw new AuthException(Response.Status.CONFLICT,
                    "device_already_revoked", "Device is already revoked");
        }

        // 2. Revoke device in PostgreSQL
        long dbRevoked = AuthDevice.revokeAllForFingerprint(deviceId, "USER_REQUESTED");

        // 3. Clear all Redis refresh tokens for this device
        int redisCleared = refreshTokenService.revokeAllForDevice(deviceId);

        // 4. Write outbox event
        writeOutboxEvent("device.revoked", userId, Map.of(
                "user_id",           userId,
                "device_fingerprint", deviceId,
                "sessions_cleared",  redisCleared,
                "timestamp",         Instant.now().toString()
        ));

        span.setAttribute("auth.device.sessions_cleared", redisCleared);
        span.setAttribute("auth.device.revoke.success",   true);
        LOG.infof("User %s revoked device %s... — %d sessions cleared",
                userId, truncate(deviceId), redisCleared);

        return new DeviceRevokeResponse(deviceId, true, Instant.now(), redisCleared);
    }

    // ----------------------------------------------------------------
    // ENDPOINT 6: POST /auth/introspect
    // ----------------------------------------------------------------

    public IntrospectResponse introspect(String userId, java.util.List<String> roles,
                                         String device, Long exp, String merchantId) {
        Span span = Span.current();
        span.setAttribute("auth.operation", "introspect");

        if (userId == null) {
            span.setAttribute("auth.introspect.active", false);
            return IntrospectResponse.inactive();
        }

        span.setAttribute("auth.userId",             userId);
        span.setAttribute("auth.introspect.active",  true);
        span.setAttribute("auth.roles",              String.join(",", roles));
        return new IntrospectResponse(true, userId, roles, exp, device, merchantId);
    }

    // ----------------------------------------------------------------
    // OKTA SSO: GET /auth/okta/callback
    // ----------------------------------------------------------------

    @Transactional
    public TokenResponse handleOktaCallback(AuthDtos.OktaClaims claims, String deviceFingerprint) {
        Span span = Span.current();
        span.setAttribute("auth.operation",          "okta.callback");
        span.setAttribute("auth.okta.sub",           claims.sub());
        span.setAttribute("auth.email.domain",       extractDomain(claims.email()));
        span.setAttribute("auth.device.fingerprint", truncate(deviceFingerprint));

        // 1. Find or create user
        AuthUser user = AuthUser.findByOktaSubject(claims.sub())
                .orElseGet(() -> AuthUser.findByEmail(claims.email())
                        .map(existing -> {
                            existing.oktaSubject = claims.sub();
                            existing.persist();
                            span.setAttribute("auth.okta.user_linked", true);
                            return existing;
                        })
                        .orElseGet(() -> {
                            span.setAttribute("auth.okta.user_created", true);
                            return createOktaUser(claims);
                        }));

        span.setAttribute("auth.userId", user.id);
        span.setAttribute("auth.roles",  user.roles);

        // 2. Check account status
        if (!user.isActive()) {
            span.setAttribute("auth.failure.reason", "account_suspended");
            span.setStatus(StatusCode.ERROR, "account_suspended");
            throw new AuthException(Response.Status.FORBIDDEN,
                    "account_suspended", "Account is not active");
        }

        // 3. Update last_login
        user.lastLogin = Instant.now();
        user.persist();

        // 4. Upsert device
        upsertDevice(user.id, deviceFingerprint);

        // 5. Issue TradeFlow tokens
        String accessToken  = jwtService.buildAccessToken(user, deviceFingerprint);
        String refreshToken = refreshTokenService.issue(user, deviceFingerprint);

        // 6. Write outbox event
        writeOutboxEvent("user.login.okta", user.id, Map.of(
                "user_id",      user.id,
                "email",        user.email,
                "okta_subject", claims.sub(),
                "timestamp",    Instant.now().toString()
        ));

        span.setAttribute("auth.okta.login.success", true);
        LOG.infof("Okta SSO login for user %s (email: %s)", user.id, user.email);

        return TokenResponse.of(accessToken, refreshToken);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private AuthUser createOktaUser(AuthDtos.OktaClaims claims) {
        AuthUser user = new AuthUser();
        user.id             = "usr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        user.email          = claims.email();
        user.hashedPassword = "$OKTA_SSO_NO_PASSWORD$";
        user.oktaSubject    = claims.sub();
        user.roles          = "BUYER";
        user.mfaEnabled     = false;
        user.status         = "ACTIVE";
        user.persist();

        writeOutboxEvent("user.registered", user.id, Map.of(
                "user_id",   user.id,
                "email",     user.email,
                "source",    "OKTA_SSO",
                "timestamp", Instant.now().toString()
        ));

        LOG.infof("Created new user from Okta SSO: %s (email: %s)", user.id, user.email);
        return user;
    }

    private void validateDeviceNotRevoked(String userId, String deviceFingerprint) {
        AuthDevice.findByUserAndFingerprint(userId, deviceFingerprint)
                .ifPresent(device -> {
                    if (device.revoked) {
                        Span.current().setAttribute("auth.failure.reason", "device_revoked");
                        Span.current().setStatus(StatusCode.ERROR, "device_revoked");
                        throw new AuthException(Response.Status.FORBIDDEN,
                                "device_revoked",
                                "This device has been revoked. Please use a different device.");
                    }
                });
    }

    private void upsertDevice(String userId, String deviceFingerprint) {
        AuthDevice.findByUserAndFingerprint(userId, deviceFingerprint)
                .ifPresentOrElse(
                        device -> {
                            device.lastSeen = Instant.now();
                            device.persist();
                        },
                        () -> {
                            AuthDevice device = new AuthDevice();
                            device.id                = UUID.randomUUID().toString();
                            device.userId            = userId;
                            device.deviceFingerprint = deviceFingerprint;
                            device.lastSeen          = Instant.now();
                            device.persist();
                        }
                );
    }

    private void writeOutboxEvent(String eventType, String aggregateId, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            AuthOutbox.of(eventType, aggregateId, json).persist();
        } catch (JsonProcessingException e) {
            LOG.errorf("Failed to write outbox event %s for %s: %s",
                    eventType, aggregateId, e.getMessage());
        }
    }

    private void writeAuditEvent(String userId, String eventType, String deviceFingerprint,
                                 String ipAddress, Map<String, Object> details) {
        try {
            String detailsJson = objectMapper.writeValueAsString(details);
            AuthUser.getEntityManager().createNativeQuery(
                            "INSERT INTO auth_audit_log " +
                                    "(user_id, event_type, device_fingerprint, ip_address, details) " +
                                    "VALUES (?, ?, ?, ?, ?::jsonb)"
                    ).setParameter(1, userId)
                    .setParameter(2, eventType)
                    .setParameter(3, deviceFingerprint)
                    .setParameter(4, ipAddress)
                    .setParameter(5, detailsJson)
                    .executeUpdate();
        } catch (Exception e) {
            LOG.warnf("Failed to write audit event %s: %s", eventType, e.getMessage());
        }
    }

    /** Safe truncation for logging/tagging fingerprints — never log full fingerprint */
    private String truncate(String s) {
        if (s == null) return "null";
        return s.substring(0, Math.min(8, s.length()));
    }

    private String extractDomain(String email) {
        if (email == null || !email.contains("@")) return "unknown";
        return email.substring(email.indexOf('@') + 1);
    }

    // ----------------------------------------------------------------
    // AuthException
    // ----------------------------------------------------------------

    public static class AuthException extends RuntimeException {
        public final Response.Status status;
        public final String          errorCode;
        public final String          description;

        public AuthException(Response.Status status, String errorCode, String description) {
            super(description);
            this.status      = status;
            this.errorCode   = errorCode;
            this.description = description;
        }
    }
}