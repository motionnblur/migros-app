package com.example.MigrosBackend.entity.product;

import jakarta.persistence.*;
import lombok.*;

import java.util.Objects;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ProductDescriptionEntity {
    @Id
    @Column(name = "product_description_entity_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String descriptionTabName;
    private String descriptionTabContent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_entity_id", referencedColumnName = "product_entity_id")
    private ProductEntity productEntity;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ProductDescriptionEntity other = (ProductDescriptionEntity) o;
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
