package io.tradeflow.identity.repository;

import io.tradeflow.identity.entity.BuyerProfile;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BuyerProfileRepository extends MongoRepository<BuyerProfile, String> {
    Optional<BuyerProfile> findByBuyerId(String buyerId);
    Optional<BuyerProfile> findByEmail(String email);
    void deleteByBuyerId(String buyerId);
}
