package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.payment.StripeEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StripeEventEntityRepository extends JpaRepository<StripeEventEntity, String> {
}
