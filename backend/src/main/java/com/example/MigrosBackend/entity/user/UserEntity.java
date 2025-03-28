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
    private String userLastName;
    private String userPassword;

    private String userAddress;
    private String userAddress2;
    private String userTown;
    private String userCountry;
    private String userPostalCode;

    private List<Long> productsIdsInCart;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "user_entity_id", referencedColumnName = "user_entity_id")
    @JsonManagedReference
    private List<OrderEntity> orderEntities;

    @Override
    public String toString() {
        return "UserEntity{" +
                "id=" + id +
                ", userMail='" + userMail + '\'' +
                ", userName='" + userName + '\'' +
                ", userLastName='" + userLastName + '\'' +
                ", userPassword='" + userPassword + '\'' +
                ", userAddress='" + userAddress + '\'' +
                ", userAddress2='" + userAddress2 + '\'' +
                ", userTown='" + userTown + '\'' +
                ", userCountry='" + userCountry + '\'' +
                ", userPostalCode='" + userPostalCode + '\'' +
                '}';
    }
}
