package io.tradeflow.identity.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tradeflow.identity.dto.AdminProfileResponse;
import io.tradeflow.identity.dto.IdentityDtos.*;
import io.tradeflow.identity.dto.RegisterAdminResponse;
import io.tradeflow.identity.entity.RegisterAdminRequest;
import io.tradeflow.identity.service.AdminService;
import io.tradeflow.identity.service.BuyerService;
import io.tradeflow.identity.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Identity & Merchant", description = "Buyer registration, profile management, merchant lifecycle")
public class IdentityController {

    private final BuyerService buyerService;
    private final MerchantService merchantService;
    private final AdminService adminService;  // ← A
    // ─────────────────────────────────────────────────────────────────────────
    // BUYER ENDPOINTS
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * ENDPOINT 9 — POST /admin/register
     * Create a new admin user
     */
    @PostMapping("/admin/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Register new admin user (admin only)",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public RegisterAdminResponse registerAdmin(
            @Valid @RequestBody RegisterAdminRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        String createdBy = jwt.getSubject();  // Audit: who created this admin
        return adminService.registerAdmin(request, createdBy);
    }

    /**
     * ENDPOINT 10 — GET /admin/{id}/profile
     * Get admin profile
     */
    @GetMapping("/admin/{id}/profile")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Get admin profile",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public AdminProfileResponse getAdminProfile(@PathVariable String id) {
        return adminService.getAdminProfile(id);
    }
    /**
     * ENDPOINT 1 — POST /buyers/register
     * Write path: PostgreSQL + Outbox
     */
    @PostMapping("/buyers/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterBuyerResponse registerBuyer(@Valid @RequestBody RegisterBuyerRequest request) {
        return buyerService.registerBuyer(request);
    }

    /**
     * ENDPOINT 2 — GET /buyers/{id}/profile
     * Read path: Redis → MongoDB (never PostgreSQL)
     */
    @GetMapping("/buyers/{id}/profile")
    @Operation(summary = "Get buyer profile (checkout prefill)", security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("hasAnyRole('BUYER', 'ADMIN') or #id == authentication.name")
    public BuyerProfileResponse getBuyerProfile(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt) {
        return buyerService.getBuyerProfile(id);
    }

    /**
     * ENDPOINT 3 — PUT /buyers/{id}/profile
     * Write path: PostgreSQL → Redis evict → Kafka → MongoDB (async)
     */
    @PutMapping("/buyers/{id}/profile")
    @Operation(summary = "Update buyer profile", security = @SecurityRequirement(name = "bearerAuth"))
    @ResponseStatus(HttpStatus.OK)
    public void updateBuyerProfile(
            @PathVariable String id,
            @Valid @RequestBody UpdateBuyerProfileRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String authenticatedUserId = jwt.getSubject();
        buyerService.updateBuyerProfile(id, request, authenticatedUserId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MERCHANT ENDPOINTS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ENDPOINT 4 — POST /merchants/onboard
     * Starts merchant KYC state machine
     */
    @PostMapping("/merchants/onboard")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN')")

    @Operation(summary = "Begin merchant onboarding / KYC", security = @SecurityRequirement(name = "bearerAuth"))
    public OnboardMerchantResponse onboardMerchant(
            @Valid @RequestBody OnboardMerchantRequest request,
            HttpServletRequest httpRequest) {
        return merchantService.onboardMerchant(request, httpRequest.getRemoteAddr());
    }

    /**
     * ENDPOINT 5 — GET /merchants/{id}/status
     * Public status check: Redis → PostgreSQL
     */
    @GetMapping("/merchants/{id}/status")
    @Operation(summary = "Get merchant status", security = @SecurityRequirement(name = "bearerAuth"))
    public MerchantStatusResponse getMerchantStatus(@PathVariable String id) {
        return merchantService.getMerchantStatus(id);
    }

    /**
     * ENDPOINT 6 — POST /merchants/{id}/kyc/documents
     * Upload KYC docs → S3 → Jumio (Virtual Thread blocks on Jumio call)
     */
    @PostMapping(value = "/merchants/{id}/kyc/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Upload KYC documents", security = @SecurityRequirement(name = "bearerAuth"))
    public KycDocumentUploadResponse uploadKycDocuments(
            @PathVariable String id,
            @RequestParam("documents") List<MultipartFile> documents,
            @RequestParam(value = "document_types", required = false) List<String> documentTypes,
            @AuthenticationPrincipal Jwt jwt) {
        String merchantId = jwt.getClaimAsString("merchant_id");
        return merchantService.uploadKycDocuments(id, documents, documentTypes, merchantId);
    }

    /**
     * ENDPOINT 7 — POST /merchants/{id}/suspend
     * Admin-only: compliance hold. Immediate Redis cache bust.
     */
    @PostMapping("/merchants/{id}/suspend")
    @PreAuthorize("hasAnyRole('ADMIN', 'COMPLIANCE')")
    @Operation(summary = "Suspend merchant (admin)", security = @SecurityRequirement(name = "bearerAuth"))
    public void suspendMerchant(
            @PathVariable String id,
            @Valid @RequestBody SuspendMerchantRequest request) {
        merchantService.suspendMerchant(id, request);
    }

    /**
     * ENDPOINT 8 — POST /merchants/{id}/terminate
     * Admin-only: permanent termination. Triggers full cleanup cascade via Kafka.
     */
    @PostMapping("/merchants/{id}/terminate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Terminate merchant permanently (admin)", security = @SecurityRequirement(name = "bearerAuth"))
    public void terminateMerchant(
            @PathVariable String id,
            @Valid @RequestBody TerminateMerchantRequest request) {
        merchantService.terminateMerchant(id, request);
    }
}
