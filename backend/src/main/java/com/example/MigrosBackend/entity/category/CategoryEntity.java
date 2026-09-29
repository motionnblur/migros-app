package com.example.MigrosBackend.entity.category;

import com.example.MigrosBackend.entity.product.ProductEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;
import java.util.Objects;

@Entity
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
    private List<ProductEntity> itemEntities;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CategoryEntity other = (CategoryEntity) o;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        if (id == null) {
            return System.identityHashCode(this);
        }
        return Objects.hash(getClass(), id);
    }
}
