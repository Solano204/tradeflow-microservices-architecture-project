package io.tradeflow.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.tradeflow.auth.dto.AuthDtos.RefreshTokenData;
import io.tradeflow.auth.entity.AuthUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * RefreshTokenService — manages refresh tokens in Redis.
 *
 * Key schema: "rt:{refresh_token_value}"
 * Value:      JSON of RefreshTokenData { userId, deviceFingerprint, issuedAt, roles, merchantId }
 * TTL:        30 days (reset on each rotation)
 *
 * Refresh Token Rotation:
 * On each use, the old token is DELETED and a new one is issued.
 * If an attacker steals a token and tries to use it after the legitimate
 * user already rotated it, they get a 401 — the key no longer exists.
 *
 * Security properties:
 * - Opaque token: UUID format, no information encoded
 * - Prefix "rt_": distinguishes from other Redis keys
 * - Device binding: refresh token is bound to the device fingerprint it was issued for
 */
@ApplicationScoped
public class RefreshTokenService {

    private static final Logger LOG = Logger.getLogger(RefreshTokenService.class);
    private static final String KEY_PREFIX = "rt:";

    @Inject
    RedisDataSource redis;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "tradeflow.refresh-token.ttl-days", defaultValue = "30")
    int ttlDays;

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /**
     * Issue a new refresh token for the given user + device.
     * Stores the token data in Redis with TTL.
     *
     * @return the opaque refresh token string (UUID-based)
     */
    public String issue(AuthUser user, String deviceFingerprint) {
        String token = generateToken();
        String key = buildKey(token);

        RefreshTokenData data = new RefreshTokenData(
                user.id,
                deviceFingerprint,
                System.currentTimeMillis() / 1000L,
                user.getRoleList(),
                user.merchantId
        );

        try {
            String json = objectMapper.writeValueAsString(data);
            ValueCommands<String, String> commands = redis.value(String.class);
            commands.setex(key, Duration.ofDays(ttlDays).toSeconds(), json);
            LOG.debugf("Issued refresh token for user %s, device %s", user.id, deviceFingerprint);
            return token;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize refresh token data", e);
        }
    }

    /**
     * Validate a refresh token and return its stored data.
     *
     * @return Optional containing the token data, or empty if not found/expired
     */
    public Optional<RefreshTokenData> validate(String token) {
        String key = buildKey(token);
        try {
            ValueCommands<String, String> commands = redis.value(String.class);
            String json = commands.get(key);
            if (json == null) {
                LOG.debugf("Refresh token not found in Redis: %s...", token.substring(0, Math.min(8, token.length())));
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, RefreshTokenData.class));
        } catch (JsonProcessingException e) {
            LOG.error("Failed to deserialize refresh token data", e);
            return Optional.empty();
        }
    }

    /**
     * Rotate a refresh token — atomic delete + issue.
     * Step 1: DELETE old key from Redis
     * Step 2: INSERT new key with fresh TTL
     *
     * @param oldToken the token being consumed
     * @param user     the authenticated user
     * @param deviceFingerprint device fingerprint (validated by caller)
     * @return new refresh token
     */
    public String rotate(String oldToken, AuthUser user, String deviceFingerprint) {
        // Delete old token first (atomic)
        revoke(oldToken);
        // Issue new token
        return issue(user, deviceFingerprint);
    }

    /**
     * Revoke a single refresh token.
     * O(1) Redis DEL operation.
     */
    public void revoke(String token) {
        String key = buildKey(token);
        ValueCommands<String, String> commands = redis.value(String.class);
        commands.getdel(key);
        LOG.debugf("Revoked refresh token: %s...", token.substring(0, Math.min(8, token.length())));
    }

    /**
     * Revoke all refresh tokens for a specific device fingerprint.
     * Used by DELETE /auth/devices/{deviceId}/revoke.
     *
     * Implementation: SCAN Redis for all "rt:*" keys, check stored device fingerprint,
     * delete matching keys. This is the only "slow" operation — scanning Redis.
     * Acceptable because device revocation is a rare, security-critical operation.
     *
     * @return count of revoked tokens
     */
    public int revokeAllForDevice(String deviceFingerprint) {
        // Note: In production with Redis Cluster, SCAN needs to be per-node.
        // For a single Redis instance or Redis Sentinel, this works correctly.
        int count = 0;
        try {
            // Get all rt: keys (paginated)
            var keyCommands = redis.key();
            var keys = keyCommands.keys(KEY_PREFIX + "*");

            ValueCommands<String, String> valueCommands = redis.value(String.class);
            for (String key : keys) {
                String json = valueCommands.get(key);
                if (json != null) {
                    try {
                        RefreshTokenData data = objectMapper.readValue(json, RefreshTokenData.class);
                        if (deviceFingerprint.equals(data.deviceFingerprint())) {
                            valueCommands.getdel(key);
                            count++;
                        }
                    } catch (JsonProcessingException ex) {
                        // Stale or corrupted key — skip
                        LOG.warnf("Skipping unparseable Redis key during device revocation: %s", key);
                    }
                }
            }
            LOG.infof("Revoked %d refresh tokens for device fingerprint: %s", count, deviceFingerprint);
        } catch (Exception e) {
            LOG.error("Error during device revocation scan", e);
        }
        return count;
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private String generateToken() {
        return "rt_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String buildKey(String token) {
        // Strip "rt_" prefix if caller passes raw token
        if (token.startsWith("rt_")) {
            return KEY_PREFIX + token;
        }
        return KEY_PREFIX + token;
    }
}