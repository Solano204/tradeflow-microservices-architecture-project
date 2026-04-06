package io.tradeflow.identity.repository;

import io.tradeflow.identity.entity.MerchantContract;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MerchantContractRepository extends JpaRepository<MerchantContract, String> {
    List<MerchantContract> findByMerchantId(String merchantId);
}
