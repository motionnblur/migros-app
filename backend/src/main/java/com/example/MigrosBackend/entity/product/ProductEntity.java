package com.example.MigrosBackend.entity.product;

import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ProductEntity {
    @Id
    @Column(name = "product_entity_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private String subcategoryName;

    @Column(nullable = false)
    private int productCount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal productPrice;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal productDiscount;

    @Column(nullable = false)
    private String productDescription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_entity_id", referencedColumnName = "admin_entity_id")
    private AdminEntity adminEntity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_entity_id", referencedColumnName = "category_entity_id")
    private CategoryEntity categoryEntity;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "product_entity_id", referencedColumnName = "product_entity_id")
    private List<ProductImageEntity> productImageEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "product_entity_id", referencedColumnName = "product_entity_id")
    private List<ProductDescriptionEntity> descriptionEntities;

    /**
     * Optimistic edit version for admin updates.
     *
     * <p>An admin edit form submits the version it loaded. Without it, the
     * update is a blind absolute-count write: a form opened before a checkout
     * reservation can write its stale count back and resurrect sold stock.
     *
     * <p>Every writer of this row must advance the column. JPA-managed writers
     * (the admin update, the locked checkout decrement) do so through this
     * mapping; the bulk stock increment advances it explicitly in the same
     * statement, because a bulk JPQL update bypasses entity version handling.
     * Adding the field to {@code toString} would put a mutable version in
     * every log line that prints a product, so it is deliberately omitted.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ProductEntity other = (ProductEntity) o;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        if (id == null) {
            return System.identityHashCode(this);
        }
        return Objects.hash(getClass(), id);
    }

    @Override
    public String toString() {
        return "ProductEntity{" +
                "id=" + id +
                ", productName='" + productName + '\'' +
                ", productCount=" + productCount +
                ", productPrice=" + productPrice +
                ", productDiscount=" + productDiscount +
                ", productDescription='" + productDescription + '\'' +
                '}';
    }
}
