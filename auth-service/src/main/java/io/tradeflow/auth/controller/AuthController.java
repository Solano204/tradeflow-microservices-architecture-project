package io.tradeflow.auth.controller;

import io.opentelemetry.api.trace.Span;
import io.tradeflow.auth.dto.AuthDtos;
import io.tradeflow.auth.dto.AuthDtos.*;
import io.tradeflow.auth.security.JwtKeyLoader;
import io.tradeflow.auth.service.AuthService;
import io.tradeflow.auth.service.OktaService;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * AuthController — all 7 auth endpoints.
 *
 * TRACING:
 * Quarkus OTel auto-creates an HTTP server span for every request.
 * Here we add business-level tags to that span so traces are searchable
 * by operation, userId, device, etc. in Zipkin.
 *
 * No manual span creation is needed at the controller layer — we only
 * enrich the existing auto-created span via Span.current().
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Authentication", description = "JWT issuance, refresh, logout, JWKS, SSO")
public class AuthController {

    private static final Logger LOG = Logger.getLogger(AuthController.class);

    @Inject AuthService   authService;
    @Inject OktaService   oktaService;
    @Inject JwtKeyLoader  keyLoader;
    @Inject JsonWebToken  jwt;

    // ================================================================
    // ENDPOINT 1: POST /auth/login
    // ================================================================

    @POST
    @Path("/auth/login")
    @PermitAll
    @Operation(
            summary     = "Login and issue tokens",
            description = "Validate credentials (BCrypt + optional TOTP MFA) and issue " +
                    "an RS256 access token (15min TTL) and refresh token (30 day TTL).")
    @APIResponse(responseCode = "200", description = "Token pair issued",
            content = @Content(schema = @Schema(implementation = TokenResponse.class)))
    @APIResponse(responseCode = "401", description = "Invalid credentials or MFA code")
    @APIResponse(responseCode = "403", description = "Account suspended or device revoked")
    public Response login(@Valid LoginRequest request) {
        // Tag the auto-created HTTP span with the endpoint name
        // AuthService.login() adds deeper business tags (userId, roles, etc.)
        Span.current().setAttribute("http.route",      "POST /auth/login");
        Span.current().setAttribute("auth.entry.point", "login");

        TokenResponse tokens = authService.login(request);
        return Response.ok(tokens).build();
    }

    // ================================================================
    // ENDPOINT 2: POST /auth/refresh
    // ================================================================

    @POST
    @Path("/auth/refresh")
    @PermitAll
    @Operation(
            summary     = "Rotate refresh token",
            description = "Validate the submitted refresh token, verify device fingerprint " +
                    "(theft detection), delete old token, issue new token pair.")
    @APIResponse(responseCode = "200", description = "New token pair issued")
    @APIResponse(responseCode = "401", description = "Invalid, expired, or stolen refresh token")
    public Response refresh(@Valid RefreshRequest request) {
        Span.current().setAttribute("http.route",       "POST /auth/refresh");
        Span.current().setAttribute("auth.entry.point", "refresh");

        TokenResponse tokens = authService.refresh(request);
        return Response.ok(tokens).build();
    }

    // ================================================================
    // ENDPOINT 3: POST /auth/logout
    // ================================================================

    @POST
    @Path("/auth/logout")
    @PermitAll
    @Operation(
            summary     = "Logout — revoke refresh token",
            description = "Delete the refresh token from Redis. The access token remains " +
                    "valid for up to 15 minutes (JWT stateless trade-off).")
    @APIResponse(responseCode = "204", description = "Logged out successfully")
    public Response logout(@Valid LogoutRequest request, @Context SecurityContext securityContext) {
        Span.current().setAttribute("http.route",       "POST /auth/logout");
        Span.current().setAttribute("auth.entry.point", "logout");

        String userId = jwt != null ? jwt.getSubject() : "anonymous";

        if (userId == null || userId.equals("anonymous")) {
            throw new AuthService.AuthException(Response.Status.UNAUTHORIZED,
                    "unauthorized", "Authentication required for logout");
        }

        authService.logout(request, userId);
        return Response.noContent().build();
    }

    // ================================================================
    // ENDPOINT 4: DELETE /auth/devices/{deviceId}/revoke
    // ================================================================

    @DELETE
    @Path("/auth/devices/{deviceId}/revoke")
    @RolesAllowed({"BUYER", "MERCHANT", "ADMIN"})
    @Operation(
            summary     = "Revoke all sessions for a device",
            description = "Nuclear option for account security. Marks the device as revoked " +
                    "in PostgreSQL AND deletes all Redis refresh tokens for that device.")
    @APIResponse(responseCode = "200", description = "Device revoked",
            content = @Content(schema = @Schema(implementation = DeviceRevokeResponse.class)))
    @APIResponse(responseCode = "404", description = "Device not found or does not belong to user")
    @APIResponse(responseCode = "409", description = "Device already revoked")
    public Response revokeDevice(@PathParam("deviceId") String deviceId) {
        Span.current().setAttribute("http.route",       "DELETE /auth/devices/{deviceId}/revoke");
        Span.current().setAttribute("auth.entry.point", "device.revoke");
        Span.current().setAttribute("auth.device.id",  deviceId.substring(0, Math.min(8, deviceId.length())));

        String userId = jwt.getSubject();
        Span.current().setAttribute("auth.userId", userId);

        DeviceRevokeResponse result = authService.revokeDevice(deviceId, userId);
        return Response.ok(result).build();
    }

    // ================================================================
    // ENDPOINT 5: GET /.well-known/jwks.json
    // ================================================================

    @GET
    @Path("/.well-known/jwks.json")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary     = "JWKS — public key for JWT verification",
            description = "Exposes the RS256 public key in JWK Set format. " +
                    "All other services cache this and verify JWTs locally. " +
                    "Cache-Control: public, max-age=3600.")
    @APIResponse(responseCode = "200", description = "JWK Set",
            content = @Content(schema = @Schema(implementation = JwksResponse.class)))
    public Response jwks() {
        // JWKS is a read-only, cached endpoint — minimal tagging needed
        Span.current().setAttribute("http.route",       "GET /.well-known/jwks.json");
        Span.current().setAttribute("auth.entry.point", "jwks");

        JwksResponse jwks = keyLoader.getJwks();
        return Response.ok(jwks)
                .header("Cache-Control", "public, max-age=3600")
                .header("Content-Type", "application/json")
                .build();
    }

    // ================================================================
    // ENDPOINT 6: POST /auth/introspect
    // ================================================================

    @POST
    @Path("/auth/introspect")
    @PermitAll
    @Operation(
            summary     = "Token introspection (legacy)",
            description = "RFC 7662 token introspection. PERFORMANCE WARNING: " +
                    "puts Auth on the hot path. Prefer JWKS for new services.")
    @APIResponse(responseCode = "200", description = "Introspection result",
            content = @Content(schema = @Schema(implementation = IntrospectResponse.class)))
    public Response introspect(@Valid IntrospectRequest request) {
        Span.current().setAttribute("http.route",       "POST /auth/introspect");
        Span.current().setAttribute("auth.entry.point", "introspect");

        try {
            org.eclipse.microprofile.jwt.JsonWebToken parsedToken = parseAndValidateToken(request.token());

            String       userId     = parsedToken.getSubject();
            List<String> roles      = List.copyOf(parsedToken.getGroups());
            String       device     = parsedToken.getClaim("device");
            Long         exp        = parsedToken.getExpirationTime();
            String       merchantId = parsedToken.getClaim("merchant_id");

            IntrospectResponse response = authService.introspect(userId, roles, device, exp, merchantId);
            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.debugf("Token introspection: invalid token — %s", e.getMessage());
            return Response.ok(IntrospectResponse.inactive()).build();
        }
    }

    // ================================================================
    // ENDPOINT 7: GET /auth/okta/callback
    // ================================================================

    @GET
    @Path("/auth/okta/callback")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary     = "Okta OIDC callback",
            description = "Handles the OIDC Authorization Code + PKCE callback from Okta.")
    @APIResponse(responseCode = "200", description = "TradeFlow token pair issued")
    @APIResponse(responseCode = "400", description = "Invalid state or expired authorization")
    @APIResponse(responseCode = "503", description = "Okta unavailable")
    public Response oktaCallback(
            @QueryParam("code")              String code,
            @QueryParam("state")             String state,
            @QueryParam("error")             String error,
            @QueryParam("error_description") String errorDescription) {

        Span.current().setAttribute("http.route",       "GET /auth/okta/callback");
        Span.current().setAttribute("auth.entry.point", "okta.callback");
        Span.current().setAttribute("auth.okta.has_error", error != null);

        if (error != null) {
            Span.current().setAttribute("auth.okta.error", error);
            LOG.warnf("Okta returned error: %s — %s", error, errorDescription);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of(error, errorDescription))
                    .build();
        }

        if (code == null || state == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of("missing_params", "code and state are required"))
                    .build();
        }

        String deviceFingerprint = "sha256:okta-sso-" + state.substring(0, Math.min(8, state.length()));

        AuthDtos.OktaClaims claims = oktaService.handleCallback(code, state);
        TokenResponse tokens       = authService.handleOktaCallback(claims, deviceFingerprint);

        return Response.ok(tokens).build();
    }

    // ================================================================
    // SSO initiation
    // ================================================================

    @GET
    @Path("/auth/okta/login")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Initiate Okta SSO login", description = "Returns the Okta authorization URL")
    public Response initiateOktaLogin() {
        Span.current().setAttribute("http.route",       "GET /auth/okta/login");
        Span.current().setAttribute("auth.entry.point", "okta.initiate");

        String authUrl = oktaService.generateAuthorizationUrl();
        return Response.ok(java.util.Map.of("authorization_url", authUrl)).build();
    }

    // ================================================================
    // Private helpers
    // ================================================================

    private org.eclipse.microprofile.jwt.JsonWebToken parseAndValidateToken(String tokenString) {
        io.smallrye.jwt.auth.principal.JWTParser parser =
                io.quarkus.arc.Arc.container()
                        .instance(io.smallrye.jwt.auth.principal.JWTParser.class)
                        .get();
        try {
            return parser.parse(tokenString);
        } catch (Exception e) {
            throw new RuntimeException("Token validation failed: " + e.getMessage(), e);
        }
    }
}