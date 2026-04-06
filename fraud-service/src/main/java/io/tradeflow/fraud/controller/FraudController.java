package io.tradeflow.fraud.controller;

import io.micrometer.tracing.Tracer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tradeflow.fraud.dto.FraudDtos.*;
import io.tradeflow.fraud.entity.FraudRule;
import io.tradeflow.fraud.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * FraudController — REST endpoints for fraud detection.
 *
 * TRACING NOTES:
 * - Spring MVC auto-creates an HTTP span for every inbound request.
 *   No configuration needed — ObservationFilter handles it.
 * - We inject Tracer to add BUSINESS TAGS to the HTTP span so you can
 *   search in Zipkin by fraud.order_id, fraud.buyer_id, fraud.decision, etc.
 * - All downstream calls (MongoDB, PostgreSQL, Redis, Kafka, Spring AI)
 *   are auto-traced as child spans of the @Observed method spans.
 * - Every log line includes [traceId=XYZ] via MDC (log pattern in application.properties).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Fraud Detection", description = "ML + rule engine, vector search, feedback loop")
public class FraudController {

    private final FraudScoringService scoringService;
    private final RuleManagementService ruleService;
    private final Tracer tracer; // ✅ for tagging the auto-created HTTP span

    // ─────────────────────────────────────────────────────────────────────────
    // POST /fraud/score
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/fraud/score")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
    @Operation(summary = "Score transaction — full ML + rule engine pipeline",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ScoreResponse score(@Valid @RequestBody ScoreRequest request) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("fraud.http.operation", "POST /fraud/score");
            span.tag("fraud.order_id",       request.orderId());
            span.tag("fraud.buyer_id",       request.buyerId());
            span.tag("fraud.merchant_id",    request.merchantId());
            span.tag("fraud.amount",         request.amount().toString());
        }
        return scoringService.score(request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /fraud/scores/{orderId}
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/fraud/scores/{orderId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'COMPLIANCE', 'SUPPORT')")
    @Operation(summary = "Get fraud score with full explanation",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ScoreDetailResponse getScore(@PathVariable String orderId) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("fraud.http.operation", "GET /fraud/scores/{orderId}");
            span.tag("fraud.order_id",       orderId);
        }
        return scoringService.getScore(orderId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /fraud/buyers/{buyerId}/profile
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/fraud/buyers/{buyerId}/profile")
    @PreAuthorize("hasAnyRole('ADMIN', 'COMPLIANCE', 'SUPPORT')")
    @Operation(summary = "Get buyer fraud risk profile",
            security = @SecurityRequirement(name = "bearerAuth"))
    public BuyerFraudProfileResponse getBuyerProfile(@PathVariable String buyerId) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("fraud.http.operation", "GET /fraud/buyers/{buyerId}/profile");
            span.tag("fraud.buyer_id",       buyerId);
        }
        return scoringService.getBuyerProfile(buyerId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /fraud/rules
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/fraud/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'COMPLIANCE')")
    @Operation(summary = "Create fraud rule — no redeployment needed",
            security = @SecurityRequirement(name = "bearerAuth"))
    public FraudRule createRule(@Valid @RequestBody CreateRuleRequest request) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("fraud.http.operation", "POST /fraud/rules");
            span.tag("fraud.rule.name",      request.ruleName());
            span.tag("fraud.rule.type",      request.ruleType());
            span.tag("fraud.rule.priority",  String.valueOf(request.priority()));
            span.tag("fraud.rule.created_by", request.createdBy() != null ? request.createdBy() : "unknown");
        }
        return ruleService.createRule(request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUT /fraud/rules/{ruleId}
    // ─────────────────────────────────────────────────────────────────────────

    @PutMapping("/fraud/rules/{ruleId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'COMPLIANCE')")
    @Operation(summary = "Update fraud rule",
            security = @SecurityRequirement(name = "bearerAuth"))
    public FraudRule updateRule(
            @PathVariable String ruleId,
            @Valid @RequestBody UpdateRuleRequest request) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("fraud.http.operation", "PUT /fraud/rules/{ruleId}");
            span.tag("fraud.rule.id",        ruleId);
            span.tag("fraud.rule.updated_by", request.updatedBy() != null ? request.updatedBy() : "unknown");
        }
        return ruleService.updateRule(ruleId, request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /fraud/rules
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/fraud/rules")
    @PreAuthorize("hasAnyRole('ADMIN', 'COMPLIANCE', 'SUPPORT')")
    @Operation(summary = "List all active fraud rules with effectiveness metrics",
            security = @SecurityRequirement(name = "bearerAuth"))
    public RuleListResponse listRules() {
        var span = tracer.currentSpan();
        if (span != null) span.tag("fraud.http.operation", "GET /fraud/rules");

        List<FraudRule> rules = ruleService.listActiveRules();
        List<RuleSummaryDto> summaries = rules.stream()
                .map(r -> new RuleSummaryDto(
                        r.getId(), r.getRuleName(), r.getRuleType(), r.getPriority(),
                        r.isActive(), r.getDescription(),
                        r.getTriggerCount(), r.getTriggerCount7d(),
                        r.getFalsePositiveRate(), r.getCreatedAt(), r.getExpiresAt()))
                .toList();

        if (span != null) span.tag("fraud.rules.count", String.valueOf(rules.size()));

        long active = rules.stream().filter(FraudRule::isActive).count();
        return new RuleListResponse(summaries, rules.size(), (int) active);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE /fraud/rules/{ruleId}
    // ─────────────────────────────────────────────────────────────────────────

    @DeleteMapping("/fraud/rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'COMPLIANCE')")
    @Operation(summary = "Deactivate rule (soft delete — audit trail preserved)",
            security = @SecurityRequirement(name = "bearerAuth"))
    public void deactivateRule(
            @PathVariable String ruleId,
            @RequestBody(required = false) DeactivateRuleRequest request) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("fraud.http.operation", "DELETE /fraud/rules/{ruleId}");
            span.tag("fraud.rule.id",        ruleId);
            if (request != null) span.tag("fraud.rule.deactivated_by",
                    request.deactivatedBy() != null ? request.deactivatedBy() : "unknown");
        }
        ruleService.deactivateRule(ruleId, request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /fraud/feedback
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/fraud/feedback")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('SERVICE', 'ADMIN', 'COMPLIANCE')")
    @Operation(summary = "Ingest confirmed fraud label — model feedback loop",
            security = @SecurityRequirement(name = "bearerAuth"))
    public void submitFeedback(@Valid @RequestBody FeedbackRequest request) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("fraud.http.operation",  "POST /fraud/feedback");
            span.tag("fraud.order_id",        request.orderId());
            span.tag("fraud.confirmed_type",  request.confirmedFraudType());
        }
        scoringService.processFeedback(request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /internal/fraud/buyers/{buyerId}/baseline
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/internal/fraud/buyers/{buyerId}/baseline")
    @Operation(summary = "Internal baseline — Order Service Circuit Breaker fallback")
    public InternalBuyerBaselineResponse getInternalBaseline(@PathVariable String buyerId) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("fraud.http.operation", "GET /internal/fraud/buyers/{buyerId}/baseline");
            span.tag("fraud.buyer_id",       buyerId);
        }
        return scoringService.getInternalBaseline(buyerId);
    }
}