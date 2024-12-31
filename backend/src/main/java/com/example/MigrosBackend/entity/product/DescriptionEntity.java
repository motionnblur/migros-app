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
public class DescriptionEntity {
    @Id
    @Column(name = "description_entity_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String descriptionTabName;
    private String descriptionTabContent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_entity_id", referencedColumnName = "product_entity_id")
    @JsonBackReference
    private ProductEntity productEntity;
}
