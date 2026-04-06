package io.tradeflow.auth.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * AuthUser — credential and identity entity.
 *
 * Stores hashed passwords (BCrypt), optional MFA secret (TOTP),
 * roles (comma-separated), and optional Okta SSO subject.
 *
 * This entity is the ONLY place passwords live. Identity Service
 * manages profile data (name, address, etc.) separately.
 */
@Entity
@Table(name = "auth_users")
public class AuthUser extends PanacheEntityBase {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    public String id;

    @Column(name = "email", length = 255, nullable = false, unique = true)
    public String email;

    @Column(name = "hashed_password", length = 255, nullable = false)
    public String hashedPassword;

    @Column(name = "mfa_enabled", nullable = false)
    public boolean mfaEnabled = false;

    @Column(name = "mfa_secret", length = 255)
    public String mfaSecret;

    /**
     * Comma-separated roles. e.g. "BUYER", "MERCHANT", "ADMIN", "BUYER,MERCHANT"
     * Parsed into a List at the service layer.
     */
    @Column(name = "roles", length = 255, nullable = false)
    public String roles = "BUYER";

    /**
     * Set only for users with MERCHANT role.
     * Links to Identity Service's merchant record.
     */
    @Column(name = "merchant_id", length = 36)
    public String merchantId;

    @Column(name = "status", length = 32, nullable = false)
    public String status = "ACTIVE";

    /**
     * Okta OIDC subject claim. Populated on first Okta SSO login.
     * Used to upsert (find or create) the auth_users row for enterprise buyers.
     */
    @Column(name = "okta_subject", length = 255, unique = true)
    public String oktaSubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "last_login")
    public Instant lastLogin;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    // ----------------------------------------------------------------
    // Panache finders
    // ----------------------------------------------------------------

    public static Optional<AuthUser> findByEmail(String email) {
        return find("email", email).firstResultOptional();
    }

    public static Optional<AuthUser> findByOktaSubject(String oktaSubject) {
        return find("oktaSubject", oktaSubject).firstResultOptional();
    }

    public static Optional<AuthUser> findByIdOptional(String id) {
        return find("id", id).firstResultOptional();
    }

    public static boolean existsByEmail(String email) {
        return count("email", email) > 0;
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    public List<String> getRoleList() {
        if (roles == null || roles.isBlank()) return List.of("BUYER");
        return Arrays.asList(roles.split(","));
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
