package com.example.MigrosBackend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ItemEntity {
    @Id
    @Column(name = "item_entity_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String itemName;
    private int itemCount;
    private float itemPrice;
    private float discount;

    @ManyToOne
    @JoinColumn(name = "category_entity_id", referencedColumnName = "category_entity_id")
    private CategoryEntity categoryEntity;
}
