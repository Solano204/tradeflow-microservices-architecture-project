
package io.tradeflow.identity.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/**
 * All request/response DTOs for the Identity & Merchant Service.
 * Grouped as nested classes for clarity.
 */
public final class IdentityDtos {

    private IdentityDtos() {}

    // ─────────────────────────────────────────────────────────────────────────
    // BUYER DTOs
    // ─────────────────────────────────────────────────────────────────────────

    public record AddressDto(
            @NotBlank String street,
            @NotBlank String city,
            @NotBlank String state,
            @NotBlank String postalCode,
            @NotBlank @Size(min = 2, max = 2) String country
    ) {}

    public record RegisterBuyerRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 2, max = 120) String fullName,
            @Pattern(regexp = "^\\+?[0-9\\s\\-]{7,20}$") String phone,
            @Valid @NotNull AddressDto address,
            @NotBlank String locale,
            String deviceFingerprint,

            // ADD THIS
            @NotBlank @Size(min = 6, max = 255) String password
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RegisterBuyerResponse(
            String buyerId,
            String status,
            Instant createdAt
    ) {}

    public record BuyerProfileResponse(
            String buyerId,
            String name,
            String email,
            AddressDto defaultAddress,
            List<SavedPaymentMethodDto> savedPaymentMethods,
            String locale
    ) {}

    public record UpdateBuyerProfileRequest(
            @Valid AddressDto address,
            List<@Valid SavedPaymentMethodDto> savedPaymentMethods
    ) {}

    public record SavedPaymentMethodDto(
            @NotBlank String type,
            String last4,
            String brand,
            String token,
            String stripePaymentMethodId
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // MERCHANT DTOs
    // ─────────────────────────────────────────────────────────────────────────

    public record BankAccountDto(
            @NotBlank String clabe,
            @NotBlank String bank
    ) {}

    public record OnboardMerchantRequest(
            @NotBlank @Size(min = 2, max = 200) String businessName,
            @NotBlank String businessType,
            @NotBlank String taxId,
            @NotBlank @Email String contactEmail,
            @Pattern(regexp = "^\\+?[0-9\\s\\-]{7,20}$") String contactPhone,
            @NotBlank @Size(min = 6, max = 255) String password,  // ← NUEVO CAMPO
            @Valid @NotNull BankAccountDto bankAccount,
            @Valid @NotNull AddressDto businessAddress,
            boolean termsAccepted
    ) {}

    public record OnboardMerchantResponse(
            String merchantId,
            String status,
            String kycDocumentUploadUrl,
            String nextStep,
            Instant createdAt
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MerchantStatusResponse(
            String merchantId,
            String status,
            String businessName,
            Instant kycVerifiedAt,
            Instant activeSince,
            Instant suspendedAt,
            String suspensionReason
    ) {}

    public record SuspendMerchantRequest(
            @NotBlank String reason,
            String notes,
            @NotBlank String initiatedBy
    ) {}

    public record TerminateMerchantRequest(
            @NotBlank String reason,
            @NotBlank String initiatedBy
    ) {}

    // Internal fast-path — minimal payload, no auth overhead
    public record InternalMerchantStatusResponse(
            String status
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // KYC DTOs
    // ─────────────────────────────────────────────────────────────────────────

    public record KycDocumentUploadResponse(
            String merchantId,
            String status,
            String verificationId,
            String message
    ) {}

    public record JumioWebhookPayload(
            String verificationId,
            String merchantId,
            String status,              // APPROVED / REJECTED / ERROR
            String rejectReason,
            Instant completedAt
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // SHARED / ERROR
    // ─────────────────────────────────────────────────────────────────────────

    public record ErrorResponse(
            String error,
            String message,
            int status,
            Instant timestamp
    ) {
        public static ErrorResponse of(String error, String message, int status) {
            return new ErrorResponse(error, message, status, Instant.now());
        }
    }
}
