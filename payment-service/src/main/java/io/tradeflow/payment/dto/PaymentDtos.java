package io.tradeflow.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PaymentDtos {

    private PaymentDtos() {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 — POST /payments/charge
    // ─────────────────────────────────────────────────────────────────────────

    public record ChargeRequest(
            @NotBlank String orderId,
            @NotBlank String buyerId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotBlank String currency,
            @NotBlank String paymentMethodToken,
            @NotBlank String merchantId,
            @NotBlank String idempotencyKey
    ) {}

    public record ChargeResponse(
            String paymentId,
            String orderId,
            String status,
            BigDecimal amount,
            String currency,
            String stripeChargeId,
            Instant processedAt,
            String idempotencyKey
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — POST /payments/refund
    // ─────────────────────────────────────────────────────────────────────────

    public record RefundRequest(
            @NotBlank String orderId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotBlank String refundType,  // FULL | PARTIAL
            @NotBlank String reason,
            String notes,
            String initiatedBy,
            @NotBlank String idempotencyKey
    ) {}

    public record RefundResponse(
            String refundId,
            String orderId,
            String stripeRefundId,
            BigDecimal amountRefunded,
            String status,
            String estimatedArrival,
            Instant processedAt
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — POST /payments/webhooks/stripe
    // ─────────────────────────────────────────────────────────────────────────

    // Raw Stripe event — deserialized from Stripe's JSON envelope
    public record StripeWebhookEvent(
            String id,
            String type,
            Long created,
            Boolean livemode,
            Map<String, Object> data
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 4 — GET /payments/{orderId}
    // ─────────────────────────────────────────────────────────────────────────

    public record PaymentMethodDto(
            String type,
            String brand,
            String last4,
            Integer expMonth,
            Integer expYear
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PaymentStatusResponse(
            String paymentId,
            String orderId,
            String status,
            BigDecimal amount,
            String currency,
            String stripeChargeId,
            PaymentMethodDto paymentMethod,
            Instant processedAt,
            List<RefundSummaryDto> refunds,
            DisputeSummaryDto dispute
    ) {}

    public record RefundSummaryDto(
            String refundId,
            BigDecimal amount,
            String status,
            Instant processedAt
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DisputeSummaryDto(
            String disputeId,
            BigDecimal amount,
            String reason,
            String status,
            Instant evidenceDueDate
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 5 — GET /payments/{orderId}/receipt
    // ─────────────────────────────────────────────────────────────────────────

    public record ReceiptItemDto(
            String description,
            int qty,
            BigDecimal unitPrice,
            BigDecimal subtotal,
            BigDecimal taxRate,
            BigDecimal taxAmount,
            BigDecimal totalWithTax
    ) {}

    public record ReceiptPartyDto(
            String name,
            String email,
            String taxId,
            Map<String, String> address
    ) {}

    public record ReceiptPaymentDto(
            String method,
            String stripeChargeId,
            BigDecimal amountCharged,
            String currency
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReceiptResponse(
            String receiptId,
            String orderId,
            Instant issuedAt,
            ReceiptPartyDto buyer,
            ReceiptPartyDto merchant,
            List<ReceiptItemDto> items,
            ReceiptPaymentDto payment,
            String pdfUrl
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 7 — GET /internal/payments/{orderId}/status
    // ─────────────────────────────────────────────────────────────────────────

    public record InternalPaymentStatusResponse(
            String orderId,
            boolean paymentFound,
            String status,
            String stripeChargeId,
            BigDecimal amount,
            Instant processedAt
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 8 — POST /payments/disputes/{disputeId}/respond
    // ─────────────────────────────────────────────────────────────────────────

    public record DisputeEvidenceRequest(
            @NotBlank String disputeId,
            @NotNull Map<String, Object> evidence,
            String additionalStatement
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ERRORS
    // ─────────────────────────────────────────────────────────────────────────

    public record ErrorResponse(String error, String message, int status, Instant timestamp) {
        public static ErrorResponse of(String error, String message, int status) {
            return new ErrorResponse(error, message, status, Instant.now());
        }
    }
}
