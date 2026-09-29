package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.checkout.CheckoutItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CheckoutItemEntityRepository extends JpaRepository<CheckoutItemEntity, Long> {
    List<CheckoutItemEntity> findByCheckout_IdOrderByProductIdAsc(UUID checkoutId);
}
