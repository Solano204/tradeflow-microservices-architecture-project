package io.tradeflow.identity.repository;

import io.tradeflow.identity.entity.KycDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KycDocumentRepository extends JpaRepository<KycDocument, String> {
    List<KycDocument> findByMerchantId(String merchantId);
}
