package io.tradeflow.auth.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AuthDevice — tracks known devices per user.
 *
 * Used for:
 * 1. Device fingerprint validation on refresh token usage
 * 2. Per-device session revocation (stolen phone scenario via DELETE /auth/devices/{id}/revoke)
 *
 * The device_fingerprint is a SHA-256 hash of client-side device characteristics
 * (screen resolution, OS, browser, etc.) computed and submitted by the client.
 */
@Entity
@Table(name = "auth_devices")
public class AuthDevice extends PanacheEntityBase {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    public String id;

    @Column(name = "user_id", length = 36, nullable = false)
    public String userId;

    @Column(name = "device_fingerprint", length = 255, nullable = false)
    public String deviceFingerprint;

    @Column(name = "device_name", length = 255)
    public String deviceName;

    @Column(name = "last_seen", nullable = false)
    public Instant lastSeen = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "revoked", nullable = false)
    public boolean revoked = false;

    @Column(name = "revoked_at")
    public Instant revokedAt;

    @Column(name = "revocation_reason", length = 255)
    public String revocationReason;

    // ----------------------------------------------------------------
    // Panache finders
    // ----------------------------------------------------------------

    public static Optional<AuthDevice> findByUserAndFingerprint(String userId, String fingerprint) {
        return find("userId = ?1 AND deviceFingerprint = ?2", userId, fingerprint)
                .firstResultOptional();
    }

    /**
     * Find all active (non-revoked) devices for a user.
     */
    public static List<AuthDevice> findActiveByUser(String userId) {
        return list("userId = ?1 AND revoked = false", userId);
    }

    /**
     * Find all active devices matching a specific fingerprint.
     * Used during device revocation to batch-revoke all sessions for a stolen device.
     */
    public static List<AuthDevice> findActiveByFingerprint(String fingerprint) {
        return list("deviceFingerprint = ?1 AND revoked = false", fingerprint);
    }

    /**
     * Used by device revocation endpoint.
     */
    public static long revokeAllForFingerprint(String fingerprint, String reason) {
        return update("revoked = true, revokedAt = ?1, revocationReason = ?2 " +
                        "WHERE deviceFingerprint = ?3 AND revoked = false",
                Instant.now(), reason, fingerprint);
    }
}
