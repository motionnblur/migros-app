package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.OrderEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderEntityRepository extends JpaRepository<OrderEntity, Long> {
    List<OrderEntity> findByOrderGroup_Id(Long orderGroupId);
    List<OrderEntity> findByOrderGroupIsNull();
    List<OrderEntity> findByUserIdAndOrderGroupIsNull(Long userId);
    Optional<OrderEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * Takes the order row's write lock before its status is read, for the same
     * reason as {@code OrderGroupEntityRepository.findByIdForUpdate}: a
     * read-then-decide sequence would let two transactions both observe
     * {@code Pending} and both restock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OrderEntity o WHERE o.id = :id")
    Optional<OrderEntity> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OrderEntity o WHERE o.id = :id AND o.userId = :userId")
    Optional<OrderEntity> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
