package io.tradeflow.identity.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.tradeflow.identity.dto.IdentityDtos.*;
import io.tradeflow.identity.service.JumioService;
import io.tradeflow.identity.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Internal endpoints — NOT exposed through the API Gateway.
 * Protected exclusively by Kubernetes NetworkPolicy:
 *   - Endpoint 9: only "order-service" pods can reach port 8081
 *   - KYC webhook: only accessible from within the cluster
 *
 * No JWT validation — service-to-service trust via network policy.
 *
 * These routes are served on a separate port (8081) configured in application.properties.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
@Slf4j
@Hidden  // Hidden from Swagger UI — internal only
public class InternalController {

    private final MerchantService merchantService;
    private final JumioService jumioService;

    /**
     * ENDPOINT 9 — GET /internal/merchants/{id}/status
     *
     * Called by Order Service on every order (1000s/sec at peak).
     * Redis only, sub-millisecond on cache hit.
     * No JWT validation — K8s NetworkPolicy is the security boundary.
     */
    @GetMapping("/merchants/{id}/status")
    public InternalMerchantStatusResponse getMerchantStatus(@PathVariable String id) {
        return merchantService.getInternalMerchantStatus(id);
    }

    /**
     * POST /internal/kyc/webhook
     *
     * Jumio calls this endpoint with KYC verification results.
     * Validates HMAC-SHA256 signature before processing.
     * Transitions merchant ACTIVE or TERMINATED.
     */
    @PostMapping("/kyc/webhook")
    @ResponseStatus(HttpStatus.OK)
    public void kycWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Jumio-Signature", required = false) String signature,
            HttpServletRequest request) throws IOException {

        // Validate Jumio webhook signature to prevent spoofed KYC approvals
        if (signature == null || !jumioService.validateWebhookSignature(rawBody, signature)) {
            log.warn("Invalid Jumio webhook signature from IP: {}", request.getRemoteAddr());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }

        // Parse payload — Jumio sends JSON
        // In production: use Jackson ObjectMapper properly; this is representative
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            JumioWebhookPayload payload = mapper.readValue(rawBody, JumioWebhookPayload.class);
            merchantService.processKycWebhook(payload);
            log.info("KYC webhook processed: merchantId={}, status={}", payload.merchantId(), payload.status());
        } catch (Exception e) {
            log.error("KYC webhook processing failed: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Webhook processing failed");
        }
    }

    /**
     * Health check for internal port.
     */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
