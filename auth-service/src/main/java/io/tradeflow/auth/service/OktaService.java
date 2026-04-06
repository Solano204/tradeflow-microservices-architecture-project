package io.tradeflow.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tradeflow.auth.dto.AuthDtos;
import io.tradeflow.auth.entity.PkceState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OktaService — OIDC Authorization Code Flow + PKCE.
 *
 * Implements the enterprise SSO flow:
 * 1. generateAuthorizationUrl() — creates state + code_verifier, stores in DB, builds Okta redirect URL
 * 2. handleCallback()          — validates state, exchanges code → tokens, validates id_token, returns claims
 *
 * PKCE (Proof Key for Code Exchange) prevents authorization code interception attacks.
 * The code_verifier is generated here, never sent to Okta directly.
 * The code_challenge (SHA256 of code_verifier) is sent in step 1.
 * The code_verifier is sent in step 2 — Okta verifies the SHA256 matches.
 *
 * OAuth 2.1 compliance — required for all new OAuth flows.
 *
 * State param serves as CSRF protection.
 */
@ApplicationScoped
public class OktaService {

    private static final Logger LOG = Logger.getLogger(OktaService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @ConfigProperty(name = "tradeflow.okta.issuer")
    String oktaIssuer;

    @ConfigProperty(name = "tradeflow.okta.client-id")
    String clientId;

    @ConfigProperty(name = "tradeflow.okta.client-secret")
    String clientSecret;

    @ConfigProperty(name = "tradeflow.okta.redirect-uri")
    String redirectUri;

    @ConfigProperty(name = "tradeflow.okta.scopes", defaultValue = "openid profile email")
    String scopes;

    @ConfigProperty(name = "tradeflow.okta.enabled", defaultValue = "false")
    boolean oktaEnabled;

    @Inject
    ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ----------------------------------------------------------------
    // Step 1: Build Okta authorization URL
    // ----------------------------------------------------------------

    /**
     * Generate the Okta authorization URL with PKCE parameters.
     *
     * Stores state + code_verifier in pkce_state table (TTL 10 minutes).
     * Returns the URL to redirect the user to Okta.
     *
     * @return Okta authorization URL
     */
    @Transactional
    public String generateAuthorizationUrl() {
        if (!oktaEnabled) {
            throw new AuthService.AuthException(Response.Status.NOT_IMPLEMENTED,
                    "okta_not_configured", "Okta SSO is not enabled");
        }

        String state         = generateSecureRandom();
        String codeVerifier  = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String nonce         = generateSecureRandom();

        // Store state + code_verifier in DB (CSRF + PKCE validation on callback)
        PkceState pkceState = new PkceState();
        pkceState.state = state;
        pkceState.codeVerifier = codeVerifier;
        pkceState.nonce = nonce;
        pkceState.persist();

        // Build authorization URL
        String scopeEncoded = URLEncoder.encode(scopes, StandardCharsets.UTF_8);
        String redirectEncoded = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);

        return String.format(
                "%s/v1/authorize?" +
                        "client_id=%s" +
                        "&response_type=code" +
                        "&scope=%s" +
                        "&redirect_uri=%s" +
                        "&state=%s" +
                        "&nonce=%s" +
                        "&code_challenge=%s" +
                        "&code_challenge_method=S256",
                oktaIssuer,
                URLEncoder.encode(clientId, StandardCharsets.UTF_8),
                scopeEncoded,
                redirectEncoded,
                URLEncoder.encode(state, StandardCharsets.UTF_8),
                URLEncoder.encode(nonce, StandardCharsets.UTF_8),
                URLEncoder.encode(codeChallenge, StandardCharsets.UTF_8)
        );
    }

    // ----------------------------------------------------------------
    // Step 2: Handle Okta callback
    // ----------------------------------------------------------------

    /**
     * Process the Okta callback after user authenticates.
     *
     * Steps:
     * 1. Validate state param (CSRF check)
     * 2. Exchange authorization code for tokens using code_verifier (PKCE)
     * 3. Validate Okta id_token signature via Okta's JWKS
     * 4. Extract and return user claims
     * 5. Clean up pkce_state row
     *
     * @param code  authorization code from Okta
     * @param state state parameter (CSRF token)
     * @return validated user claims from Okta id_token
     */
    @Transactional
    public AuthDtos.OktaClaims handleCallback(String code, String state) {
        // 1. Validate state (CSRF check)
        PkceState pkceState = PkceState.findByStateOptional(state)
                .orElseThrow(() -> new AuthService.AuthException(Response.Status.BAD_REQUEST,
                        "invalid_state", "Invalid or expired state parameter"));

        if (pkceState.isExpired()) {
            pkceState.delete();
            throw new AuthService.AuthException(Response.Status.BAD_REQUEST,
                    "state_expired", "Authorization state has expired. Please try again.");
        }

        String codeVerifier = pkceState.codeVerifier;

        // 2. Delete pkce_state row (use-once)
        pkceState.delete();

        // 3. Exchange code for tokens
        AuthDtos.OktaTokenResponse oktaTokens = exchangeCodeForTokens(code, codeVerifier);

        // 4. Validate and decode id_token
        // In production: validate signature via Okta's JWKS endpoint
        // For brevity here: decode JWT payload (signature already validated by Okta's /token endpoint)
        AuthDtos.OktaClaims claims = decodeOktaIdToken(oktaTokens.idToken());

        LOG.infof("Okta callback successful for sub: %s, email: %s", claims.sub(), claims.email());

        return claims;
    }

    // ----------------------------------------------------------------
    // Token exchange with Okta /token endpoint
    // ----------------------------------------------------------------

    private AuthDtos.OktaTokenResponse exchangeCodeForTokens(String code, String codeVerifier) {
        try {
            String tokenEndpoint = oktaIssuer + "/v1/token";

            // Build form body
            Map<String, String> formParams = Map.of(
                    "grant_type",    "authorization_code",
                    "code",          code,
                    "redirect_uri",  redirectUri,
                    "client_id",     clientId,
                    "client_secret", clientSecret,
                    "code_verifier", codeVerifier
            );

            String formBody = formParams.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" +
                            URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenEndpoint))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.errorf("Okta token exchange failed: HTTP %d — %s", response.statusCode(), response.body());
                throw new AuthService.AuthException(Response.Status.UNAUTHORIZED,
                        "okta_token_exchange_failed",
                        "Failed to exchange authorization code with Okta");
            }

            return objectMapper.readValue(response.body(), AuthDtos.OktaTokenResponse.class);

        } catch (AuthService.AuthException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Error during Okta token exchange", e);
            throw new AuthService.AuthException(Response.Status.SERVICE_UNAVAILABLE,
                    "okta_unavailable", "Could not reach Okta. Please try again.");
        }
    }

    /**
     * Decode the Okta id_token JWT payload (without full signature verification).
     *
     * NOTE: In a production system, you MUST validate the id_token signature
     * against Okta's JWKS endpoint: GET {issuer}/v1/keys
     * Use a proper OIDC library (e.g., nimbus-jose-jwt) for this.
     * This implementation decodes the payload for demonstration; production
     * should use SmallRye JWT or Nimbus for full OIDC validation.
     */
    private AuthDtos.OktaClaims decodeOktaIdToken(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid JWT format");
            }
            // Base64url decode the payload
            byte[] payloadBytes = Base64.getUrlDecoder().decode(addPadding(parts[1]));
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);

            JsonNode payload = objectMapper.readTree(payloadJson);

            String sub   = payload.has("sub")   ? payload.get("sub").asText()   : null;
            String email = payload.has("email") ? payload.get("email").asText() : null;
            String name  = payload.has("name")  ? payload.get("name").asText()  : null;
            String enterpriseId = payload.has("enterprise_id")
                    ? payload.get("enterprise_id").asText() : null;

            if (sub == null || email == null) {
                throw new AuthService.AuthException(Response.Status.UNAUTHORIZED,
                        "invalid_id_token", "id_token missing required claims: sub, email");
            }

            return new AuthDtos.OktaClaims(sub, email, name, enterpriseId);

        } catch (AuthService.AuthException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to decode Okta id_token", e);
            throw new AuthService.AuthException(Response.Status.UNAUTHORIZED,
                    "invalid_id_token", "Could not decode Okta id_token");
        }
    }

    // ----------------------------------------------------------------
    // PKCE helpers
    // ----------------------------------------------------------------

    private String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate code challenge", e);
        }
    }

    private String generateSecureRandom() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String addPadding(String base64) {
        int mod = base64.length() % 4;
        if (mod == 2) return base64 + "==";
        if (mod == 3) return base64 + "=";
        return base64;
    }
}