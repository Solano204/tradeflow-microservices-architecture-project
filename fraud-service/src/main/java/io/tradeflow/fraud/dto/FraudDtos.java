package io.tradeflow.fraud.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class FraudDtos {

    private FraudDtos() {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 — POST /fraud/score
    // ─────────────────────────────────────────────────────────────────────────

    public record ScoreRequest(
            @NotBlank String orderId,
            @NotBlank String buyerId,
            @NotBlank String merchantId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotBlank String currency,
            List<String> productIds,
            List<String> productCategories,
            String deviceFingerprint,
            String ipAddress,
            String userAgent,
            String paymentMethodToken
    ) {}

    public record ScoreComponentsDto(
            double mlSimilarity,
            double amountDeviation,
            double velocity,
            double deviceIpRisk
    ) {}

    public record RuleEvaluationDto(
            String rule,
            boolean triggered,
            String action,
            String detail
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScoreResponse(
            String orderId,
            double score,
            String decision,
            double confidence,
            ScoreComponentsDto scoreComponents,
            List<RuleEvaluationDto> rulesEvaluated,
            int nearestFraudNeighbors,
            String modelVersion,
            Instant scoredAt,
            long processingTimeMs
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — GET /fraud/scores/{orderId}
    // ─────────────────────────────────────────────────────────────────────────

    public record FraudNeighborDto(
            String referenceOrderId,
            double similarity,
            String fraudType,
            BigDecimal amount,
            Instant occurredAt
    ) {}

    public record TriggeredRuleDto(
            String ruleId,
            String ruleName,
            String action,
            String detail
    ) {}

    public record BuyerContextDto(
            int transactionCount,
            BigDecimal avgOrderValue90d,
            String deviceFingerprint,
            String ipCountry,
            boolean ipIsVpn
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScoreDetailResponse(
            String orderId,
            double score,
            String decision,
            double confidence,
            String modelVersion,
            Instant scoredAt,
            ScoreComponentsDto scoreBreakdown,
            List<FraudNeighborDto> nearestFraudNeighbors,
            List<TriggeredRuleDto> rulesTriggered,
            BuyerContextDto buyerContextAtScoring
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — GET /fraud/buyers/{buyerId}/profile
    // ─────────────────────────────────────────────────────────────────────────

    public record BehavioralBaselineDto(
            BigDecimal avgOrderValue90d,
            BigDecimal maxOrderValue90d,
            int purchaseVelocity7d,
            List<Integer> usualOrderHours,
            List<String> usualIpCountries,
            List<String> usualCategories
    ) {}

    public record DeviceHistoryDto(
            String fingerprint,
            Instant firstSeen,
            Instant lastSeen,
            boolean trusted
    ) {}

    public record RecentScoreDto(
            String orderId,
            double score,
            String decision,
            Instant scoredAt
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BuyerFraudProfileResponse(
            String buyerId,
            String riskTier,
            int transactionCount,
            Instant firstTransaction,
            BehavioralBaselineDto behavioralBaseline,
            List<DeviceHistoryDto> deviceHistory,
            List<RecentScoreDto> recentScores,
            List<String> flags,
            int coldStartRemainingTransactions
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINTS 4, 5 — POST/PUT /fraud/rules
    // ─────────────────────────────────────────────────────────────────────────

    public record RuleConditionDto(
            @NotBlank String field,
            @NotBlank String operator,
            Object value,
            List<Object> valueList,
            String redisKey,
            String pattern,
            List<String> exceptions
    ) {}

    public record CreateRuleRequest(
            @NotBlank String ruleName,
            @NotBlank String ruleType,
            String description,
            @NotNull RuleConditionDto condition,
            @NotNull Integer priority,
            Instant expiresAt,
            @NotBlank String createdBy,
            String justification
    ) {}

    public record UpdateRuleRequest(
            RuleConditionDto condition,
            Integer priority,
            String description,
            String updatedBy,
            String changeReason,
            Instant expiresAt
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 6 — GET /fraud/rules
    // ─────────────────────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleSummaryDto(
            String ruleId,
            String ruleName,
            String ruleType,
            int priority,
            boolean active,
            String description,
            long triggerCountTotal,
            long triggerCount7d,
            double falsePositiveRate,
            Instant createdAt,
            Instant expiresAt
    ) {}

    public record RuleListResponse(
            List<RuleSummaryDto> rules,
            int totalRules,
            int activeRules
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 7 — DELETE /fraud/rules/{ruleId}
    // ─────────────────────────────────────────────────────────────────────────

    public record DeactivateRuleRequest(
            String deactivatedBy,
            String reason
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 8 — POST /fraud/feedback
    // ─────────────────────────────────────────────────────────────────────────

    public record FeedbackRequest(
            @NotBlank String orderId,
            @NotBlank String confirmedFraudType,
            String confirmedBy,
            String source,
            Double originalScore,
            String originalDecision,
            String notes
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 9 — GET /internal/fraud/buyers/{buyerId}/baseline
    // ─────────────────────────────────────────────────────────────────────────

    public record InternalBuyerBaselineResponse(
            String buyerId,
            String riskTier,
            int transactionCount,
            BigDecimal avgOrderValue90d,
            BigDecimal maxSafeAmount,
            boolean coldStartActive,
            int coldStartTransactionsRemaining,
            Double lastScore,
            Instant lastScoreAt,
            Instant cachedAt
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // SHARED
    // ─────────────────────────────────────────────────────────────────────────

    public record ErrorResponse(String error, String message, int status, Instant timestamp) {
        public static ErrorResponse of(String error, String message, int status) {
            return new ErrorResponse(error, message, status, Instant.now());
        }
    }
}
