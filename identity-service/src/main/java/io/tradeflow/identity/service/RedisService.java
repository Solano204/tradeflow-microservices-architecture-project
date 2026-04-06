package io.tradeflow.identity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tradeflow.identity.dto.IdentityDtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final Duration BUYER_PROFILE_TTL = Duration.ofMinutes(10);
    private static final Duration MERCHANT_STATUS_TTL = Duration.ofMinutes(10);

    // ─────────────────────────────────────────────────────────────────────────
    // Buyer profile cache
    // ─────────────────────────────────────────────────────────────────────────

    public BuyerProfileResponse getBuyerProfile(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, BuyerProfileResponse.class);
        } catch (Exception e) {
            log.warn("Redis read failed for key {}: {}", key, e.getMessage());
            return null; // fail open — degrade to MongoDB
        }
    }

    public void cacheBuyerProfile(String key, BuyerProfileResponse profile) {
        try {
            String json = objectMapper.writeValueAsString(profile);
            redisTemplate.opsForValue().set(key, json, BUYER_PROFILE_TTL);
        } catch (Exception e) {
            log.warn("Redis write failed for key {}: {}", key, e.getMessage());
            // fail open — cache miss on next request is fine
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Merchant status cache (full DTO)
    // ─────────────────────────────────────────────────────────────────────────

    public MerchantStatusResponse getMerchantStatus(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, MerchantStatusResponse.class);
        } catch (Exception e) {
            log.warn("Redis read failed for key {}: {}", key, e.getMessage());
            return null;
        }
    }

    public void cacheMerchantStatus(String key, MerchantStatusResponse status) {
        try {
            String json = objectMapper.writeValueAsString(status);
            redisTemplate.opsForValue().set(key, json, MERCHANT_STATUS_TTL);
        } catch (Exception e) {
            log.warn("Redis write failed for key {}: {}", key, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Merchant status string (for internal fast-path endpoint 9)
    // ─────────────────────────────────────────────────────────────────────────

    public String getMerchantStatusString(String key) {
        try {
            // The merchant status string is stored as a sub-field of the JSON
            // For fast-path internal endpoint, we parse just the status field
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            // Quick parse — extract "status" field only
            var node = objectMapper.readTree(json);
            var statusNode = node.get("status");
            return statusNode != null ? statusNode.asText() : null;
        } catch (Exception e) {
            log.warn("Redis read (status string) failed for key {}: {}", key, e.getMessage());
            return null;
        }
    }

    public void cacheMerchantStatusString(String key, String status) {
        try {
            // Store a minimal JSON with just the status so both endpoint 5 and 9 can share the key
            String json = objectMapper.writeValueAsString(new MerchantStatusResponse(
                    key.replace("merchant:status:", ""), status, null, null, null, null, null
            ));
            redisTemplate.opsForValue().set(key, json, MERCHANT_STATUS_TTL);
        } catch (Exception e) {
            log.warn("Redis write (status string) failed for key {}: {}", key, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache eviction — synchronous, used on write operations
    // ─────────────────────────────────────────────────────────────────────────

    public void evict(String key) {
        try {
            redisTemplate.delete(key);
            log.debug("Cache evicted: key={}", key);
        } catch (Exception e) {
            log.warn("Redis eviction failed for key {}: {}", key, e.getMessage());
            // Non-fatal — stale data will expire naturally by TTL
        }
    }
}
