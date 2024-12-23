package com.example.MigrosBackend.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
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
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_entity_id", referencedColumnName = "admin_entity_id")
    @JsonBackReference
    private AdminEntity adminEntity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_entity_id", referencedColumnName = "category_entity_id")
    @JsonBackReference
    private CategoryEntity categoryEntity;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "item_entity_id", referencedColumnName = "item_entity_id")
    @JsonManagedReference
    private List<ItemImageEntity> itemImageEntities;

    @Override
    public String toString() {
        return "ItemEntity{" +
                "id=" + id +
                ", itemName='" + itemName + '\'' +
                ", itemCount=" + itemCount +
                ", itemPrice=" + itemPrice +
                ", discount=" + discount +
                ", description='" + description + '\'' +
                '}';
    }
}
