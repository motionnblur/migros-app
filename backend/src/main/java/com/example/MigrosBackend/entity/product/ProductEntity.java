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

    /**
     * The price a listing shows and a price band filter compares against,
     * materialized at write time.
     *
     * <p>{@link com.example.MigrosBackend.helper.ProductPricingPolicy} owns this
     * arithmetic and is still the only place that computes it; this column is
     * its stored result, not a second formula. It exists because filtering and
     * sorting by "the price the card shows" has to happen in the database - a
     * filter applied after pagination is not a filter - and re-deriving the
     * discounted value in SQL would be an informal second copy of a rounding
     * sequence, which drifts from the first one at exactly the boundaries.
     *
     * <p>{@code NOT NULL} after V13, and every writer sets it, so it can never
     * be missing from the row a filter is compared against. V13 backfills the
     * pre-existing rows from the same rounding sequence, so an upgraded row and
     * a freshly written one hold the same number.
     */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal effectivePrice;

    @Column(nullable = false)
    private String productDescription;

    /**
     * How much is in one package, or {@code null} when the product carries no
     * package size.
     *
     * <p>Nullable with no default, and nullable on purpose. Every row that predates
     * V14 has no package size, and a quantity inferred from a product name or a
     * description would be a guess dressed as data: it would yield a unit price
     * that looks authoritative and is not. An absent size is displayed as nothing
     * at all rather than as an estimate.
     *
     * <p>{@code NUMERIC(12, 3)} because this is a physical quantity and not money:
     * a dose in millilitres or a spice in grams legitimately needs three decimals.
     * The scale is the schema's own, which is what lets
     * {@link com.example.MigrosBackend.service.admin.supply.ProductCreationPolicy}
     * reject a finer value as a 400 instead of letting the driver round it.
     *
     * <p>Always {@code null} together with {@link #packageUnit} or set together
     * with it. A half-filled pair is a row the unit-price arithmetic has no
     * denominator for.
     */
    @Column(precision = 12, scale = 3)
    private BigDecimal packageAmount;

    /**
     * The measure {@link #packageAmount} is counted in: {@code G}, {@code KG},
     * {@code ML}, {@code L} or {@code ADET}.
     *
     * <p>Stored as the canonical upper-case token rather than as free text, because
     * it decides the unit price: a unit nobody defined has no basis to be priced
     * per, and {@link com.example.MigrosBackend.helper.ProductUnitPricePolicy}
     * returns nothing for one rather than guessing.
     */
    @Column(length = 8)
    private String packageUnit;

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
