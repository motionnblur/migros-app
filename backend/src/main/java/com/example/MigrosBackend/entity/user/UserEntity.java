package com.example.MigrosBackend.entity.user;

import com.example.MigrosBackend.entity.product.ProductEntity;
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
public class UserEntity {
    @Id
    @Column(name = "user_entity_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String userMail;
    private String userName;
    private String userPassword;

    private List<Long> productsIdsInCart;
}
