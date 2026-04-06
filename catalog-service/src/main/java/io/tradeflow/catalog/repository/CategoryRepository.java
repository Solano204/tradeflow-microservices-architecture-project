package io.tradeflow.catalog.repository;

import io.tradeflow.catalog.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, String> {
    List<Category> findByActiveTrue();
    List<Category> findByParentIdIsNullAndActiveTrueOrderBySortOrderAsc();
    List<Category> findByParentIdAndActiveTrueOrderBySortOrderAsc(String parentId);
    Optional<Category> findBySlug(String slug);
}