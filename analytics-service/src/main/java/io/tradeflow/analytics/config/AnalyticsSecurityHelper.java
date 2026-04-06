package io.tradeflow.analytics.config;

// ===== SECURITY HELPER for @PreAuthorize expressions =====
@org.springframework.stereotype.Component("analyticsSecurityHelper")
public class AnalyticsSecurityHelper {

    /**
     * Merchants can access their own data.
     * Admins can access any merchant's data.
     */
    public boolean canAccessMerchantData(String merchantId,
                                          org.springframework.security.oauth2.jwt.Jwt jwt) {
        if (jwt == null) return false;
        String sub = jwt.getSubject();
        if (merchantId.equals(sub)) return true;

        Object roles = jwt.getClaim("roles");
        if (roles instanceof Iterable<?> roleList) {
            for (Object role : roleList) {
                if ("ADMIN".equals(role) || "INTERNAL".equals(role)) return true;
            }
        }
        return false;
    }
}
