package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.order.OrderDto;
import com.example.MigrosBackend.dto.order.OrderPageDto;
import com.example.MigrosBackend.exception.shared.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization of {@code GET /admin/panel/getAllOrders} paging.
 *
 * <p>The response merges grouped orders and legacy single-line orders. The two
 * sources draw their ids from independent sequences, so the merged list is
 * ordered by the DTO's {@code orderId} descending and a group and a legacy line
 * can legitimately share the same numeric id. When they do, the current
 * behaviour keeps the group first, because groups are collected before legacy
 * lines and the sort is stable.
 *
 * <p>This test pins that exact merged ordering, the per-group price sum, the
 * windowing, and the {@code total} for an out-of-range page. It must pass
 * byte-for-byte both before and after the paging refactor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class AdminOrderServicePostgresTest {

    private static final String USER_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String ADMIN_SECRET = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jwt.user-secret", () -> USER_SECRET);
        registry.add("jwt.admin-secret", () -> ADMIN_SECRET);
        registry.add("support.internal.key", () -> "integration-test-internal-key");
        registry.add("support.service.internal-key", () -> "integration-test-internal-key");
    }

    @Autowired
    private AdminOrderService adminOrderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE TABLE checkout_item_entity, checkout_entity, order_entity, "
                + "order_group_entity, product_entity, user_entity RESTART IDENTITY CASCADE");

        // Group 1: two lines totalling 15.00. Group 3: no lines, so 0.
        jdbcTemplate.update("INSERT INTO order_group_entity "
                + "(order_group_entity_id, created_at, status, user_id) VALUES "
                + "(1, now(), 'A', 1), (3, now(), 'B', 1)");

        // Legacy lines 1, 2, 5. Line 1 collides with group 1's id on purpose.
        jdbcTemplate.update("INSERT INTO order_entity "
                + "(order_entity_id, count, item_id, price, status, total_price, user_id, order_group_entity_id) VALUES "
                + "(1, 1, 101, 7.00, 'L1', 7.00, 1, NULL), "
                + "(2, 1, 102, 3.00, 'L2', NULL, 1, NULL), "
                + "(5, 1, 103, 2.50, 'L5', 2.50, 1, NULL), "
                + "(11, 1, 101, 10.00, 'A', 10.00, 1, 1), "
                + "(12, 1, 102, 5.00, 'A', 5.00, 1, 1)");
    }

    @Test
    void mergedOrderingTotalAndWindowingAreStableAcrossPages() {
        // 5 (legacy), 3 (group), 2 (legacy), 1 (group), 1 (legacy).
        OrderPageDto first = adminOrderService.getAllOrders(0, 2);
        assertEquals(5L, first.getTotal());
        assertEquals(2, first.getItems().size());
        assertRow(first.getItems().get(0), 5, 5, "2.50", "L5");
        assertRow(first.getItems().get(1), 3, 3, "0", "B");

        OrderPageDto second = adminOrderService.getAllOrders(1, 2);
        assertEquals(5L, second.getTotal());
        assertEquals(2, second.getItems().size());
        assertRow(second.getItems().get(0), 2, 2, "0", "L2");
        assertRow(second.getItems().get(1), 1, 1, "15.00", "A");

        OrderPageDto third = adminOrderService.getAllOrders(2, 2);
        assertEquals(5L, third.getTotal());
        assertEquals(1, third.getItems().size());
        assertRow(third.getItems().get(0), 1, 1, "7.00", "L1");
    }

    @Test
    void anOutOfRangePageReportsTheTotalButNoItems() {
        OrderPageDto beyond = adminOrderService.getAllOrders(3, 2);
        assertEquals(5L, beyond.getTotal());
        assertEquals(0, beyond.getItems().size());

        OrderPageDto farBeyond = adminOrderService.getAllOrders(99, 2);
        assertEquals(5L, farBeyond.getTotal());
        assertEquals(0, farBeyond.getItems().size());
    }

    @Test
    void theWholeListingMatchesThePageWindowExactly() {
        OrderPageDto whole = adminOrderService.getAllOrders(0, 5);
        assertEquals(5L, whole.getTotal());
        assertEquals(5, whole.getItems().size());
        assertRow(whole.getItems().get(0), 5, 5, "2.50", "L5");
        assertRow(whole.getItems().get(1), 3, 3, "0", "B");
        assertRow(whole.getItems().get(2), 2, 2, "0", "L2");
        assertRow(whole.getItems().get(3), 1, 1, "15.00", "A");
        assertRow(whole.getItems().get(4), 1, 1, "7.00", "L1");
    }

    /**
     * The colliding pair must stay in the same order no matter where the window
     * boundary falls between the two rows that share id 1. Paging one row at a
     * time puts a boundary between the group and the legacy line, which is the
     * only case where an unstable or re-sorted window could flip them or repeat
     * one of them.
     */
    @Test
    void collidingGroupAndLegacyIdsKeepTheirOrderAcrossEveryPageBoundary() {
        long[] expectedOrderIds = {5, 3, 2, 1, 1};
        String[] expectedStatuses = {"L5", "B", "L2", "A", "L1"};

        Set<String> seen = new LinkedHashSet<>();
        for (int page = 0; page < expectedOrderIds.length; page++) {
            OrderPageDto dto = adminOrderService.getAllOrders(page, 1);
            assertEquals(5L, dto.getTotal(), "page " + page);
            assertEquals(1, dto.getItems().size(), "page " + page);

            OrderDto row = dto.getItems().get(0);
            assertEquals(expectedOrderIds[page], row.getOrderId(), "page " + page);
            assertEquals(expectedStatuses[page], row.getStatus(), "page " + page);
            assertTrue(seen.add(row.getOrderId() + "|" + row.getStatus()),
                    "row " + row.getOrderId() + "|" + row.getStatus() + " appeared on more than one page");
        }
        assertEquals(5, seen.size());
    }

    /**
     * More than two pages of unchanged rows, read in windows: the windows must
     * concatenate into the single ordered listing and must not share a row.
     */
    @Test
    void moreThanTwoPagesOfUnchangedOrdersConcatenateWithoutOverlap() {
        seedMoreOrders();

        List<String> first = keys(adminOrderService.getAllOrders(0, 4));
        List<String> second = keys(adminOrderService.getAllOrders(1, 4));
        List<String> third = keys(adminOrderService.getAllOrders(2, 4));

        assertEquals(11L, adminOrderService.getAllOrders(0, 4).getTotal());
        assertEquals(List.of("23|L23", "22|L22", "21|L21", "20|L20"), first);
        assertEquals(List.of("11|G11", "10|G10", "5|L5", "3|B"), second);
        assertEquals(List.of("2|L2", "1|A", "1|L1"), third);

        Set<String> seen = new LinkedHashSet<>();
        Stream.of(first, second, third)
                .flatMap(List::stream)
                .forEach(key -> assertTrue(seen.add(key), "row " + key + " appeared on more than one page"));
        assertEquals(11, seen.size());

        // Re-reading the same unchanged data must return the same windows.
        assertEquals(first, keys(adminOrderService.getAllOrders(0, 4)));
        assertEquals(second, keys(adminOrderService.getAllOrders(1, 4)));
        assertEquals(third, keys(adminOrderService.getAllOrders(2, 4)));
    }

    @Test
    void anOutOfRangePageOrRangeIsRejectedRatherThanClamped() {
        assertThrows(GeneralException.class, () -> adminOrderService.getAllOrders(-1, 2));
        assertThrows(GeneralException.class, () -> adminOrderService.getAllOrders(0, 0));
        assertThrows(GeneralException.class, () -> adminOrderService.getAllOrders(0, -1));
        assertThrows(GeneralException.class, () -> adminOrderService.getAllOrders(0, 101));
        assertThrows(GeneralException.class, () -> adminOrderService.getAllOrders(0, Integer.MAX_VALUE));
    }

    private void seedMoreOrders() {
        jdbcTemplate.update("INSERT INTO order_group_entity "
                + "(order_group_entity_id, created_at, status, user_id) VALUES "
                + "(10, now(), 'G10', 1), (11, now(), 'G11', 1)");
        jdbcTemplate.update("INSERT INTO order_entity "
                + "(order_entity_id, count, item_id, price, status, total_price, user_id, order_group_entity_id) VALUES "
                + "(20, 1, 104, 1.00, 'L20', 1.00, 1, NULL), "
                + "(21, 1, 105, 1.00, 'L21', 1.00, 1, NULL), "
                + "(22, 1, 106, 1.00, 'L22', 1.00, 1, NULL), "
                + "(23, 1, 107, 1.00, 'L23', 1.00, 1, NULL)");
    }

    private List<String> keys(OrderPageDto page) {
        return page.getItems().stream()
                .map(item -> item.getOrderId() + "|" + item.getStatus())
                .toList();
    }

    private void assertRow(OrderDto dto, long orderId, long orderGroupId, String total, String status) {
        assertEquals(orderId, dto.getOrderId());
        assertEquals(orderGroupId, dto.getOrderGroupId());
        assertEquals(0, new BigDecimal(total).compareTo(dto.getTotalPrice()),
                "expected total " + total + " but was " + dto.getTotalPrice());
        assertEquals(status, dto.getStatus());
    }
}
