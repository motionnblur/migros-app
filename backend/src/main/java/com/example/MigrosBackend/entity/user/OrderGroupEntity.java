package com.example.MigrosBackend.entity.user;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class OrderGroupEntity {
    @Id
    @Column(name = "order_group_entity_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private LocalDateTime createdAt;
    private String status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_entity_id", referencedColumnName = "user_entity_id")
    @JsonBackReference("user-order-groups")
    private UserEntity userEntity;

    @OneToMany(mappedBy = "orderGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference("group-orders")
    private List<OrderEntity> orderItems = new ArrayList<>();
}


