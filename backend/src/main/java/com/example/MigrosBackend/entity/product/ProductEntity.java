package com.example.MigrosBackend.entity.product;

import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Data
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ProductEntity {
    @Id
    @Column(name = "product_entity_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String productName;
    private String subcategoryName;
    private int productCount;
    private float productPrice;
    private float productDiscount;
    private String productDescription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_entity_id", referencedColumnName = "admin_entity_id")
    @JsonBackReference
    private AdminEntity adminEntity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_entity_id", referencedColumnName = "category_entity_id")
    @JsonBackReference
    private CategoryEntity categoryEntity;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "product_entity_id", referencedColumnName = "product_entity_id")
    @JsonManagedReference
    private List<ProductImageEntity> productImageEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "product_entity_id", referencedColumnName = "product_entity_id")
    @JsonManagedReference
    private List<ProductDescriptionEntity> descriptionEntities;

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
