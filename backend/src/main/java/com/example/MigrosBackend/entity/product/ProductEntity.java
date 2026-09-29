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
