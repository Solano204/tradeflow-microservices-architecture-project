package io.tradeflow.fraud.rules;

import java.util.List; /**
 * Immutable scoring context — assembled from enriched features before rule evaluation.
 * Passed to rule evaluator and ML pipeline.
 */
public record ScoringContext(
        String orderId,
        String buyerId,
        String merchantId,
        java.math.BigDecimal amount,
        String currency,
        List<String> productCategories,
        String deviceFingerprint,
        String ipAddress,
        String ipCountry,
        boolean ipIsVpn,
        boolean ipIsTor,
        boolean ipOnOfacList,
        int transactionCount,
        java.math.BigDecimal avgOrderValue90d,
        int purchaseVelocity7d,
        boolean isNewBuyer,
        double amountDeviationFactor,
        String riskTier,
        List<String> usualDeviceFingerprints,
        int merchantAgeDays,
        double merchantDisputeRate,
        int hourOfDay,
        String dayOfWeek
) {
    public boolean isDeviceKnown() {
        return usualDeviceFingerprints != null &&
               usualDeviceFingerprints.contains(deviceFingerprint);
    }
}
