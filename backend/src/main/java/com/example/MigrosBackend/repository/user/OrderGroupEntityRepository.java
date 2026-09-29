package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderGroupEntityRepository extends JpaRepository<OrderGroupEntity, Long> {
    List<OrderGroupEntity> findByUserId(Long userId);
    Optional<OrderGroupEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * Takes the group row's write lock before its status is read.
     *
     * <p>Status, cancellation, and deletion are all decided from a status read
     * followed by a conditional restock. Without this lock two transactions can
     * both read {@code Pending} and both restock, returning the same units to
     * live stock twice. Locking the group is also the single serialization
     * point between a user cancellation and a concurrent admin status change.
     *
     * <p>Read-only display paths must keep using the non-locking finders above:
     * taking a write lock to render a list would serialize unrelated requests.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM OrderGroupEntity g WHERE g.id = :id")
    Optional<OrderGroupEntity> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM OrderGroupEntity g WHERE g.id = :id AND g.userId = :userId")
    Optional<OrderGroupEntity> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
