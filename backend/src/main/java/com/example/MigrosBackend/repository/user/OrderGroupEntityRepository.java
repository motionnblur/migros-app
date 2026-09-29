package com.example.MigrosBackend.repository.user;

import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderGroupEntityRepository extends JpaRepository<OrderGroupEntity, Long> {

    /**
     * Merged, database-paged admin order listing.
     *
     * <p>Grouped orders and legacy single-line orders draw their ids from
     * independent sequences, so the response is the union of both ordered by
     * the DTO's {@code orderId} descending. Two rows can therefore share an id
     * (one group, one legacy line); {@code source_rank} keeps the group before
     * the legacy line for a tie, which is what the previous stable in-memory
     * sort produced. Group price is summed in the database and the window is
     * applied by the database, so a page never loads the whole table.
     */
    String ADMIN_ORDER_PAGE_QUERY = """
            SELECT merged.order_id       AS orderId,
                   merged.order_group_id AS orderGroupId,
                   merged.total_price    AS totalPrice,
                   merged.status         AS status
            FROM (
                SELECT g.order_group_entity_id AS order_id,
                       g.order_group_entity_id AS order_group_id,
                       COALESCE((SELECT SUM(o.total_price)
                                 FROM order_entity o
                                 WHERE o.order_group_entity_id = g.order_group_entity_id), 0) AS total_price,
                       g.status AS status,
                       0 AS source_rank
                FROM order_group_entity g
                UNION ALL
                SELECT o.order_entity_id AS order_id,
                       o.order_entity_id AS order_group_id,
                       COALESCE(o.total_price, 0) AS total_price,
                       o.status AS status,
                       1 AS source_rank
                FROM order_entity o
                WHERE o.order_group_entity_id IS NULL
            ) merged
            ORDER BY merged.order_id DESC, merged.source_rank ASC
            """;

    String ADMIN_ORDER_COUNT_QUERY = """
            SELECT COUNT(*) FROM (
                SELECT g.order_group_entity_id
                FROM order_group_entity g
                UNION ALL
                SELECT o.order_entity_id
                FROM order_entity o
                WHERE o.order_group_entity_id IS NULL
            ) counted
            """;

    @Query(value = ADMIN_ORDER_PAGE_QUERY, countQuery = ADMIN_ORDER_COUNT_QUERY, nativeQuery = true)
    Page<AdminOrderRow> findAdminOrderPage(Pageable pageable);

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
