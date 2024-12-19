package com.example.MigrosBackend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    @JsonManagedReference
    private CategoryEntity categoryEntity;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "item_entity_id", referencedColumnName = "item_entity_id")
    @JsonIgnore
    private List<ItemImageEntity> itemImageEntities;
}
