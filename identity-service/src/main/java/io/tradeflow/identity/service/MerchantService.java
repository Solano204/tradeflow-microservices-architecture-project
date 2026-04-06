package io.tradeflow.identity.service;

import at.favre.lib.crypto.bcrypt.BCrypt;
import io.tradeflow.identity.dto.IdentityDtos.*;
import io.tradeflow.identity.entity.*;
import io.tradeflow.identity.exceptions.BadRequestException;
import io.tradeflow.identity.exceptions.ConflictException;
import io.tradeflow.identity.exceptions.ForbiddenException;
import io.tradeflow.identity.exceptions.NotFoundException;
import io.tradeflow.identity.kafka.AuthEventPublisher;
import io.tradeflow.identity.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final MerchantContractRepository contractRepository;
    private final KycDocumentRepository kycDocumentRepository;
    private final IdentityOutboxRepository outboxRepository;
    private final RedisService redisService;
    private final JumioService jumioService;
    private final S3Client s3Client;
    private final AuthEventPublisher authEventPublisher;  // NEW

    private static final String MERCHANT_STATUS_CACHE_KEY = "merchant:status:";
    private static final String CONTRACT_VERSION = "v2024.1";

    @Value("${aws.s3.kyc-bucket}")
    private String kycBucket;

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 4 — POST /merchants/onboard
    // Creates merchant in PENDING_KYC state + records contract acceptance
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public OnboardMerchantResponse onboardMerchant(OnboardMerchantRequest request, String requestIp) {
        if (!request.termsAccepted()) {
            throw new BadRequestException("Terms of service must be accepted");
        }
        if (merchantRepository.existsByTaxId(request.taxId())) {
            throw new ConflictException("Merchant already exists with tax ID: " + request.taxId());
        }

        String merchantId = "mrc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        Map<String, Object> addressMap = buildAddressMap(request.businessAddress());

        // Bank account: encrypt before storing (simplified — in prod use KMS/Vault)
        Map<String, Object> bankAccountMap = Map.of(
                "clabe_masked", maskClabe(request.bankAccount().clabe()),
                "bank", request.bankAccount().bank()
        );

        // Hash the password using BCrypt
        String hashedPassword = BCrypt.withDefaults()
                .hashToString(12, request.password().toCharArray());

        Merchant merchant = Merchant.builder()
                .id(merchantId)
                .businessName(request.businessName())
                .businessType(request.businessType())
                .taxId(request.taxId())
                .contactEmail(request.contactEmail())
                .contactPhone(request.contactPhone())
                .bankAccountEncrypted(bankAccountMap)
                .businessAddress(addressMap)
                .status("PENDING_KYC")
                .kycStatus("NOT_STARTED")
                .build();

        // Contract record — legal requirement
        MerchantContract contract = MerchantContract.builder()
                .merchantId(merchantId)
                .contractVersion(CONTRACT_VERSION)
                .acceptedAt(Instant.now())
                .ipAddress(requestIp)
                .build();

        // OUTBOX EVENT for merchant onboarding + password
        Map<String, Object> payload = Map.of(
                "event_type",       "merchant.onboarding.started",
                "merchant_id",      merchantId,
                "business_name",    request.businessName(),
                "contact_email",    request.contactEmail(),
                "hashed_password",  hashedPassword,  // ← NUEVO CAMPO
                "roles",            List.of("MERCHANT"),
                "timestamp",        Instant.now().toString()
        );

        IdentityOutbox outbox = IdentityOutbox.builder()
                .eventType("merchant.onboarding.started")
                .payload(payload)
                .aggregateId(merchantId)
                .build();

        merchantRepository.save(merchant);
        contractRepository.save(contract);
        outboxRepository.save(outbox);

        log.info("Merchant onboarded: merchantId={}, status=PENDING_KYC", merchantId);

        return new OnboardMerchantResponse(
                merchantId,
                "PENDING_KYC",
                "/merchants/" + merchantId + "/kyc/documents",
                "Upload identity and business documents",
                merchant.getCreatedAt()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 5 — GET /merchants/{id}/status
    // Public status check: Redis → PostgreSQL
    // ─────────────────────────────────────────────────────────────────────────

    public MerchantStatusResponse getMerchantStatus(String merchantId) {
        // Try Redis (TTL 10 min)
        MerchantStatusResponse cached = redisService.getMerchantStatus(MERCHANT_STATUS_CACHE_KEY + merchantId);
        if (cached != null) {
            return cached;
        }

        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new NotFoundException("Merchant not found: " + merchantId));

        MerchantStatusResponse response = new MerchantStatusResponse(
                merchant.getId(),
                merchant.getStatus(),
                merchant.getBusinessName(),
                merchant.getKycVerifiedAt(),
                merchant.getActiveSince(),
                merchant.getSuspendedAt(),
                merchant.getSuspensionReason()
        );

        redisService.cacheMerchantStatus(MERCHANT_STATUS_CACHE_KEY + merchantId, response);
        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 6 — POST /merchants/{id}/kyc/documents
    // Upload docs → S3 → Jumio API (Virtual Threads handle blocking call)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public KycDocumentUploadResponse uploadKycDocuments(
            String merchantId,
            List<MultipartFile> documents,
            List<String> documentTypes,
            String authenticatedMerchantId) {

        if (!merchantId.equals(authenticatedMerchantId)) {
            throw new ForbiddenException("Cannot upload documents for another merchant");
        }

        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new NotFoundException("Merchant not found: " + merchantId));

        if (!merchant.canUploadKycDocuments()) {
            throw new BadRequestException(
                    "Cannot upload documents in status: " + merchant.getStatus() +
                    ". Merchant must be in PENDING_KYC state."
            );
        }

        // Upload each document to S3
        List<String> s3Urls = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            MultipartFile file = documents.get(i);
            String docType = (documentTypes != null && i < documentTypes.size())
                    ? documentTypes.get(i) : "UNKNOWN";

            String s3Key = "kyc/" + merchantId + "/" + docType + "_" + UUID.randomUUID();
            uploadToS3(s3Key, file);

            String s3Url = "https://" + kycBucket + ".s3.amazonaws.com/" + s3Key;
            s3Urls.add(s3Url);

            KycDocument doc = KycDocument.builder()
                    .merchantId(merchantId)
                    .documentType(docType)
                    .s3Key(s3Key)
                    .s3Url(s3Url)
                    .originalFilename(file.getOriginalFilename())
                    .contentType(file.getContentType())
                    .build();
            kycDocumentRepository.save(doc);
        }

        // Transition to KYC_IN_PROGRESS
        merchant.setStatus("KYC_IN_PROGRESS");
        merchant.setKycStatus("IN_PROGRESS");

        IdentityOutbox outbox = IdentityOutbox.builder()
                .eventType("merchant.kyc.submitted")
                .payload(Map.of(
                        "merchant_id", merchantId,
                        "document_count", documents.size(),
                        "timestamp", Instant.now().toString()
                ))
                .aggregateId(merchantId)
                .build();

        merchantRepository.save(merchant);
        outboxRepository.save(outbox);

        // ── Virtual Thread blocking call to Jumio ────────────────────────────
        // This call can block 2-3 seconds. With Virtual Threads enabled via
        // spring.threads.virtual.enabled=true, this does NOT block a platform thread.
        // 10,000 concurrent uploads = 10,000 virtual threads on a handful of carriers.
        String verificationId = jumioService.submitForVerification(merchantId, s3Urls);
        merchant.setJumioVerificationId(verificationId);
        merchantRepository.save(merchant);
        // ────────────────────────────────────────────────────────────────────

        log.info("KYC submitted: merchantId={}, verificationId={}", merchantId, verificationId);

        return new KycDocumentUploadResponse(
                merchantId,
                "KYC_IN_PROGRESS",
                verificationId,
                "Documents submitted to KYC provider. Result will arrive via webhook."
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL — POST /internal/kyc/webhook (Jumio callback)
    // Transitions merchant to ACTIVE or TERMINATED based on KYC result
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void processKycWebhook(JumioWebhookPayload payload) {
        Merchant merchant = merchantRepository.findById(payload.merchantId())
                .orElseThrow(() -> new NotFoundException("Merchant not found: " + payload.merchantId()));

        String newStatus;
        String eventType;

        switch (payload.status()) {
            case "APPROVED" -> {
                newStatus = "ACTIVE";
                eventType = "merchant.status.changed";
                merchant.setKycVerifiedAt(payload.completedAt());
                merchant.setActiveSince(Instant.now());
                merchant.setKycStatus("APPROVED");
            }
            case "REJECTED" -> {
                newStatus = "TERMINATED";
                eventType = "merchant.status.changed";
                merchant.setKycStatus("REJECTED");
            }
            default -> {
                log.warn("Unknown Jumio status: {} for merchant: {}", payload.status(), payload.merchantId());
                return;
            }
        }

        merchant.setStatus(newStatus);

        IdentityOutbox outbox = IdentityOutbox.builder()
                .eventType(eventType)
                .payload(Map.of(
                        "merchant_id", payload.merchantId(),
                        "status", newStatus,
                        "kyc_status", merchant.getKycStatus(),
                        "timestamp", Instant.now().toString()
                ))
                .aggregateId(payload.merchantId())
                .build();

        merchantRepository.save(merchant);
        outboxRepository.save(outbox);

        // Immediate Redis cache bust
        redisService.evict(MERCHANT_STATUS_CACHE_KEY + payload.merchantId());

        log.info("KYC webhook processed: merchantId={}, newStatus={}", payload.merchantId(), newStatus);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 7 — POST /merchants/{id}/suspend
    // Compliance hold: ACTIVE → SUSPENDED + immediate Redis cache bust
    // ─────────────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 8 — POST /merchants/{id}/terminate
    // Permanent termination + full cleanup cascade via Kafka
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void terminateMerchant(String merchantId, TerminateMerchantRequest request) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new NotFoundException("Merchant not found: " + merchantId));

        if (!merchant.canBeTerminated()) {
            throw new BadRequestException("Merchant is already TERMINATED");
        }

        merchant.setStatus("TERMINATED");
        merchant.setTerminatedAt(Instant.now());
        merchant.setTerminatedBy(request.initiatedBy());

        // This single event fans out to:
        // - Product Catalog: delist all products
        // - Payment: cancel payouts, freeze wallet
        // - Notification: send termination notice
        // - Order: cancel any in-flight SAGAs
        IdentityOutbox outbox = IdentityOutbox.builder()
                .eventType("merchant.terminated")
                .payload(Map.of(
                        "merchant_id", merchantId,
                        "business_name", merchant.getBusinessName(),
                        "reason", request.reason(),
                        "initiated_by", request.initiatedBy(),
                        "terminated_at", Instant.now().toString()
                ))
                .aggregateId(merchantId)
                .build();

        merchantRepository.save(merchant);
        outboxRepository.save(outbox);

        redisService.evict(MERCHANT_STATUS_CACHE_KEY + merchantId);

        log.warn("Merchant terminated: merchantId={}, by={}", merchantId, request.initiatedBy());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 9 — GET /internal/merchants/{id}/status
    // Internal fast-path: Redis only, no auth, K8s NetworkPolicy restricted
    // ─────────────────────────────────────────────────────────────────────────

    public InternalMerchantStatusResponse getInternalMerchantStatus(String merchantId) {
        // Try Redis (TTL 10 min)
        String cached = redisService.getMerchantStatusString(MERCHANT_STATUS_CACHE_KEY + merchantId);
        if (cached != null) {
            return new InternalMerchantStatusResponse(cached);
        }

        // Cache miss → PostgreSQL
        String status = merchantRepository.findStatusById(merchantId)
                .orElseThrow(() -> new NotFoundException("Merchant not found: " + merchantId));

        redisService.cacheMerchantStatusString(MERCHANT_STATUS_CACHE_KEY + merchantId, status);

        return new InternalMerchantStatusResponse(status);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void uploadToS3(String key, MultipartFile file) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(kycBucket)
                    .key(key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(file.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload document to S3: " + key, e);
        }
    }

    private Map<String, Object> buildAddressMap(AddressDto dto) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("street", dto.street());
        m.put("city", dto.city());
        m.put("state", dto.state());
        m.put("postal_code", dto.postalCode());
        m.put("country", dto.country());
        return m;
    }

    private String maskClabe(String clabe) {
        if (clabe == null || clabe.length() < 8) return "****";
        return "*".repeat(clabe.length() - 4) + clabe.substring(clabe.length() - 4);
    }


    // Add this after the suspendMerchant method
    @Transactional
    public void suspendMerchant(String merchantId, SuspendMerchantRequest request) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new NotFoundException("Merchant not found: " + merchantId));

        if (!merchant.canBeSuspended()) {
            throw new BadRequestException(
                    "Cannot suspend merchant in status: " + merchant.getStatus() +
                            ". Must be ACTIVE."
            );
        }

        merchant.setStatus("SUSPENDED");
        merchant.setSuspensionReason(request.reason() + (request.notes() != null ? ": " + request.notes() : ""));
        merchant.setSuspendedAt(Instant.now());
        merchant.setSuspendedBy(request.initiatedBy());

        IdentityOutbox outbox = IdentityOutbox.builder()
                .eventType("merchant.status.changed")
                .payload(Map.of(
                        "merchant_id", merchantId,
                        "status", "SUSPENDED",
                        "reason", request.reason(),
                        "initiated_by", request.initiatedBy(),
                        "timestamp", Instant.now().toString()
                ))
                .aggregateId(merchantId)
                .build();

        merchantRepository.save(merchant);
        outboxRepository.save(outbox);

        // Also publish auth status change event (NEW)
        authEventPublisher.publishUserStatusChanged(
                merchantId,
                merchant.getContactEmail(),
                "SUSPENDED"
        );

        // Synchronous cache bust
        redisService.evict(MERCHANT_STATUS_CACHE_KEY + merchantId);

        log.warn("Merchant suspended: merchantId={}, reason={}, by={}", merchantId, request.reason(), request.initiatedBy());
    }
}
