package io.tradeflow.analytics.controller;

import io.tradeflow.analytics.dto.AnalyticsDtos.*;
import io.tradeflow.analytics.service.AnalyticsQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * User-facing analytics endpoints.
 *
 * Endpoints 1-7:
 * 1. GET /analytics/merchants/{id}/revenue    — revenue metrics (windowed)
 * 2. GET /analytics/merchants/{id}/orders     — order volume + cancellation rate
 * 3. GET /analytics/merchants/{id}/products   — per-product performance
 * 4. GET /analytics/products/trending         — top products by revenue (platform-wide)
 * 5. GET /analytics/merchants/{id}/summary    — full dashboard summary (all KPIs)
 * 6. GET /analytics/platform/overview         — admin platform-wide GMV, top merchants
 * 7. WS  /analytics/live/{merchantId}         — real-time sales push (handled by WebSocket config)
 */
@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
@Slf4j
public class AnalyticsController {

    private final AnalyticsQueryService queryService;

    // ================================================================
    // ENDPOINT 1 — GET /analytics/merchants/{id}/revenue
    // ================================================================

    @GetMapping("/merchants/{merchantId}/revenue")
    @PreAuthorize("@analyticsSecurityHelper.canAccessMerchantData(#merchantId, #jwt)")
    public ResponseEntity<RevenueResponse> getMerchantRevenue(
            @PathVariable String merchantId,
            @RequestParam(defaultValue = "24h") String window,
            @RequestParam(defaultValue = "MXN") String currency,
            @RequestParam(name = "compare_previous", defaultValue = "false") boolean comparePrevious,
            @AuthenticationPrincipal Jwt jwt) {

        log.debug("Revenue request: merchantId={}, window={}", merchantId, window);
        RevenueResponse response = queryService.getMerchantRevenue(
            merchantId, window, comparePrevious);
        return ResponseEntity.ok(response);
    }

    // ================================================================
    // ENDPOINT 2 — GET /analytics/merchants/{id}/orders
    // ================================================================

    @GetMapping("/merchants/{merchantId}/orders")
    @PreAuthorize("@analyticsSecurityHelper.canAccessMerchantData(#merchantId, #jwt)")
    public ResponseEntity<OrderVolumeResponse> getMerchantOrders(
            @PathVariable String merchantId,
            @RequestParam(defaultValue = "24h") String window,
            @RequestParam(name = "compare_previous", defaultValue = "false") boolean comparePrevious,
            @AuthenticationPrincipal Jwt jwt) {

        OrderVolumeResponse response = queryService.getMerchantOrders(merchantId, window, comparePrevious);
        return ResponseEntity.ok(response);
    }

    // ================================================================
    // ENDPOINT 3 — GET /analytics/merchants/{id}/products
    // ================================================================

    @GetMapping("/merchants/{merchantId}/products")
    @PreAuthorize("@analyticsSecurityHelper.canAccessMerchantData(#merchantId, #jwt)")
    public ResponseEntity<ProductPerformanceResponse> getMerchantProducts(
            @PathVariable String merchantId,
            @RequestParam(defaultValue = "7d") String window,
            @AuthenticationPrincipal Jwt jwt) {

        ProductPerformanceResponse response = queryService.getMerchantProducts(merchantId, window);
        return ResponseEntity.ok(response);
    }

    // ================================================================
    // ENDPOINT 4 — GET /analytics/products/trending
    // ================================================================

    @GetMapping("/products/trending")
    public ResponseEntity<TrendingProductsResponse> getTrendingProducts(
            @RequestParam String category,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "24h") String window) {

        TrendingProductsResponse response = queryService.getTrendingProducts(category, limit);
        return ResponseEntity.ok(response);
    }

    // ================================================================
    // ENDPOINT 5 — GET /analytics/merchants/{id}/summary
    // ================================================================

    @GetMapping("/merchants/{merchantId}/summary")
    @PreAuthorize("@analyticsSecurityHelper.canAccessMerchantData(#merchantId, #jwt)")
    public ResponseEntity<MerchantSummaryResponse> getMerchantSummary(
            @PathVariable String merchantId,
            @AuthenticationPrincipal Jwt jwt) {

        MerchantSummaryResponse response = queryService.getMerchantSummary(merchantId);
        return ResponseEntity.ok(response);
    }

    // ================================================================
    // ENDPOINT 6 — GET /analytics/platform/overview (admin only)
    // ================================================================

    @GetMapping("/platform/overview")
    @PreAuthorize("hasAuthority('SCOPE_admin') or hasAuthority('SCOPE_internal')")
    public ResponseEntity<PlatformOverviewResponse> getPlatformOverview(
            @RequestParam(defaultValue = "24h") String window) {

        PlatformOverviewResponse response = queryService.getPlatformOverview(window);
        return ResponseEntity.ok(response);
    }
}
