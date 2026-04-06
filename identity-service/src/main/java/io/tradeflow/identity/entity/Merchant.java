package io.tradeflow.identity.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "merchants",
        indexes = {
                @Index(name = "idx_merchants_email", columnList = "contact_email"),
                @Index(name = "idx_merchants_status", columnList = "status"),
                @Index(name = "idx_merchants_tax_id", columnList = "tax_id", unique = true)
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Merchant {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "business_name", nullable = false, length = 200)
    private String businessName;

    @Column(name = "business_type", nullable = false, length = 50)
    private String businessType;

    @Column(name = "tax_id", nullable = false, unique = true, length = 50)
    private String taxId;

    @Column(name = "contact_email", nullable = false, length = 255)
    private String contactEmail;

    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    // Encrypted at application level before storage (PCI-DSS)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bank_account_encrypted", columnDefinition = "jsonb")
    private Map<String, Object> bankAccountEncrypted;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "business_address", columnDefinition = "jsonb")
    private Map<String, Object> businessAddress;

    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "PENDING_KYC";

    @Column(name = "kyc_status", length = 30)
    @Builder.Default
    private String kycStatus = "NOT_STARTED";

    @Column(name = "jumio_verification_id", length = 100)
    private String jumioVerificationId;

    @Column(name = "kyc_verified_at")
    private Instant kycVerifiedAt;

    @Column(name = "active_since")
    private Instant activeSince;

    @Column(name = "suspension_reason", length = 500)
    private String suspensionReason;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspended_by", length = 36)
    private String suspendedBy;

    @Column(name = "terminated_at")
    private Instant terminatedAt;

    @Column(name = "terminated_by", length = 36)
    private String terminatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // ── State machine helpers ──────────────────────────────────────────────

    public boolean canUploadKycDocuments() {
        return "PENDING_KYC".equals(status);
    }

    public boolean canBeSuspended() {
        return "ACTIVE".equals(status);
    }

    public boolean canBeTerminated() {
        return !"TERMINATED".equals(status);
    }
}
