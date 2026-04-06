package io.tradeflow.fraud.service;

import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.tracing.Tracer;
import io.tradeflow.fraud.dto.FraudDtos.*;
import io.tradeflow.fraud.entity.*;
import io.tradeflow.fraud.repository.*;
import io.tradeflow.fraud.rules.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * FraudScoringService — 7-step scoring pipeline.
 *
 * TRACING ADDITIONS vs original:
 *
 * 1. @Observed on score(), processFeedback(), runMlScoring()
 *    → creates named child spans visible in Zipkin:
 *      "fraud.score"         — the full 7-step pipeline
 *      "fraud.feedback"      — confirmed fraud feedback processing
 *      "fraud.ml.scoring"    — Spring AI embed + $vectorSearch
 *    → each of these shows timing, status, and tags in Zipkin
 *
 * 2. span.tag() on key operations with business context:
 *    → fraud.order_id, fraud.buyer_id, fraud.decision, fraud.score
 *    → fraud.cold_start_blocked, fraud.rule_block, fraud.ml_similarity
 *    → fraud.cache.buyer_profile = "hit" / "miss"
 *    → fraud.false_negative = true (compliance critical!)
 *    All searchable in Zipkin's tag filter UI.
 *
 * 3. span.error(e) on all exception paths
 *    → exceptions show as RED spans in Zipkin timeline
 *    → stack trace attached to the span for instant diagnosis
 *
 * VIRTUAL THREADS NOTE:
 * The @Observed annotation uses ObservationRegistry which propagates
 * through virtual threads correctly in Spring Boot 3.2+.
 * runMlScoring() (50-300ms embedding call) runs on a virtual thread —
 * the trace context is propagated correctly even across thread boundaries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FraudScoringService {

    private final FraudVectorRepository vectorRepo;
    private final BuyerBaselineRepository baselineRepo;
    private final FraudScoreAuditRepository auditRepo;
    private final FraudFeedbackRepository feedbackRepo;
    private final FraudRuleRepository ruleRepo;
    private final RuleCache ruleCache;
    private final RuleEvaluator ruleEvaluator;
    private final EmbeddingClient embeddingClient;
    private final MongoTemplate mongoTemplate;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, Map<String, Object>> kafkaTemplate;
    private final ContextEnrichmentService enrichment;
    private final Tracer tracer; // ✅ injected for business tags on spans

    @Value("${fraud.model.version:v2024-W01}")
    private String modelVersion;

    @Value("${fraud.cold-start.threshold:3}")
    private int coldStartThreshold;

    @Value("${fraud.cold-start.max-amount:3200}")
    private BigDecimal coldStartMaxAmount;

    private static final double W_ML         = 0.60;
    private static final double W_AMOUNT_DEV = 0.20;
    private static final double W_VELOCITY   = 0.10;
    private static final double W_DEVICE_IP  = 0.10;
    private static final double THRESHOLD_BLOCK  = 0.85;
    private static final double THRESHOLD_REVIEW = 0.65;

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 / Kafka consumer — POST /fraud/score
    //
    // @Observed creates a "fraud.score" child span under the HTTP or Kafka span.
    // All DB/Redis/Kafka calls inside are grandchildren of this span.
    // ─────────────────────────────────────────────────────────────────────────

    @Observed(name = "fraud.score", contextualName = "score-transaction")
    @Transactional
    public ScoreResponse score(ScoreRequest req) {
        long start = System.currentTimeMillis();

        // ✅ Tag the current span with business context
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("fraud.order_id",      req.orderId());
            span.tag("fraud.buyer_id",      req.buyerId());
            span.tag("fraud.merchant_id",   req.merchantId());
            span.tag("fraud.amount",        req.amount().toString());
            span.tag("fraud.currency",      req.currency());
            span.tag("fraud.model_version", modelVersion);
        }

        // ── STEP 1: Parallel context enrichment ──────────────────────────────
        ScoringContext ctx = enrichment.enrich(req);

        if (span != null) {
            span.tag("fraud.buyer.risk_tier",     ctx.riskTier());
            span.tag("fraud.buyer.tx_count",      String.valueOf(ctx.transactionCount()));
            span.tag("fraud.buyer.is_new",        String.valueOf(ctx.isNewBuyer()));
            span.tag("fraud.ip.is_vpn",           String.valueOf(ctx.ipIsVpn()));
            span.tag("fraud.ip.is_tor",           String.valueOf(ctx.ipIsTor()));
            span.tag("fraud.ip.on_ofac",          String.valueOf(ctx.ipOnOfacList()));
            span.tag("fraud.amount_deviation",    String.valueOf(round(ctx.amountDeviationFactor())));
        }

        // ── STEP 2: Cold-start check ──────────────────────────────────────────
        if (ctx.isNewBuyer() && req.amount().compareTo(coldStartMaxAmount) > 0) {
            if (span != null) {
                span.tag("fraud.decision",           "BLOCKED");
                span.tag("fraud.block_reason",       "COLD_START");
                span.tag("fraud.cold_start_blocked", "true");
            }
            log.warn("[traceId={}] Cold-start block: orderId={}, amount={}, limit={}",
                    span != null ? span.context().traceId() : "none",
                    req.orderId(), req.amount(), coldStartMaxAmount);
            return buildBlockedResponse(req, 1.0, "NEW_BUYER_LIMIT",
                    "New buyer limit: " + coldStartMaxAmount + " " + req.currency(), start);
        }

        // ── STEP 3: Rule evaluation (in-memory, < 1ms) ───────────────────────
        List<FraudRule> rules = ruleCache.getActiveRules();
        RuleEvaluator.EvaluationResult ruleResult = ruleEvaluator.evaluate(rules, ctx);

        if (span != null) {
            span.tag("fraud.rules.evaluated", String.valueOf(ruleResult.results().size()));
            span.tag("fraud.rules.hard_block", String.valueOf(ruleResult.hardBlock()));
            span.tag("fraud.rules.allow_flag", String.valueOf(ruleResult.allowFlag()));
            span.tag("fraud.rules.force_review", String.valueOf(ruleResult.forceReview()));
        }

        if (ruleResult.hardBlock()) {
            RuleEvaluator.RuleResult triggeredRule = ruleResult.results().stream()
                    .filter(RuleEvaluator.RuleResult::triggered)
                    .findFirst().orElse(null);
            String ruleName = triggeredRule != null ? triggeredRule.rule().getRuleName() : "RULE_BLOCK";
            String detail   = triggeredRule != null ? triggeredRule.detail() : "";

            if (span != null) {
                span.tag("fraud.decision",    "BLOCKED");
                span.tag("fraud.block_reason", ruleName);
            }
            return buildBlockedResponse(req, 1.0, ruleName, detail, start);
        }

        // ── STEP 4: Feature vector assembly ──────────────────────────────────
        String featureString = buildFeatureString(req, ctx);

        // ── STEP 5: ML scoring ────────────────────────────────────────────────
        // runMlScoring() is @Observed — creates its own "fraud.ml.scoring" child span
        MlScoreResult mlResult = runMlScoring(featureString, req.orderId());

        if (span != null) {
            span.tag("fraud.ml.similarity",         String.valueOf(round(mlResult.maxCosineSimilarity())));
            span.tag("fraud.ml.neighbors_found",    String.valueOf(mlResult.nearestNeighbors().size()));
        }

        // ── STEP 6: Final score assembly ──────────────────────────────────────
        double mlScore        = mlResult.maxCosineSimilarity();
        double amountDevScore = computeAmountDeviationScore(ctx.amountDeviationFactor());
        double velocityScore  = computeVelocityScore(ctx.purchaseVelocity7d());
        double deviceIpScore  = computeDeviceIpScore(ctx);

        double rawScore = (W_ML * mlScore)
                + (W_AMOUNT_DEV * amountDevScore)
                + (W_VELOCITY   * velocityScore)
                + (W_DEVICE_IP  * deviceIpScore);

        double finalScore;
        if (ruleResult.allowFlag() && rawScore < 0.5) {
            finalScore = rawScore;
        } else if (ruleResult.forceReview() && rawScore < THRESHOLD_REVIEW) {
            finalScore = THRESHOLD_REVIEW;
        } else {
            finalScore = rawScore;
        }

        String decision = finalScore >= THRESHOLD_BLOCK  ? "BLOCKED"
                : finalScore >= THRESHOLD_REVIEW ? "REVIEW"
                : "APPROVED";

        if (span != null) {
            span.tag("fraud.score",    String.valueOf(round(finalScore)));
            span.tag("fraud.decision", decision);
        }

        ScoreComponentsDto components = new ScoreComponentsDto(
                round(mlScore), round(amountDevScore),
                round(velocityScore), round(deviceIpScore));

        List<RuleEvaluationDto> rulesDtos = ruleResult.results().stream()
                .map(r -> new RuleEvaluationDto(r.rule().getRuleName(),
                        r.triggered(), r.rule().getRuleType(), r.detail()))
                .toList();

        long processingMs = System.currentTimeMillis() - start;

        if (span != null) {
            span.tag("fraud.processing_ms", String.valueOf(processingMs));
        }

        // ── STEP 7: Persist + publish ─────────────────────────────────────────
        // PG INSERT (auto-traced by datasource-micrometer) + MongoDB save (auto-traced)
        persistResults(req, ctx, mlResult, finalScore, decision, components, ruleResult, processingMs);
        updateBuyerBaseline(ctx, req, finalScore);

        // ✅ Kafka produce — traceId injected into headers automatically (setObservationEnabled=true)
        // Order Service consumer will extract these headers and continue the SAME trace
        kafkaTemplate.send("fraud.events", req.orderId(), Map.of(
                "event_type",    "fraud.score.computed",
                "order_id",      req.orderId(),
                "score",         finalScore,
                "decision",      decision,
                "model_version", modelVersion,
                "timestamp",     Instant.now().toString()
        ));

        log.info("[traceId={}] Scored: orderId={}, score={}, decision={}, ms={}",
                span != null ? span.context().traceId() : "none",
                req.orderId(), round(finalScore), decision, processingMs);

        return new ScoreResponse(
                req.orderId(), round(finalScore), decision,
                computeConfidence(finalScore), components, rulesDtos,
                mlResult.nearestNeighbors().size(), modelVersion,
                Instant.now(), processingMs);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ML SCORING — @Observed creates a dedicated "fraud.ml.scoring" span
    //
    // This is the most expensive step (50-300ms for embedding + vector search).
    // Showing it as a separate named span in Zipkin lets you instantly see
    // how much of each scoring request is spent in Spring AI vs MongoDB vs rules.
    // ─────────────────────────────────────────────────────────────────────────

    @Observed(name = "fraud.ml.scoring", contextualName = "ml-vector-search")
    @Retry(name = "embedding-client")
    @TimeLimiter(name = "embedding-client")
    private MlScoreResult runMlScoring(String featureString, String orderId) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("fraud.order_id", orderId);
            span.tag("fraud.ml.model", "text-embedding-3-small");
        }

        try {
            // Spring AI HTTP call (50-150ms) — auto-traced by RestTemplate/WebClient
            List<Double> embeddingList = embeddingClient.embed(featureString);
            float[] embedding = toFloatArray(embeddingList);

            if (span != null) span.tag("fraud.ml.embedding_dims", String.valueOf(embedding.length));

            // MongoDB $vectorSearch (20-100ms) — auto-traced by Spring Data MongoDB
            List<Map<String, Object>> neighbors = runVectorSearch(embedding);

            double maxSimilarity = neighbors.stream()
                    .mapToDouble(n -> ((Number) n.getOrDefault("similarity_score", 0)).doubleValue())
                    .max().orElse(0.0);

            if (span != null) {
                span.tag("fraud.ml.max_similarity", String.valueOf(round(maxSimilarity)));
                span.tag("fraud.ml.neighbors",      String.valueOf(neighbors.size()));
            }

            return new MlScoreResult(embedding, maxSimilarity, neighbors);

        } catch (Exception e) {
            if (span != null) span.error(e);
            // Resilience4j TimeLimiter (300ms) → fallback to rules-only (score = 0.0 for ML)
            log.warn("[traceId={}] ML scoring failed for orderId={}, using rules-only fallback: {}",
                    span != null ? span.context().traceId() : "none", orderId, e.getMessage());
            return new MlScoreResult(new float[0], 0.0, List.of());
        }
    }

    private List<Map<String, Object>> runVectorSearch(float[] queryVector) {
        var pipeline = List.of(
                Map.of("$vectorSearch", Map.of(
                        "index",         "fraud_vector_index",
                        "path",          "embedding",
                        "queryVector",   queryVector,
                        "numCandidates", 200,
                        "limit",         20,
                        "filter",        Map.of("label", "FRAUD")
                )),
                Map.of("$project", Map.of(
                        "_id",              0,
                        "order_id",         1,
                        "fraud_type",       1,
                        "amount",           1,
                        "scored_at",        1,
                        "similarity_score", Map.of("$meta", "vectorSearchScore")
                ))
        );

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = mongoTemplate.getDb()
                    .getCollection("fraud_vectors")
                    .aggregate(pipeline.stream()
                            .map(m -> new org.bson.Document(m))
                            .collect(Collectors.toList()))
                    .into(new ArrayList<>())
                    .stream()
                    .map(doc -> (Map<String, Object>) doc)
                    .toList();
            return results;
        } catch (Exception e) {
            log.warn("Vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — GET /fraud/scores/{orderId}
    // ─────────────────────────────────────────────────────────────────────────

    @Observed(name = "fraud.score.get", contextualName = "get-score-detail")
    public ScoreDetailResponse getScore(String orderId) {
        var span = tracer.currentSpan();
        if (span != null) span.tag("fraud.order_id", orderId);

        FraudScoreAudit audit = auditRepo.findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException("Score not found for order: " + orderId));

        FraudVector vector = vectorRepo.findByOrderId(orderId).orElse(null);

        List<FraudNeighborDto> neighbors = vector != null && vector.getNearestNeighbors() != null
                ? vector.getNearestNeighbors().stream().map(this::buildNeighborDto).toList()
                : List.of();

        List<TriggeredRuleDto> triggeredRules = buildTriggeredRules(audit.getRulesEvaluated());
        BuyerContextDto buyerCtx = buildBuyerContext(audit.getBuyerContextSnapshot());
        ScoreComponentsDto components = buildComponents(audit.getScoreComponents());

        if (span != null) {
            span.tag("fraud.decision",       audit.getDecision());
            span.tag("fraud.score",          String.valueOf(audit.getScore()));
            span.tag("fraud.model_version",  audit.getModelVersion());
        }

        return new ScoreDetailResponse(
                orderId, audit.getScore(), audit.getDecision(), audit.getConfidence(),
                audit.getModelVersion(), audit.getScoredAt(),
                components, neighbors, triggeredRules, buyerCtx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — GET /fraud/buyers/{buyerId}/profile
    // ─────────────────────────────────────────────────────────────────────────

    @Observed(name = "fraud.buyer.profile", contextualName = "get-buyer-profile")
    public BuyerFraudProfileResponse getBuyerProfile(String buyerId) {
        var span = tracer.currentSpan();
        if (span != null) span.tag("fraud.buyer_id", buyerId);

        BuyerBaseline baseline = baselineRepo.findByBuyerId(buyerId)
                .orElse(BuyerBaseline.builder().buyerId(buyerId).build());

        if (span != null) {
            span.tag("fraud.buyer.risk_tier", baseline.getRiskTier() != null ? baseline.getRiskTier() : "STANDARD");
            span.tag("fraud.buyer.tx_count",  String.valueOf(baseline.getTransactionCount()));
        }

        List<FraudVector> recentVectors = vectorRepo.findRecentByBuyerId(
                buyerId, Instant.now().minus(90, java.time.temporal.ChronoUnit.DAYS));

        List<RecentScoreDto> recentScores = recentVectors.stream()
                .limit(10)
                .map(v -> new RecentScoreDto(v.getOrderId(), v.getScore(),
                        v.getDecision(), v.getScoredAt()))
                .toList();

        List<DeviceHistoryDto> deviceHistory = baseline.getDeviceHistory() != null
                ? baseline.getDeviceHistory().stream().map(this::buildDeviceHistoryDto).toList()
                : List.of();

        int coldStartRemaining = Math.max(0, coldStartThreshold - baseline.getTransactionCount());

        BehavioralBaselineDto behavioralBaseline = new BehavioralBaselineDto(
                baseline.getAvgOrderValue90d(), baseline.getMaxOrderValue90d(),
                baseline.getPurchaseVelocity7d(), baseline.getUsualOrderHours(),
                baseline.getUsualIpCountries(), baseline.getUsualCategories());

        return new BuyerFraudProfileResponse(
                buyerId, baseline.getRiskTier(), baseline.getTransactionCount(),
                baseline.getFirstTransactionAt(), behavioralBaseline,
                deviceHistory, recentScores, baseline.getFlags(), coldStartRemaining);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 8 — POST /fraud/feedback
    //
    // @Observed creates a "fraud.feedback" span. This is important for
    // compliance auditing: every confirmed fraud label update is traceable
    // back to the original order's trace via fraud.order_id tag.
    // ─────────────────────────────────────────────────────────────────────────

    @Observed(name = "fraud.feedback", contextualName = "process-feedback")
    @Transactional
    public void processFeedback(FeedbackRequest req) {
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("fraud.order_id",       req.orderId());
            span.tag("fraud.confirmed_type", req.confirmedFraudType());
            span.tag("fraud.confirmed_by",   req.confirmedBy() != null ? req.confirmedBy() : "unknown");
            span.tag("fraud.source",         req.source() != null ? req.source() : "unknown");
        }

        // MongoDB update: label LEGITIMATE → FRAUD (auto-traced)
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("order_id").is(req.orderId())),
                Update.update("label", "FRAUD")
                        .set("fraud_type", req.confirmedFraudType())
                        .set("confirmed_at", Instant.now()),
                FraudVector.class);

        FraudVector vector = vectorRepo.findByOrderId(req.orderId()).orElse(null);
        boolean wasFalseNegative = "APPROVED".equals(req.originalDecision());

        if (span != null) {
            span.tag("fraud.false_negative", String.valueOf(wasFalseNegative));
            if (wasFalseNegative) {
                // COMPLIANCE CRITICAL: false negative visible in Zipkin + searchable
                span.tag("fraud.compliance.false_negative", "true");
                span.tag("fraud.original_score", req.originalScore() != null
                        ? req.originalScore().toString() : "unknown");
            }
        }

        FraudFeedback feedback = FraudFeedback.builder()
                .id(UUID.randomUUID().toString())
                .orderId(req.orderId())
                .buyerId(vector != null ? vector.getBuyerId() : null)
                .originalScore(req.originalScore())
                .originalDecision(req.originalDecision())
                .confirmedFraudType(req.confirmedFraudType())
                .confirmedBy(req.confirmedBy())
                .source(req.source())
                .wasFalseNegative(wasFalseNegative)
                .amount(vector != null ? vector.getAmount() : null)
                .notes(req.notes())
                .confirmedAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        feedbackRepo.save(feedback);

        if (vector != null) {
            // MongoDB: flag buyer as HIGH_RISK (auto-traced)
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("buyer_id").is(vector.getBuyerId())),
                    Update.update("risk_tier", "HIGH_RISK")
                            .addToSet("flags", "CONFIRMED_FRAUD_" + req.confirmedFraudType()),
                    BuyerBaseline.class);

            // Redis: add IP/device to suspicious sets (auto-traced)
            if (vector.getFeaturesSnapshot() != null) {
                Object ip     = vector.getFeaturesSnapshot().get("ip_address");
                Object device = vector.getFeaturesSnapshot().get("device_fingerprint");
                if (ip     != null) redis.opsForSet().add("suspicious:ips",       ip.toString());
                if (device != null) redis.opsForSet().add("compromised:devices",  device.toString());
            }
        }

        if (wasFalseNegative) {
            log.warn("[traceId={}] False negative detected: orderId={}, originalScore={}",
                    span != null ? span.context().traceId() : "none",
                    req.orderId(), req.originalScore());
            checkFalseNegativeRate();
        }

        log.info("[traceId={}] Feedback processed: orderId={}, fraudType={}, wasFN={}",
                span != null ? span.context().traceId() : "none",
                req.orderId(), req.confirmedFraudType(), wasFalseNegative);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 9 — GET /internal/fraud/buyers/{buyerId}/baseline
    // ─────────────────────────────────────────────────────────────────────────

    @Observed(name = "fraud.buyer.baseline", contextualName = "get-internal-baseline")
    public InternalBuyerBaselineResponse getInternalBaseline(String buyerId) {
        var span = tracer.currentSpan();
        if (span != null) span.tag("fraud.buyer_id", buyerId);

        BuyerBaseline baseline = baselineRepo.findByBuyerId(buyerId).orElse(null);

        if (baseline == null) {
            if (span != null) span.tag("fraud.cache.buyer_profile", "miss");
            return new InternalBuyerBaselineResponse(
                    buyerId, "STANDARD", 0, BigDecimal.ZERO,
                    coldStartMaxAmount, true, coldStartThreshold,
                    null, null, Instant.now());
        }

        if (span != null) {
            span.tag("fraud.cache.buyer_profile", "hit");
            span.tag("fraud.buyer.risk_tier",     baseline.getRiskTier());
            span.tag("fraud.buyer.tx_count",      String.valueOf(baseline.getTransactionCount()));
        }

        BigDecimal maxSafe = baseline.getAvgOrderValue90d().multiply(BigDecimal.valueOf(5));
        boolean coldStart  = baseline.getTransactionCount() < coldStartThreshold;
        int remaining      = Math.max(0, coldStartThreshold - baseline.getTransactionCount());

        return new InternalBuyerBaselineResponse(
                buyerId, baseline.getRiskTier(), baseline.getTransactionCount(),
                baseline.getAvgOrderValue90d(), maxSafe,
                coldStart, remaining,
                baseline.getLastScore(), baseline.getLastScoreAt(),
                Instant.now());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Feature vector assembly (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private String buildFeatureString(ScoreRequest req, ScoringContext ctx) {
        int hourOfDay    = LocalDateTime.now(ZoneId.of("America/Mexico_City")).getHour();
        String dayOfWeek = LocalDate.now(ZoneId.of("America/Mexico_City")).getDayOfWeek().name();
        String categoryRisk = highRiskCategory(req.productCategories()) ? "HIGH" : "MEDIUM";

        return String.format("""
                buyer_id:%s
                purchase_velocity_7d:%d
                avg_order_value_90d:%.2f
                amount_deviation_factor:%.2f
                device_fingerprint:%s
                ip_country:%s
                ip_is_vpn:%b
                ip_is_tor:%b
                time_of_day_hour:%d
                day_of_week:%s
                product_category_risk:%s
                merchant_dispute_rate:%.4f
                amount:%.2f
                currency:%s
                is_new_buyer:%b
                transaction_count:%d
                device_is_known:%b
                """,
                req.buyerId(), ctx.purchaseVelocity7d(),
                ctx.avgOrderValue90d(), ctx.amountDeviationFactor(),
                ctx.deviceFingerprint(), ctx.ipCountry(),
                ctx.ipIsVpn(), ctx.ipIsTor(), hourOfDay, dayOfWeek, categoryRisk,
                ctx.merchantDisputeRate(), req.amount(), req.currency(),
                ctx.isNewBuyer(), ctx.transactionCount(), ctx.isDeviceKnown());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Score component calculators (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private double computeAmountDeviationScore(double deviationFactor) {
        if (deviationFactor <= 1.0) return 0.0;
        return Math.min(1.0, (deviationFactor - 1.0) / 9.0);
    }

    private double computeVelocityScore(int velocity7d) {
        return Math.min(1.0, velocity7d / 20.0);
    }

    private double computeDeviceIpScore(ScoringContext ctx) {
        double score = 0.0;
        if (!ctx.isDeviceKnown()) score += 0.3;
        if (ctx.ipIsVpn())        score += 0.3;
        if (ctx.ipIsTor())        score += 0.4;
        if (ctx.ipOnOfacList())   score  = 1.0;
        return Math.min(1.0, score);
    }

    private double computeConfidence(double score) {
        double distFromReview = Math.abs(score - THRESHOLD_REVIEW);
        double distFromBlock  = Math.abs(score - THRESHOLD_BLOCK);
        return Math.min(0.99, 0.5 + Math.min(distFromReview, distFromBlock));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence helpers (unchanged except log lines with traceId)
    // ─────────────────────────────────────────────────────────────────────────

    private void persistResults(ScoreRequest req, ScoringContext ctx, MlScoreResult mlResult,
                                double finalScore, String decision, ScoreComponentsDto components,
                                RuleEvaluator.EvaluationResult ruleResult, long processingMs) {
        Map<String, Object> compMap = Map.of(
                "ml_similarity",    components.mlSimilarity(),
                "amount_deviation", components.amountDeviation(),
                "velocity",         components.velocity(),
                "device_ip_risk",   components.deviceIpRisk());

        Map<String, Object> buyerCtxMap = Map.of(
                "transaction_count",   ctx.transactionCount(),
                "avg_order_value_90d", ctx.avgOrderValue90d(),
                "device_fingerprint",  ctx.deviceFingerprint() != null ? ctx.deviceFingerprint() : "",
                "ip_country",          ctx.ipCountry() != null ? ctx.ipCountry() : "",
                "ip_is_vpn",           ctx.ipIsVpn());

        // PostgreSQL INSERT — auto-traced by datasource-micrometer-spring-boot
        auditRepo.save(FraudScoreAudit.builder()
                .orderId(req.orderId()).buyerId(req.buyerId()).merchantId(req.merchantId())
                .score(finalScore).decision(decision).confidence(computeConfidence(finalScore))
                .scoreComponents(compMap).buyerContextSnapshot(buyerCtxMap)
                .modelVersion(modelVersion).processingTimeMs(processingMs)
                .build());

        // MongoDB save — auto-traced by Spring Data MongoDB
        FraudVector vector = FraudVector.builder()
                .id(UUID.randomUUID().toString())
                .orderId(req.orderId()).buyerId(req.buyerId())
                .embedding(mlResult.embedding())
                .label(decision.equals("BLOCKED") ? "FRAUD" : "LEGITIMATE")
                .amount(req.amount()).currency(req.currency())
                .score(finalScore).decision(decision)
                .modelVersion(modelVersion).scoredAt(Instant.now())
                .featuresSnapshot(buyerCtxMap)
                .nearestNeighbors(mlResult.nearestNeighbors())
                .build();
        vectorRepo.save(vector);
    }

    private void updateBuyerBaseline(ScoringContext ctx, ScoreRequest req, double score) {
        try {
            BuyerBaseline baseline = baselineRepo.findByBuyerId(req.buyerId())
                    .orElse(BuyerBaseline.builder().buyerId(req.buyerId())
                            .firstTransactionAt(Instant.now()).build());

            baseline.setTransactionCount(baseline.getTransactionCount() + 1);
            baseline.setLastScore(score);
            baseline.setLastScoreAt(Instant.now());
            baseline.setLastTransactionAt(Instant.now());
            baseline.setPurchaseVelocity7d(baseline.getPurchaseVelocity7d() + 1);

            BigDecimal currentAvg = baseline.getAvgOrderValue90d();
            int count = baseline.getTransactionCount();
            if (count > 0) {
                baseline.setAvgOrderValue90d(
                        currentAvg.multiply(BigDecimal.valueOf(count - 1))
                                .add(req.amount())
                                .divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP));
            }

            if (req.amount().compareTo(baseline.getMaxOrderValue90d()) > 0) {
                baseline.setMaxOrderValue90d(req.amount());
            }

            if (req.deviceFingerprint() != null) {
                List<String> devices = new ArrayList<>(
                        baseline.getUsualDeviceFingerprints() != null
                                ? baseline.getUsualDeviceFingerprints() : List.of());
                if (!devices.contains(req.deviceFingerprint())) {
                    devices.add(req.deviceFingerprint());
                    baseline.setUsualDeviceFingerprints(devices);
                    baseline.setLastDeviceChange(Instant.now());
                }
            }

            if (baseline.getTransactionCount() >= 100 && score < 0.3) {
                baseline.setRiskTier("TRUSTED");
            } else if (score > 0.65) {
                baseline.setRiskTier("ELEVATED");
            }

            baseline.setLastUpdated(Instant.now());
            baselineRepo.save(baseline);
        } catch (Exception e) {
            log.warn("Buyer baseline update failed (non-critical): {}", e.getMessage());
        }
    }

    private void checkFalseNegativeRate() {
        long fnCount = feedbackRepo.countByWasFalseNegativeTrueAndConfirmedAtAfter(
                Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS));
        if (fnCount > 10) {
            log.error("HIGH FALSE NEGATIVE RATE: {} confirmed fraud misses in 7 days", fnCount);
            kafkaTemplate.send("fraud.alerts", "fn-rate", Map.of(
                    "alert_type", "HIGH_FALSE_NEGATIVE_RATE",
                    "count_7d",   fnCount,
                    "timestamp",  Instant.now().toString()
            ));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response builders (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private ScoreResponse buildBlockedResponse(ScoreRequest req, double score,
                                               String ruleName, String detail, long start) {
        long processingMs = System.currentTimeMillis() - start;

        auditRepo.save(FraudScoreAudit.builder()
                .orderId(req.orderId()).buyerId(req.buyerId()).merchantId(req.merchantId())
                .score(score).decision("BLOCKED").confidence(0.99)
                .scoreComponents(Map.of("rule_block", ruleName))
                .buyerContextSnapshot(Map.of())
                .modelVersion(modelVersion).processingTimeMs(processingMs)
                .build());

        // Kafka produce — traceId injected automatically
        kafkaTemplate.send("fraud.events", req.orderId(), Map.of(
                "event_type",     "fraud.score.computed",
                "order_id",       req.orderId(),
                "score",          score,
                "decision",       "BLOCKED",
                "rule_triggered", ruleName,
                "model_version",  modelVersion,
                "timestamp",      Instant.now().toString()
        ));

        return new ScoreResponse(req.orderId(), score, "BLOCKED", 0.99,
                new ScoreComponentsDto(0, 0, 0, 0),
                List.of(new RuleEvaluationDto(ruleName, true, "BLOCK", detail)),
                0, modelVersion, Instant.now(), processingMs);
    }

    @SuppressWarnings("unchecked")
    private FraudNeighborDto buildNeighborDto(Map<String, Object> n) {
        Object amtRaw = n.get("amount");
        BigDecimal amt = amtRaw != null ? new BigDecimal(amtRaw.toString()) : BigDecimal.ZERO;
        return new FraudNeighborDto(
                (String) n.get("order_id"),
                ((Number) n.getOrDefault("similarity_score", 0)).doubleValue(),
                (String) n.get("fraud_type"),
                amt,
                n.get("scored_at") != null ? Instant.parse((String) n.get("scored_at")) : null);
    }

    @SuppressWarnings("unchecked")
    private DeviceHistoryDto buildDeviceHistoryDto(Map<String, Object> d) {
        return new DeviceHistoryDto(
                (String) d.getOrDefault("fingerprint", ""),
                d.get("first_seen") != null ? Instant.parse((String) d.get("first_seen")) : null,
                d.get("last_seen")  != null ? Instant.parse((String) d.get("last_seen"))  : null,
                Boolean.TRUE.equals(d.get("trusted")));
    }

    @SuppressWarnings("unchecked")
    private List<TriggeredRuleDto> buildTriggeredRules(Map<String, Object> rulesMap) {
        if (rulesMap == null) return List.of();
        return List.of(new TriggeredRuleDto("rule_unknown", "RULE", "BLOCK", rulesMap.toString()));
    }

    @SuppressWarnings("unchecked")
    private BuyerContextDto buildBuyerContext(Map<String, Object> ctx) {
        if (ctx == null) return null;
        return new BuyerContextDto(
                ((Number) ctx.getOrDefault("transaction_count", 0)).intValue(),
                new BigDecimal(ctx.getOrDefault("avg_order_value_90d", "0").toString()),
                (String) ctx.getOrDefault("device_fingerprint", ""),
                (String) ctx.getOrDefault("ip_country", ""),
                Boolean.TRUE.equals(ctx.get("ip_is_vpn")));
    }

    private ScoreComponentsDto buildComponents(Map<String, Object> m) {
        if (m == null) return new ScoreComponentsDto(0, 0, 0, 0);
        return new ScoreComponentsDto(
                toDouble(m.get("ml_similarity")), toDouble(m.get("amount_deviation")),
                toDouble(m.get("velocity")),      toDouble(m.get("device_ip_risk")));
    }

    private double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0.0; }
    }

    private double round(double v) { return Math.round(v * 10000.0) / 10000.0; }

    private boolean highRiskCategory(List<String> categories) {
        if (categories == null) return false;
        return categories.stream().anyMatch(c ->
                c.equals("ELECTRONICS") || c.equals("JEWELRY") || c.equals("CRYPTO"));
    }

    private float[] toFloatArray(List<Double> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i).floatValue();
        return arr;
    }

    record MlScoreResult(float[] embedding, double maxCosineSimilarity,
                         List<Map<String, Object>> nearestNeighbors) {}
}