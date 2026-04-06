package io.tradeflow.identity.service;
import at.favre.lib.crypto.bcrypt.BCrypt;

import io.tradeflow.identity.dto.IdentityDtos.*;
import io.tradeflow.identity.entity.Buyer;
import io.tradeflow.identity.entity.BuyerProfile;
import io.tradeflow.identity.entity.IdentityOutbox;
import io.tradeflow.identity.exceptions.ConflictException;
import io.tradeflow.identity.exceptions.ForbiddenException;
import io.tradeflow.identity.exceptions.NotFoundException;
import io.tradeflow.identity.kafka.AuthEventPublisher;
import io.tradeflow.identity.repository.BuyerProfileRepository;
import io.tradeflow.identity.repository.BuyerRepository;
import io.tradeflow.identity.repository.IdentityOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class BuyerService {

    private final BuyerRepository buyerRepository;
    private final BuyerProfileRepository buyerProfileRepository;
    private final IdentityOutboxRepository outboxRepository;
    private final RedisService redisService;
    private final AuthEventPublisher authEventPublisher;  // NEW

    private static final String BUYER_PROFILE_CACHE_KEY = "buyer:profile:";
    private static final SecureRandom RANDOM = new SecureRandom();

    // Characters for generating random passwords
    private static final String PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 — POST /buyers/register
    // Write path: PostgreSQL (ACID) + Outbox (Kafka publish deferred)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public RegisterBuyerResponse registerBuyer(RegisterBuyerRequest request) {
        if (buyerRepository.existsByEmail(request.email())) {
            throw new ConflictException("Buyer already exists with email: " + request.email());
        }

        String buyerId = "usr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // Hash here — raw password never goes to DB or Kafka
        String hashedPassword = BCrypt.withDefaults()
                .hashToString(12, request.password().toCharArray());

        Map<String, Object> addressMap = buildAddressMap(request.address());

        Buyer buyer = Buyer.builder()
                .id(buyerId)
                .email(request.email())
                .fullName(request.fullName())
                .phone(request.phone())
                .address(addressMap)
                .locale(request.locale())
                .savedPaymentMethods(new ArrayList<>())
                .status("ACTIVE")
                .build();

        // ← include hashed_password so Auth Service can consume it
        Map<String, Object> payload = Map.of(
                "event_type",      "buyer.registered",
                "buyer_id",        buyerId,
                "email",           request.email(),
                "full_name",       request.fullName(),
                "locale",          request.locale(),
                "hashed_password", hashedPassword,
                "timestamp",       java.time.Instant.now().toString()
        );

        IdentityOutbox outbox = IdentityOutbox.builder()
                .eventType("buyer.registered")
                .payload(payload)
                .aggregateId(buyerId)
                .build();

        buyerRepository.save(buyer);
        outboxRepository.save(outbox);

        log.info("Buyer registered: buyerId={}", buyerId);

        return new RegisterBuyerResponse(buyerId, "ACTIVE", buyer.getCreatedAt());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — GET /buyers/{id}/profile
    // Read path: Redis → MongoDB (never touches PostgreSQL)
    // ─────────────────────────────────────────────────────────────────────────

    public BuyerProfileResponse getBuyerProfile(String buyerId) {
        // 1. Try Redis cache first (< 1ms)
        BuyerProfileResponse cached = redisService.getBuyerProfile(BUYER_PROFILE_CACHE_KEY + buyerId);
        if (cached != null) {
            return cached;
        }

        // 2. Cache miss → MongoDB (~5ms)
        BuyerProfile profile = buyerProfileRepository.findByBuyerId(buyerId)
                .orElseThrow(() -> new NotFoundException("Buyer profile not found: " + buyerId));

        BuyerProfileResponse response = mapToProfileResponse(profile);

        // 3. Re-cache in Redis (TTL 10 min)
        redisService.cacheBuyerProfile(BUYER_PROFILE_CACHE_KEY + buyerId, response);

        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — PUT /buyers/{id}/profile
    // Write path: PostgreSQL → Redis evict → Kafka event → MongoDB rebuild (async)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void updateBuyerProfile(String buyerId, UpdateBuyerProfileRequest request, String authenticatedUserId) {
        // Authorization: buyers can only update their own profile
        if (!buyerId.equals(authenticatedUserId)) {
            throw new ForbiddenException("Cannot update another buyer's profile");
        }

        Buyer buyer = buyerRepository.findById(buyerId)
                .orElseThrow(() -> new NotFoundException("Buyer not found: " + buyerId));

        // Apply updates
        if (request.address() != null) {
            buyer.setAddress(buildAddressMap(request.address()));
        }
        if (request.savedPaymentMethods() != null) {
            List<Map<String, Object>> methods = request.savedPaymentMethods().stream()
                    .map(this::buildPaymentMethodMap)
                    .toList();
            buyer.setSavedPaymentMethods(methods);
        }

        // Build outbox event — same transaction as the update
        Map<String, Object> payload = new HashMap<>();
        payload.put("buyer_id", buyerId);
        payload.put("address", buyer.getAddress());
        payload.put("saved_payment_methods", buyer.getSavedPaymentMethods());
        payload.put("timestamp", java.time.Instant.now().toString());

        IdentityOutbox outbox = IdentityOutbox.builder()
                .eventType("buyer.profile.updated")
                .payload(payload)
                .aggregateId(buyerId)
                .build();

        buyerRepository.save(buyer);
        outboxRepository.save(outbox);

        // Immediate Redis eviction — synchronous cache bust
        redisService.evict(BUYER_PROFILE_CACHE_KEY + buyerId);

        log.info("Buyer profile updated: buyerId={}", buyerId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Called by Kafka consumer to rebuild MongoDB read model
    // ─────────────────────────────────────────────────────────────────────────

    public void rebuildBuyerProfileInMongo(Map<String, Object> eventPayload) {
        String buyerId = (String) eventPayload.get("buyer_id");

        Buyer buyer = buyerRepository.findById(buyerId).orElse(null);
        if (buyer == null) {
            log.warn("Cannot rebuild profile — buyer not found: {}", buyerId);
            return;
        }

        BuyerProfile profile = buyerProfileRepository.findByBuyerId(buyerId)
                .orElse(BuyerProfile.builder().buyerId(buyerId).build());

        profile.setEmail(buyer.getEmail());
        profile.setName(buyer.getFullName());
        profile.setDefaultAddress(buyer.getAddress());
        profile.setSavedPaymentMethods(
                buyer.getSavedPaymentMethods() != null ? buyer.getSavedPaymentMethods() : new ArrayList<>()
        );
        profile.setLocale(buyer.getLocale());
        profile.setStatus(buyer.getStatus());
        profile.setUpdatedAt(java.time.Instant.now());

        if (profile.getCreatedAt() == null) {
            profile.setCreatedAt(buyer.getCreatedAt());
        }

        buyerProfileRepository.save(profile);
        log.debug("MongoDB buyer profile rebuilt: buyerId={}", buyerId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper to generate random password
    // ─────────────────────────────────────────────────────────────────────────

    private String generateRandomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mapping helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> buildAddressMap(AddressDto dto) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("street", dto.street());
        m.put("city", dto.city());
        m.put("state", dto.state());
        m.put("postal_code", dto.postalCode());
        m.put("country", dto.country());
        return m;
    }

    private Map<String, Object> buildPaymentMethodMap(SavedPaymentMethodDto dto) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", dto.type());
        if (dto.last4() != null) m.put("last4", dto.last4());
        if (dto.brand() != null) m.put("brand", dto.brand());
        if (dto.token() != null) m.put("token", dto.token());
        if (dto.stripePaymentMethodId() != null) m.put("stripe_pm_id", dto.stripePaymentMethodId());
        return m;
    }

    private BuyerProfileResponse mapToProfileResponse(BuyerProfile profile) {
        AddressDto address = null;
        if (profile.getDefaultAddress() != null) {
            Map<String, Object> a = profile.getDefaultAddress();
            address = new AddressDto(
                    (String) a.getOrDefault("street", ""),
                    (String) a.getOrDefault("city", ""),
                    (String) a.getOrDefault("state", ""),
                    (String) a.getOrDefault("postal_code", ""),
                    (String) a.getOrDefault("country", "")
            );
        }

        List<SavedPaymentMethodDto> methods = new ArrayList<>();
        if (profile.getSavedPaymentMethods() != null) {
            for (Map<String, Object> m : profile.getSavedPaymentMethods()) {
                methods.add(new SavedPaymentMethodDto(
                        (String) m.getOrDefault("type", ""),
                        (String) m.get("last4"),
                        (String) m.get("brand"),
                        (String) m.get("token"),
                        (String) m.get("stripe_pm_id")
                ));
            }
        }

        return new BuyerProfileResponse(
                profile.getBuyerId(),
                profile.getName(),
                profile.getEmail(),
                address,
                methods,
                profile.getLocale()
        );
    }
}