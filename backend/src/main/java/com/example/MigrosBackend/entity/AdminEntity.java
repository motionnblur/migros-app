package com.example.MigrosBackend.entity;

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
public class AdminEntity {
    @Id
    @Column(name = "admin_entity_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String adminName;
    private String adminPassword;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "admin_entity_id", referencedColumnName = "admin_entity_id")
    @JsonManagedReference
    private List<ProductEntity> itemEntities;
}
