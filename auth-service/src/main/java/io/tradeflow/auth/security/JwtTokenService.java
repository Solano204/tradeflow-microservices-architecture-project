
package io.tradeflow.auth.security;

import io.smallrye.jwt.build.Jwt;
import io.tradeflow.auth.entity.AuthUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JwtTokenService — RS256 JWT token builder and validator.
 *
 * Uses SmallRye JWT build API to sign tokens with the private key
 * loaded by JwtKeyLoader.
 *
 * JWT Payload:
 * {
 *   "sub": "usr_7f3a",
 *   "roles": ["BUYER"],
 *   "merchant_id": null,
 *   "device": "sha256:abc",
 *   "iat": 1700000000,
 *   "exp": 1700000900,
 *   "iss": "https://auth.tradeflow.io",
 *   "kid": "auth-key-2024-v1"
 * }
 *
 * TTL: 15 minutes (900 seconds).
 * Verification: done by JWKS endpoint + SmallRye JWT in all other services.
 */
@ApplicationScoped
public class JwtTokenService {

    private static final Logger LOG = Logger.getLogger(JwtTokenService.class);

    @Inject
    JwtKeyLoader keyLoader;

    @ConfigProperty(name = "tradeflow.jwt.issuer", defaultValue = "https://auth.tradeflow.io")
    String issuer;

    @ConfigProperty(name = "tradeflow.jwt.access-token.ttl-seconds", defaultValue = "900")
    long accessTokenTtlSeconds;

    /**
     * Build and sign a new RS256 access token for the given user.
     *
     * @param user            the authenticated user
     * @param deviceFingerprint the device fingerprint from the request
     * @return signed JWT string
     */
    public String buildAccessToken(AuthUser user, String deviceFingerprint) {
        try {
            Instant now = Instant.now();
            Instant exp = now.plusSeconds(accessTokenTtlSeconds);

            Set<String> rolesSet = new HashSet<>(user.getRoleList());

            return Jwt.issuer(issuer)
                    .subject(user.id)
                    .groups(rolesSet)
                    .claim("roles", user.getRoleList())
                    .claim("device", deviceFingerprint)
                    .claim("email", user.email)
                    .issuedAt(now.getEpochSecond())
                    .expiresAt(exp.getEpochSecond())
                    // Only add merchant_id if present — BUYER users don't have one
                    .claim("merchant_id", user.merchantId != null ? user.merchantId : "")
                    .jws()
                    .keyId(keyLoader.getKeyId())
                    .algorithm(io.smallrye.jwt.algorithm.SignatureAlgorithm.RS256)
                    .sign(keyLoader.getPrivateKey());

        } catch (Exception e) {
            LOG.error("Failed to build access token for user: " + user.id, e);
            throw new RuntimeException("Token generation failed", e);
        }
    }

    /**
     * Extract the subject (user ID) from a JWT without full validation.
     * Used by introspect endpoint which does its own signature verification.
     *
     * For full verification, use the SmallRye JWT library which validates
     * signature via the JWKS endpoint.
     */
    public String extractSubject(JsonWebToken token) {
        return token.getSubject();
    }

    /**
     * Extract the device fingerprint claim from a validated JWT.
     */
    public String extractDevice(JsonWebToken token) {
        return token.getClaim("device");
    }

    /**
     * Extract roles from a validated JWT.
     */
    public List<String> extractRoles(JsonWebToken token) {
        Object roles = token.getClaim("roles");
        if (roles instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }
}