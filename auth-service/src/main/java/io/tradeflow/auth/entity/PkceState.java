package io.tradeflow.auth.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Optional;

/**
 * PkceState — temporary PKCE state storage for Okta OIDC flow.
 *
 * Flow:
 * 1. User clicks "Login with Okta"
 * 2. Auth Service generates: state (CSRF token) + code_verifier (PKCE)
 * 3. Stores both here with 10-minute TTL
 * 4. Redirects user to Okta with state + code_challenge (SHA256 of code_verifier)
 * 5. On callback: retrieve and validate state → use code_verifier to exchange code
 * 6. Delete row after use
 *
 * TTL enforcement: expires_at column + cleanup @Scheduled job.
 */
@Entity
@Table(name = "pkce_state")
public class PkceState extends PanacheEntityBase {

    @Id
    @Column(name = "state", length = 255, nullable = false)
    public String state;

    @Column(name = "code_verifier", length = 255, nullable = false)
    public String codeVerifier;

    @Column(name = "nonce", length = 255)
    public String nonce;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt = Instant.now().plusSeconds(600); // 10 minutes

    // ----------------------------------------------------------------
    // Panache finders
    // ----------------------------------------------------------------

    public static Optional<PkceState> findByStateOptional(String state) {
        return find("state", state).firstResultOptional();
    }

    public static long deleteExpired() {
        return delete("expiresAt < ?1", Instant.now());
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
