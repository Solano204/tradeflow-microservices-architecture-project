package io.tradeflow.identity.service;

import at.favre.lib.crypto.bcrypt.BCrypt;
import io.tradeflow.identity.dto.AdminProfileResponse;
import io.tradeflow.identity.dto.IdentityDtos.*;
import io.tradeflow.identity.dto.RegisterAdminResponse;
import io.tradeflow.identity.entity.Admin;
import io.tradeflow.identity.entity.IdentityOutbox;
import io.tradeflow.identity.entity.RegisterAdminRequest;
import io.tradeflow.identity.exceptions.ConflictException;
import io.tradeflow.identity.exceptions.NotFoundException;
import io.tradeflow.identity.repository.AdminRepository;
import io.tradeflow.identity.repository.IdentityOutboxRepository;
import io.tradeflow.identity.kafka.AuthEventPublisher;  // ← Importar si quieres usar el publisher dedicado
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {

    private final AdminRepository adminRepository;
    private final IdentityOutboxRepository outboxRepository;
    private final AuthEventPublisher authEventPublisher;  // ← Opcional, pero recomendado

    @Transactional
    public RegisterAdminResponse registerAdmin(RegisterAdminRequest request, String createdBy) {
        // Check if email already exists
        if (adminRepository.existsByEmail(request.email())) {
            throw new ConflictException("Admin already exists with email: " + request.email());
        }

        String adminId = "adm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // Hash password
        String hashedPassword = BCrypt.withDefaults()
                .hashToString(12, request.password().toCharArray());

        // Create admin profile in Identity Service
        Admin admin = Admin.builder()
                .id(adminId)
                .email(request.email())
                .fullName(request.fullName())
                .phone(request.phone())
                .department(request.department() != null ? request.department() : "GENERAL")
                .status("ACTIVE")
                .createdBy(createdBy)
                .build();

        adminRepository.save(admin);

        // ============================================================
        // OPCIÓN 1: Usar el mismo formato que BuyerService (recomendado)
        // ============================================================
        Map<String, Object> payload = Map.of(
                "event_type",      "admin.registered",
                "admin_id",        adminId,
                "email",           request.email(),
                "full_name",       request.fullName(),
                "phone",           request.phone() != null ? request.phone() : "",
                "department",      admin.getDepartment(),
                "hashed_password", hashedPassword,
                "roles",           List.of("ADMIN"),
                "created_by",      createdBy,
                "timestamp",       Instant.now().toString()
        );

        IdentityOutbox outbox = IdentityOutbox.builder()
                .eventType("admin.registered")
                .payload(payload)
                .aggregateId(adminId)
                .build();

        outboxRepository.save(outbox);

        // ============================================================
        // OPCIÓN 2: Usar AuthEventPublisher (si prefieres)
        // ============================================================
        // authEventPublisher.publishUserCreationRequest(
        //     adminId,
        //     request.email(),
        //     hashedPassword,
        //     List.of("ADMIN")
        // );

        log.info("Admin registered with outbox event: adminId={}, email={}, createdBy={}",
                adminId, request.email(), createdBy);

        return new RegisterAdminResponse(
                adminId,
                request.email(),
                request.fullName(),
                "ACTIVE",
                admin.getCreatedAt()
        );
    }

    /**
     * Get admin profile (for display in admin panel)
     */
    public AdminProfileResponse getAdminProfile(String adminId) {
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new NotFoundException("Admin not found: " + adminId));

        return new AdminProfileResponse(
                admin.getId(),
                admin.getEmail(),
                admin.getFullName(),
                admin.getPhone(),
                admin.getDepartment(),
                admin.getStatus(),
                admin.getCreatedAt(),
                admin.getLastLogin()
        );
    }

    /**
     * Optional: Method to update admin status and publish event
     */
    @Transactional
    public void updateAdminStatus(String adminId, String status, String updatedBy) {
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new NotFoundException("Admin not found: " + adminId));

        admin.setStatus(status);
        adminRepository.save(admin);

        // Publish status change event
        Map<String, Object> payload = Map.of(
                "event_type",      "admin.status.changed",
                "admin_id",        adminId,
                "email",           admin.getEmail(),
                "status",          status,
                "updated_by",      updatedBy,
                "timestamp",       Instant.now().toString()
        );

        IdentityOutbox outbox = IdentityOutbox.builder()
                .eventType("admin.status.changed")
                .payload(payload)
                .aggregateId(adminId)
                .build();

        outboxRepository.save(outbox);

        log.info("Admin status updated: adminId={}, status={}", adminId, status);
    }
}