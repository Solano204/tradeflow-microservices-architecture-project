package io.tradeflow.catalog.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
@Table(name = "categories",
        indexes = {
                @Index(name = "idx_categories_parent", columnList = "parent_id"),
                @Index(name = "idx_categories_slug", columnList = "slug", unique = true)
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class  Category {

    @Id
    @Column(name = "id", length = 100, nullable = false, updatable = false)
    private String id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 100)
    private String slug;

    @Column(name = "parent_id", length = 100)
    private String parentId;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    // JSON array of required attribute names for this category
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_attributes", columnDefinition = "jsonb")
    private List<String> requiredAttributes;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
