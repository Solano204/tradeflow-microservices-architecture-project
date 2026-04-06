
package io.tradeflow.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * All DTOs for the Auth Service.
 * Grouped as inner classes for clarity — split into separate files if preferred.
 */
public final class AuthDtos {

    private AuthDtos() {}

    // ============================================================
    // REQUEST DTOs
    // ============================================================

    /**
     * POST /auth/login request body.
     */
    public record LoginRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Email must be valid")
            String email,

            @NotBlank(message = "Password is required")
            @Size(min = 6, max = 255, message = "Password must be between 6 and 255 characters")
            String password,

            @JsonProperty("device_fingerprint")
            @NotBlank(message = "Device fingerprint is required")
            String deviceFingerprint,

            /**
             * Optional — required only when MFA is enabled for the account.
             * 6-digit TOTP code from authenticator app.
             */
            @JsonProperty("mfa_code")
            String mfaCode
    ) {}

    /**
     * POST /auth/refresh request body.
     */
    public record RefreshRequest(
            @JsonProperty("refresh_token")
            @NotBlank(message = "Refresh token is required")
            String refreshToken,

            @JsonProperty("device_fingerprint")
            @NotBlank(message = "Device fingerprint is required")
            String deviceFingerprint
    ) {}

    /**
     * POST /auth/logout request body.
     */
    public record LogoutRequest(
            @JsonProperty("refresh_token")
            @NotBlank(message = "Refresh token is required")
            String refreshToken
    ) {}

    /**
     * POST /auth/introspect request body.
     */
    public record IntrospectRequest(
            @NotBlank(message = "Token is required")
            String token
    ) {}

    // ============================================================
    // RESPONSE DTOs
    // ============================================================

    /**
     * Token response — returned by /auth/login and /auth/refresh.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenResponse(
            @JsonProperty("access_token")  String accessToken,
            @JsonProperty("token_type")    String tokenType,
            @JsonProperty("expires_in")    int expiresIn,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("refresh_expires_in") int refreshExpiresIn
    ) {
        public static TokenResponse of(String accessToken, String refreshToken) {
            return new TokenResponse(accessToken, "Bearer", 900, refreshToken, 2592000);
        }
    }

    /**
     * Introspection response — returned by /auth/introspect.
     * RFC 7662 compliant.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IntrospectResponse(
            boolean active,
            String sub,
            List<String> roles,
            Long exp,
            String device,
            @JsonProperty("merchant_id") String merchantId
    ) {
        public static IntrospectResponse inactive() {
            return new IntrospectResponse(false, null, null, null, null, null);
        }
    }

    /**
     * JWKS response — returned by /.well-known/jwks.json.
     * RFC 7517 JSON Web Key Set.
     */
    public record JwksResponse(List<JwkKey> keys) {
        public record JwkKey(
                String kty,
                String use,
                String alg,
                String kid,
                String n,   // RSA modulus (base64url)
                String e    // RSA exponent (base64url)
        ) {}
    }

    /**
     * Device revocation response.
     */
    public record DeviceRevokeResponse(
            @JsonProperty("device_id")      String deviceId,
            @JsonProperty("revoked")        boolean revoked,
            @JsonProperty("revoked_at")     Instant revokedAt,
            @JsonProperty("sessions_cleared") int sessionsCleared
    ) {}

    /**
     * Standard error response.
     */
    public record ErrorResponse(
            String error,
            @JsonProperty("error_description") String errorDescription,
            @JsonProperty("error_uri") String errorUri
    ) {
        public static ErrorResponse of(String error, String description) {
            return new ErrorResponse(error, description, null);
        }
    }

    // ============================================================
    // INTERNAL / REDIS DTOs
    // ============================================================

    /**
     * Data stored in Redis under key "rt:{refresh_token_value}".
     * TTL = 30 days.
     */
    public record RefreshTokenData(
            @JsonProperty("user_id")           String userId,
            @JsonProperty("device_fingerprint") String deviceFingerprint,
            @JsonProperty("issued_at")         long issuedAt,
            @JsonProperty("roles")             List<String> roles,
            @JsonProperty("merchant_id")       String merchantId
    ) {}

    // ============================================================
    // OKTA / SSO DTOs
    // ============================================================

    /**
     * Okta /token endpoint response (parsed from JSON).
     */
    public record OktaTokenResponse(
            @JsonProperty("access_token")  String accessToken,
            @JsonProperty("id_token")      String idToken,
            @JsonProperty("token_type")    String tokenType,
            @JsonProperty("expires_in")    int expiresIn,
            @JsonProperty("scope")         String scope
    ) {}

    /**
     * Claims extracted from Okta id_token after validation.
     */
    public record OktaClaims(
            String sub,         // Okta user ID — used as unique identifier
            String email,
            String name,
            @JsonProperty("enterprise_id") String enterpriseId
    ) {}
}