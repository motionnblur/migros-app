package com.example.MigrosBackend.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ItemImageEntity {
    @Id
    @Column(name = "item_image_entity_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String imagePath;

    @ManyToOne
    @JoinColumn(name = "item_entity_id", referencedColumnName = "item_entity_id")
    @JsonBackReference
    private ItemEntity itemEntity;
}
