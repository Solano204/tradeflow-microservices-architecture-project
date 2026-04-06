package io.tradeflow.fraud.service;

import io.tradeflow.fraud.dto.FraudDtos;
import io.tradeflow.fraud.entity.BuyerBaseline;
import io.tradeflow.fraud.repository.BuyerBaselineRepository;
import io.tradeflow.fraud.rules.ScoringContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit; /**
 * ContextEnrichmentService — parallel feature enrichment before scoring.
 *
 * Runs 4 parallel CompletableFuture tasks:
 *   A) MongoDB buyer baseline
 *   B) In-memory rule cache (already live — trivial)
 *   C) IP geolocation + OFAC check (local MaxMind DB + Redis)
 *   D) Merchant cache from Redis
 *
 * Total: ~10-30ms (all happen simultaneously)
 * Virtual Threads: MongoDB and Redis calls don't block carrier threads.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContextEnrichmentService {

    private final BuyerBaselineRepository baselineRepo;
    private final StringRedisTemplate redis;

    @Value("${fraud.cold-start.threshold:3}")
    private int coldStartThreshold;

    public ScoringContext enrich(FraudDtos.ScoreRequest req) {
        // Parallel enrichment — all four happen simultaneously
        CompletableFuture<BuyerBaseline> buyerFuture = CompletableFuture.supplyAsync(
                () -> baselineRepo.findByBuyerId(req.buyerId())
                        .orElse(BuyerBaseline.builder().buyerId(req.buyerId()).build()));

        CompletableFuture<IpInfo> ipFuture = CompletableFuture.supplyAsync(
                () -> enrichIp(req.ipAddress()));

        CompletableFuture<MerchantInfo> merchantFuture = CompletableFuture.supplyAsync(
                () -> enrichMerchant(req.merchantId()));

        try {
            CompletableFuture.allOf(buyerFuture, ipFuture, merchantFuture).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Context enrichment timeout — using partial data: {}", e.getMessage());
        }

        BuyerBaseline baseline  = buyerFuture.getNow(BuyerBaseline.builder().buyerId(req.buyerId()).build());
        IpInfo ipInfo           = ipFuture.getNow(new IpInfo("UNKNOWN", false, false, false));
        MerchantInfo merchant   = merchantFuture.getNow(new MerchantInfo(0, 0.0));

//        boolean isNewBuyer = baseline.getTransactionCount() < coldStartThreshold;
//        BigDecimal avg = baseline.getAvgOrderValue90d() != null
//                ? baseline.getAvgOrderValue90d() : BigDecimal.ZERO;



        boolean isNewBuyer = false;
        BigDecimal avg = baseline.getAvgOrderValue90d() != null
                ? baseline.getAvgOrderValue90d() : BigDecimal.ZERO;

        double deviationFactor = avg.compareTo(BigDecimal.ZERO) > 0
                ? req.amount().divide(avg, 4, RoundingMode.HALF_UP).doubleValue()
                : 1.0;

        int hourOfDay = java.time.LocalDateTime.now(java.time.ZoneId.of("America/Mexico_City")).getHour();
        String dayOfWeek = java.time.LocalDate.now(java.time.ZoneId.of("America/Mexico_City")).getDayOfWeek().name();

        return new ScoringContext(
                req.orderId(), req.buyerId(), req.merchantId(),
                req.amount(), req.currency(),
                req.productCategories() != null ? req.productCategories() : List.of(),
                req.deviceFingerprint(), req.ipAddress(),
                ipInfo.country(), ipInfo.isVpn(), ipInfo.isTor(), ipInfo.isOnOfacList(),
                baseline.getTransactionCount(),
                avg,
                baseline.getPurchaseVelocity7d(),
                isNewBuyer, deviationFactor,
                baseline.getRiskTier() != null ? baseline.getRiskTier() : "STANDARD",
                baseline.getUsualDeviceFingerprints() != null
                        ? baseline.getUsualDeviceFingerprints() : List.of(),
                merchant.ageDays(), merchant.disputeRate(),
                hourOfDay, dayOfWeek);
    }

    private IpInfo enrichIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return new IpInfo("UNKNOWN", false, false, false);
        }
        try {
            // MaxMind GeoIP2 — local DB, no external HTTP (sub-millisecond)
            InetAddress inet = InetAddress.getByName(ipAddress.split(",")[0].trim());
            boolean isVpn  = Boolean.TRUE.equals(redis.opsForSet().isMember("vpn:ips", ipAddress));
            boolean isTor  = Boolean.TRUE.equals(redis.opsForSet().isMember("tor:exits", ipAddress));
            boolean isOfac = Boolean.TRUE.equals(redis.opsForSet().isMember("ofac:ips", ipAddress))
                          || Boolean.TRUE.equals(redis.opsForSet().isMember("suspicious:ips", ipAddress));

            // GeoIP country lookup (local — no network call)
            String country = lookupCountry(inet);
            return new IpInfo(country, isVpn, isTor, isOfac);
        } catch (Exception e) {
            log.debug("IP enrichment failed for {}: {}", ipAddress, e.getMessage());
            return new IpInfo("UNKNOWN", false, false, false);
        }
    }

    private String lookupCountry(InetAddress inet) {
        try {
            // MaxMind database bean injected — see FraudConfig
            return "MX"; // stub — replace with real GeoIP2 lookup
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private MerchantInfo enrichMerchant(String merchantId) {
        try {
            String ageStr  = redis.opsForValue().get("merchant:age:" + merchantId);
            String rateStr = redis.opsForValue().get("merchant:dispute_rate:" + merchantId);
            int age  = ageStr  != null ? Integer.parseInt(ageStr)  : 0;
            double rate = rateStr != null ? Double.parseDouble(rateStr) : 0.0;
            return new MerchantInfo(age, rate);
        } catch (Exception e) {
            return new MerchantInfo(0, 0.0);
        }
    }

    record IpInfo(String country, boolean isVpn, boolean isTor, boolean isOnOfacList) {}
    record MerchantInfo(int ageDays, double disputeRate) {}
}
