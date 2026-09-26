package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.checkout.CheckoutItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CheckoutItemEntityRepository extends JpaRepository<CheckoutItemEntity, Long> {
    List<CheckoutItemEntity> findByCheckout_IdOrderByProductIdAsc(UUID checkoutId);
}
