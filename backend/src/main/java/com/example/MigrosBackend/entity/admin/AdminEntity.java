package com.example.MigrosBackend.entity.admin;

import com.example.MigrosBackend.entity.product.ProductEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;
import java.util.Objects;

@Entity
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
    @JsonIgnore
    private String adminPassword;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "admin_entity_id", referencedColumnName = "admin_entity_id")
    private List<ProductEntity> itemEntities;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AdminEntity other = (AdminEntity) o;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        if (id == null) {
            return System.identityHashCode(this);
        }
        return Objects.hash(getClass(), id);
    }

    @Override
    public String toString() {
        return "AdminEntity{" +
                "id=" + id +
                ", adminName='" + adminName + '\'' +
                '}';
    }
}
