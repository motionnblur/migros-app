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

    /**
     * Reads one <em>legacy</em> order line.
     *
     * <p>Every lookup here is deliberately constrained to
     * {@code orderGroup IS NULL}. An order line used to be an order on its own,
     * and callers still accept either shape for the same numeric id: an
     * {@code orderId} may name an order group or a legacy line. Those two id
     * spaces are <strong>not</strong> shared - they come from independent
     * sequences - so a lookup that misses the group table and then takes "the
     * order line with this id" can land on a line that belongs to an entirely
     * different customer's group. It would then restock that line's products,
     * change its status, or report its owner's profile for an order that does
     * not exist. Restricting the fallback to rows with no group makes "not
     * found" mean not found.
     */
    Optional<OrderEntity> findByIdAndOrderGroupIsNull(Long id);

    Optional<OrderEntity> findByIdAndUserIdAndOrderGroupIsNull(Long id, Long userId);

    /**
     * Takes a legacy order line's write lock before its status is read, for the
     * same reason as {@code OrderGroupEntityRepository.findByIdForUpdate}: a
     * read-then-decide sequence would let two transactions both observe
     * {@code Pending} and both restock.
     *
     * <p>The lock is one statement with the ownership and legacy-shape checks,
     * so neither can change between them, and it cannot be pointed at a line
     * that belongs to some other order group.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OrderEntity o WHERE o.id = :id AND o.orderGroup IS NULL")
    Optional<OrderEntity> findByIdAndOrderGroupIsNullForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OrderEntity o WHERE o.id = :id AND o.userId = :userId AND o.orderGroup IS NULL")
    Optional<OrderEntity> findByIdAndUserIdAndOrderGroupIsNullForUpdate(@Param("id") Long id,
                                                                        @Param("userId") Long userId);
}
