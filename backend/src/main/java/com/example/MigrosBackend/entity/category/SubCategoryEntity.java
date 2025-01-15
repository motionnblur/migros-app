package com.example.MigrosBackend.entity.category;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class SubCategoryEntity {
    @Id
    @Column(name = "subcategory_entity_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String subCategoryName;
    private int productCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_entity_id", referencedColumnName = "category_entity_id")
    @JsonBackReference
    private CategoryEntity categoryEntity;
}
