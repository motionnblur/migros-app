package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.checkout.CheckoutEntity;
import com.example.MigrosBackend.entity.checkout.CheckoutStatus;
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

public interface CheckoutEntityRepository extends JpaRepository<CheckoutEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CheckoutEntity c WHERE c.id = :id")
    Optional<CheckoutEntity> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CheckoutEntity c WHERE c.id = :id AND c.userEntity.id = :userId")
    Optional<CheckoutEntity> findOwnedByIdForUpdate(@Param("id") UUID id, @Param("userId") Long userId);

    @Query("SELECT c FROM CheckoutEntity c WHERE c.id = :id AND c.userEntity.id = :userId")
    Optional<CheckoutEntity> findOwnedById(@Param("id") UUID id, @Param("userId") Long userId);

    @Query("SELECT c FROM CheckoutEntity c WHERE c.userEntity.id = :userId AND c.status IN :statuses ORDER BY c.createdAt DESC")
    List<CheckoutEntity> findByUserIdAndStatusIn(@Param("userId") Long userId,
                                                 @Param("statuses") Collection<CheckoutStatus> statuses);

    @Query("SELECT c.id FROM CheckoutEntity c WHERE c.status IN :statuses AND c.expiresAt < :now")
    List<UUID> findExpiredIds(@Param("statuses") Collection<CheckoutStatus> statuses,
                              @Param("now") LocalDateTime now);
}
