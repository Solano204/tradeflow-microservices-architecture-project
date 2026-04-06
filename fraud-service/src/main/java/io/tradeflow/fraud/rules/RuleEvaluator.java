package io.tradeflow.fraud.rules;

import io.tradeflow.fraud.entity.FraudRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map; /**
 * Rule evaluation engine.
 *
 * Evaluates each active rule against the scoring context in microseconds.
 * Supports 6 operators covering all documented rule patterns:
 *   EQUALS, GREATER_THAN, IN, CONTAINS, IP_IN_REDIS_SET, REGEX_MATCH
 *
 * BLOCK rules (priority ASC) are evaluated first — short-circuit if triggered.
 * ALLOW rules evaluated after — ALLOW_FLAG set for ML override.
 * REVIEW rules floor the final score at 0.65.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RuleEvaluator {

    private final StringRedisTemplate redis;

    public record RuleResult(
            FraudRule rule,
            boolean triggered,
            String detail
    ) {}

    public record EvaluationResult(
            boolean hardBlock,
            boolean allowFlag,
            boolean forceReview,
            List<RuleResult> results
    ) {}

    public EvaluationResult evaluate(List<FraudRule> rules, ScoringContext ctx) {
        boolean hardBlock  = false;
        boolean allowFlag  = false;
        boolean forceReview = false;
        List<RuleResult> results = new ArrayList<>();

        for (FraudRule rule : rules) {
            try {
                boolean triggered = evaluateCondition(rule, ctx);

                String detail = null;
                if (triggered) {
                    detail = buildDetail(rule, ctx);
                    log.debug("Rule triggered: {} → {} for orderId={}", rule.getRuleName(), rule.getRuleType(), ctx.orderId());

                    switch (rule.getRuleType()) {
                        case "BLOCK"   -> hardBlock  = true;
                        case "ALLOW"   -> allowFlag  = true;
                        case "REVIEW"  -> forceReview = true;
                    }
                }

                results.add(new RuleResult(rule, triggered, detail));

                // Short-circuit on first BLOCK rule — no need to evaluate further
                if (hardBlock) break;

            } catch (Exception e) {
                log.warn("Rule evaluation error: rule={}, error={}", rule.getRuleName(), e.getMessage());
            }
        }

        return new EvaluationResult(hardBlock, allowFlag, forceReview, results);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean evaluateCondition(FraudRule rule, ScoringContext ctx) {
        Map<String, Object> cond = rule.getCondition();
        if (cond == null) return false;

        String field    = (String) cond.get("field");
        String operator = (String) cond.get("operator");
        Object value    = cond.get("value");
        List   valueList = (List) cond.get("valueList");
        List<String> exceptions = (List<String>) cond.get("exceptions");

        Object fieldValue = getField(ctx, field);
        if (fieldValue == null) return false;

        boolean result = switch (operator) {
            case "EQUALS"       -> fieldValue.equals(value);
            case "NOT_EQUALS"   -> !fieldValue.equals(value);
            case "GREATER_THAN" -> toDouble(fieldValue) > toDouble(value);
            case "LESS_THAN"    -> toDouble(fieldValue) < toDouble(value);
            case "IN"           -> valueList != null && valueList.contains(fieldValue);
            case "NOT_IN"       -> valueList == null || !valueList.contains(fieldValue);
            case "CONTAINS"     -> fieldValue.toString().contains(value.toString());
            case "IP_IN_REDIS_SET" -> {
                String key = (String) cond.get("redisKey");
                yield key != null && Boolean.TRUE.equals(
                        redis.opsForSet().isMember(key, fieldValue.toString()));
            }
            case "REGEX_MATCH"  -> {
                String pattern = (String) cond.get("pattern");
                yield pattern != null && fieldValue.toString().matches(pattern);
            }
            default -> {
                log.warn("Unknown rule operator: {}", operator);
                yield false;
            }
        };

        // Apply exceptions — e.g. MX IPs excluded from OFAC block
        if (result && exceptions != null && !exceptions.isEmpty()) {
            for (String exception : exceptions) {
                String[] parts = exception.split(":");
                if (parts.length == 2) {
                    Object exceptionFieldValue = getField(ctx, parts[0]);
                    if (parts[1].equals(exceptionFieldValue)) {
                        log.debug("Rule exception matched: {} for rule {}", exception, rule.getRuleName());
                        return false;
                    }
                }
            }
        }

        return result;
    }

    /** Extract field value from scoring context by field name */
    private Object getField(ScoringContext ctx, String field) {
        return switch (field) {
            case "amount"             -> ctx.amount();
            case "buyer_id"           -> ctx.buyerId();
            case "merchant_id"        -> ctx.merchantId();
            case "device_fingerprint" -> ctx.deviceFingerprint();
            case "ip_address"         -> ctx.ipAddress();
            case "ip_country"         -> ctx.ipCountry();
            case "ip_is_vpn"          -> ctx.ipIsVpn();
            case "ip_is_tor"          -> ctx.ipIsTor();
            case "ip_on_ofac_list"    -> ctx.ipOnOfacList();
            case "transaction_count"  -> ctx.transactionCount();
            case "purchase_velocity_7d" -> ctx.purchaseVelocity7d();
            case "is_new_buyer"       -> ctx.isNewBuyer();
            case "amount_deviation_factor" -> ctx.amountDeviationFactor();
            case "risk_tier"          -> ctx.riskTier();
            case "product_categories" -> ctx.productCategories();
            default -> {
                log.debug("Unknown rule field: {}", field);
                yield null;
            }
        };
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0.0; }
    }

    private String buildDetail(FraudRule rule, ScoringContext ctx) {
        Map<String, Object> cond = rule.getCondition();
        String field = (String) cond.getOrDefault("field", "");
        Object value = cond.get("value");
        return String.format("%s: field=%s value=%s context=%s",
                rule.getRuleName(), field, value, getField(ctx, field));
    }
}
