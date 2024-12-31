package com.example.MigrosBackend.entity.product;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ProductImageEntity {
    @Id
    @Column(name = "product_image_entity_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String imagePath;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_entity_id", referencedColumnName = "product_entity_id")
    @JsonBackReference
    private ProductEntity productEntity;
}
