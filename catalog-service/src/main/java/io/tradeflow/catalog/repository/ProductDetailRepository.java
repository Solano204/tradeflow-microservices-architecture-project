package io.tradeflow.catalog.repository;

import io.tradeflow.catalog.entity.ProductDetail;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductDetailRepository extends MongoRepository<ProductDetail, String> {
    Optional<ProductDetail> findByProductId(String productId);

    // Batch fetch for N+1 prevention in paginated list endpoints
    List<ProductDetail> findByProductIdIn(List<String> productIds);

    void deleteByProductId(String productId);
}
