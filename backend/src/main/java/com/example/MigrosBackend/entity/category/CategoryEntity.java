package com.example.MigrosBackend.entity.category;

import com.example.MigrosBackend.entity.product.ProductEntity;
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
public class CategoryEntity {
    @Id
    @Column(name = "category_entity_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private int categoryId;
    private String categoryName;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "category_entity_id", referencedColumnName = "category_entity_id")
    @JsonManagedReference
    private List<ProductEntity> itemEntities;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "category_entity_id", referencedColumnName = "category_entity_id")
    @JsonManagedReference
    private List<SubCategoryEntity> subCategoryEntities;
}
