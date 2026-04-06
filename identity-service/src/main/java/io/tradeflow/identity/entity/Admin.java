package io.tradeflow.identity.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "admins",
        indexes = {
                @Index(name = "idx_admins_email", columnList = "email", unique = true)
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Admin {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "department", length = 50)
    private String department;  // COMPLIANCE, OPERATIONS, SUPPORT, etc.

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_login")
    private Instant lastLogin;

    @Column(name = "created_by", length = 36)  // Which admin created this admin
    private String createdBy;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}