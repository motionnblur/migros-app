package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.payment.StripeEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StripeEventEntityRepository extends JpaRepository<StripeEventEntity, String> {
}
