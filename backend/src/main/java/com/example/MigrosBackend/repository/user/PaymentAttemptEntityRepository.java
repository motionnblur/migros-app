package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.payment.PaymentAttemptEntity;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptEntityRepository extends JpaRepository<PaymentAttemptEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM PaymentAttemptEntity a WHERE a.id = :id")
    Optional<PaymentAttemptEntity> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM PaymentAttemptEntity a WHERE a.checkout.id = :checkoutId")
    Optional<PaymentAttemptEntity> findByCheckoutIdForUpdate(@Param("checkoutId") UUID checkoutId);

    @Query("SELECT a FROM PaymentAttemptEntity a WHERE a.checkout.id = :checkoutId")
    Optional<PaymentAttemptEntity> findByCheckoutId(@Param("checkoutId") UUID checkoutId);

    @Query("SELECT a FROM PaymentAttemptEntity a "
            + "WHERE a.checkout.id = :checkoutId AND a.checkout.userEntity.id = :userId")
    Optional<PaymentAttemptEntity> findOwnedByCheckoutId(@Param("checkoutId") UUID checkoutId,
                                                         @Param("userId") Long userId);

    @Query("SELECT a FROM PaymentAttemptEntity a WHERE a.stripeChargeId = :chargeId")
    Optional<PaymentAttemptEntity> findByStripeChargeId(@Param("chargeId") String chargeId);

    @Query("SELECT a.id FROM PaymentAttemptEntity a "
            + "WHERE a.status IN :statuses AND a.updatedAt < :before")
    List<UUID> findStaleIds(@Param("statuses") Collection<PaymentAttemptStatus> statuses,
                            @Param("before") LocalDateTime before);
}
