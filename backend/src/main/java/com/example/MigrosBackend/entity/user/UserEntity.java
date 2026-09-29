package com.example.MigrosBackend.entity.user;

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
public class UserEntity {
    @Id
    @Column(name = "user_entity_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String userMail;
    private String userName;
    private String userLastName;
    @JsonIgnore
    private String userPassword;

    private String userAddress;
    private String userAddress2;
    private String userTown;
    private String userCountry;
    private String userPostalCode;

    private List<Long> productsIdsInCart;

    private Boolean banned = false;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "user_entity_id", referencedColumnName = "user_entity_id")
    private List<OrderEntity> orderEntities;

    @OneToMany(mappedBy = "userEntity", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderGroupEntity> orderGroupEntities;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        UserEntity other = (UserEntity) o;
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
        return "UserEntity{" +
                "id=" + id +
                ", userMail='" + userMail + '\'' +
                ", userName='" + userName + '\'' +
                ", userLastName='" + userLastName + '\'' +
                ", userAddress='" + userAddress + '\'' +
                ", userAddress2='" + userAddress2 + '\'' +
                ", userTown='" + userTown + '\'' +
                ", userCountry='" + userCountry + '\'' +
                ", userPostalCode='" + userPostalCode + '\'' +
                ", banned=" + banned +
                '}';
    }
}


