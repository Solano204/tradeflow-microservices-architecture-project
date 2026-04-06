package io.tradeflow.identity.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "kyc_documents",
        indexes = {
                @Index(name = "idx_kyc_merchant_id", columnList = "merchant_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KycDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "merchant_id", nullable = false, length = 36)
    private String merchantId;

    @Column(name = "document_type", nullable = false, length = 50)
    private String documentType;

    @Column(name = "s3_key", nullable = false, length = 500)
    private String s3Key;

    @Column(name = "s3_url", nullable = false, length = 1000)
    private String s3Url;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @PrePersist
    protected void onCreate() {
        uploadedAt = Instant.now();
    }
}
