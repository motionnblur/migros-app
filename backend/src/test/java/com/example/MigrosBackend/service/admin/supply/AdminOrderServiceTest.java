package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.order.OrderDto;
import com.example.MigrosBackend.dto.order.OrderPageDto;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.exception.admin.OrderNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.AdminOrderRow;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.user.supply.OrderStockRestocker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminOrderServiceTest {
    @Mock
    private UserEntityRepository userEntityRepository;

    @Mock
    private OrderEntityRepository orderEntityRepository;

    @Mock
    private OrderGroupEntityRepository orderGroupEntityRepository;

    @Mock
    private ProductEntityRepository productEntityRepository;

    private AdminOrderService adminOrderService;

    @BeforeEach
    void setUp() {
        adminOrderService = new AdminOrderService(
                userEntityRepository,
                orderEntityRepository,
                orderGroupEntityRepository,
                new OrderStockRestocker(productEntityRepository));
    }

    @Test
    void deleteOrder_LocksTheGroupBeforeReadingItsStatusAndRestocksAtomically() {
        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(200L);
        group.setStatus("Pending");

        OrderEntity itemB = new OrderEntity();
        itemB.setItemId(12L);
        itemB.setCount(1);

        OrderEntity itemA = new OrderEntity();
        itemA.setItemId(11L);
        itemA.setCount(2);

        when(orderGroupEntityRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(group));
        when(orderEntityRepository.findByOrderGroup_Id(200L)).thenReturn(List.of(itemB, itemA));

        adminOrderService.deleteOrder(200L);

        // One atomic statement per product, applied in ascending product id so
        // overlapping orders always take the same lock order.
        InOrder restockOrder = inOrder(productEntityRepository);
        restockOrder.verify(productEntityRepository).incrementStock(11L, 2);
        restockOrder.verify(productEntityRepository).incrementStock(12L, 1);
        verify(productEntityRepository, never()).save(any());
        verify(productEntityRepository, never()).findById(any());
        verify(orderEntityRepository, times(1)).deleteAll(any());
        verify(orderGroupEntityRepository, times(1)).delete(group);
    }

    @Test
    void deleteOrder_ShouldNotRestock_WhenGroupOrderIsNotPending() {
        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(201L);
        group.setStatus("Delivered");

        OrderEntity itemA = new OrderEntity();
        itemA.setItemId(11L);
        itemA.setCount(2);

        when(orderGroupEntityRepository.findByIdForUpdate(201L)).thenReturn(Optional.of(group));
        when(orderEntityRepository.findByOrderGroup_Id(201L)).thenReturn(List.of(itemA));

        adminOrderService.deleteOrder(201L);

        verify(productEntityRepository, never()).incrementStock(any(), anyInt());
        verify(productEntityRepository, never()).save(any());
        verify(orderEntityRepository, times(1)).deleteAll(any());
        verify(orderGroupEntityRepository, times(1)).delete(group);
    }

    @Test
    void deleteOrder_ShouldRestockLegacyOrder_WhenStatusIsPending() {
        OrderEntity legacyOrder = new OrderEntity();
        legacyOrder.setId(300L);
        legacyOrder.setItemId(31L);
        legacyOrder.setCount(4);
        legacyOrder.setStatus("Pending");

        when(orderGroupEntityRepository.findByIdForUpdate(300L)).thenReturn(Optional.empty());
        when(orderEntityRepository.findByIdAndOrderGroupIsNullForUpdate(300L)).thenReturn(Optional.of(legacyOrder));

        adminOrderService.deleteOrder(300L);

        verify(productEntityRepository).incrementStock(31L, 4);
        verify(orderEntityRepository, times(1)).delete(legacyOrder);
    }

    @Test
    void deleteOrder_ShouldNotRestockLegacyOrder_WhenStatusIsNotPending() {
        OrderEntity legacyOrder = new OrderEntity();
        legacyOrder.setId(301L);
        legacyOrder.setItemId(32L);
        legacyOrder.setCount(4);
        legacyOrder.setStatus("Delivered");

        when(orderGroupEntityRepository.findByIdForUpdate(301L)).thenReturn(Optional.empty());
        when(orderEntityRepository.findByIdAndOrderGroupIsNullForUpdate(301L)).thenReturn(Optional.of(legacyOrder));

        adminOrderService.deleteOrder(301L);

        verify(productEntityRepository, never()).incrementStock(any(), anyInt());
        verify(productEntityRepository, never()).findById(any());
        verify(productEntityRepository, never()).save(any());
        verify(orderEntityRepository, times(1)).delete(legacyOrder);
    }

    @Test
    void deleteOrder_ThrowsWhenNeitherAGroupNorALegacyOrderExists() {
        when(orderGroupEntityRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());
        when(orderEntityRepository.findByIdAndOrderGroupIsNullForUpdate(999L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> adminOrderService.deleteOrder(999L));
    }

    @Test
    void deleteOrder_SumsDuplicateLinesForTheSameProduct() {
        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(202L);
        group.setStatus("Pending");

        OrderEntity first = new OrderEntity();
        first.setItemId(11L);
        first.setCount(2);
        OrderEntity second = new OrderEntity();
        second.setItemId(11L);
        second.setCount(3);

        when(orderGroupEntityRepository.findByIdForUpdate(202L)).thenReturn(Optional.of(group));
        when(orderEntityRepository.findByOrderGroup_Id(202L)).thenReturn(List.of(first, second));

        adminOrderService.deleteOrder(202L);

        verify(productEntityRepository, times(1)).incrementStock(11L, 5);
    }

    @Test
    void deleteOrder_IgnoresLinesWithNullOrNonPositiveQuantities() {
        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(203L);
        group.setStatus("Pending");

        OrderEntity nullCount = new OrderEntity();
        nullCount.setItemId(11L);
        OrderEntity zeroCount = new OrderEntity();
        zeroCount.setItemId(12L);
        zeroCount.setCount(0);

        when(orderGroupEntityRepository.findByIdForUpdate(203L)).thenReturn(Optional.of(group));
        when(orderEntityRepository.findByOrderGroup_Id(203L)).thenReturn(List.of(nullCount, zeroCount));

        adminOrderService.deleteOrder(203L);

        verify(productEntityRepository, never()).incrementStock(any(), anyInt());
    }

    @Test
    void updateOrderStatus_LocksTheGroupAndAppliesTheStatusToHeaderAndLines() {
        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(300L);
        group.setStatus("Pending");

        OrderEntity line = new OrderEntity();
        line.setId(301L);
        line.setStatus("Pending");

        when(orderGroupEntityRepository.findByIdForUpdate(300L)).thenReturn(Optional.of(group));
        when(orderEntityRepository.findByOrderGroup_Id(300L)).thenReturn(List.of(line));

        adminOrderService.updateOrderStatus(300L, "Shipped");

        assertEquals("Shipped", group.getStatus());
        assertEquals("Shipped", line.getStatus());
        verify(orderGroupEntityRepository).save(group);
        verify(orderEntityRepository).saveAll(List.of(line));
        verify(orderGroupEntityRepository, never()).findById(any());
    }

    @Test
    void updateOrderStatus_LocksALegacyOrderWhenNoGroupMatches() {
        OrderEntity legacyOrder = new OrderEntity();
        legacyOrder.setId(400L);
        legacyOrder.setStatus("Pending");

        when(orderGroupEntityRepository.findByIdForUpdate(400L)).thenReturn(Optional.empty());
        when(orderEntityRepository.findByIdAndOrderGroupIsNullForUpdate(400L)).thenReturn(Optional.of(legacyOrder));

        adminOrderService.updateOrderStatus(400L, "Delivered");

        assertEquals("Delivered", legacyOrder.getStatus());
        verify(orderEntityRepository).save(legacyOrder);
        verify(orderEntityRepository, never()).findById(any());
    }

    @Test
    void updateOrderStatus_ThrowsWhenTheOrderDoesNotExist() {
        when(orderGroupEntityRepository.findByIdForUpdate(500L)).thenReturn(Optional.empty());
        when(orderEntityRepository.findByIdAndOrderGroupIsNullForUpdate(500L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> adminOrderService.updateOrderStatus(500L, "Shipped"));
    }

    @Test
    void updateOrderStatus_StoresTheAdminStatusStringVerbatim() {
        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(301L);
        group.setStatus("Pending");

        when(orderGroupEntityRepository.findByIdForUpdate(301L)).thenReturn(Optional.of(group));
        when(orderEntityRepository.findByOrderGroup_Id(301L)).thenReturn(List.of());

        adminOrderService.updateOrderStatus(301L, "any-status-string");

        assertEquals("any-status-string", group.getStatus());
        verify(orderEntityRepository, never()).deleteAll(any());
        verify(productEntityRepository, never()).incrementStock(any(), anyInt());
    }

    @Test
    void deleteOrder_UsesTheGroupStatusNotTheLineStatusToDecideRestocking() {
        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(302L);
        group.setStatus("Delivered");

        OrderEntity line = new OrderEntity();
        line.setItemId(11L);
        line.setCount(2);
        line.setStatus("Pending");

        when(orderGroupEntityRepository.findByIdForUpdate(302L)).thenReturn(Optional.of(group));
        when(orderEntityRepository.findByOrderGroup_Id(302L)).thenReturn(List.of(line));

        adminOrderService.deleteOrder(302L);

        verify(productEntityRepository, never()).incrementStock(eq(11L), anyInt());
    }

    @Test
    void getAllOrders_LoadsOnlyTheRequestedPageAndNeverTheWholeTable() {
        AdminOrderRow firstRow = row(5, 5, "2.50", "L5");
        AdminOrderRow secondRow = row(3, 3, "0", "B");
        when(orderGroupEntityRepository.findAdminOrderPage(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(firstRow, secondRow), PageRequest.of(0, 2), 5));

        OrderPageDto result = adminOrderService.getAllOrders(0, 2);

        assertEquals(5L, result.getTotal());
        assertEquals(2, result.getItems().size());
        assertRow(result.getItems().get(0), 5, 5, "2.50", "L5");
        assertRow(result.getItems().get(1), 3, 3, "0", "B");

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderGroupEntityRepository).findAdminOrderPage(pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(2, pageable.getValue().getPageSize());

        // The old N+1 path must be gone: no findAll and no per-page line queries.
        verify(orderGroupEntityRepository, never()).findAll();
        verify(orderEntityRepository, never()).findByOrderGroupIsNull();
        verify(orderEntityRepository, never()).findByOrderGroup_Id(any());
    }

    @Test
    void getAllOrders_RejectsANegativePageInsteadOfClampingItToTheFirstPage() {
        assertThrows(GeneralException.class, () -> adminOrderService.getAllOrders(-1, 2));
        assertThrows(GeneralException.class, () -> adminOrderService.getAllOrders(-3, 2));

        verify(orderGroupEntityRepository, never()).findAdminOrderPage(any());
    }

    @Test
    void getAllOrders_RejectsANonPositiveOrOversizedRangeInsteadOfCountingOnly() {
        assertThrows(GeneralException.class, () -> adminOrderService.getAllOrders(0, 0));
        assertThrows(GeneralException.class, () -> adminOrderService.getAllOrders(0, -1));
        assertThrows(GeneralException.class, () -> adminOrderService.getAllOrders(0, 101));
        assertThrows(GeneralException.class, () -> adminOrderService.getAllOrders(0, Integer.MAX_VALUE));

        // Nothing is fetched at all: the bound is checked before the page query,
        // and the count the page response already carries is what a client sizes
        // its paginator from.
        verify(orderGroupEntityRepository, never()).findAdminOrderPage(any());
    }

    @Test
    void getAllOrders_LeavesTheNativeUnionOrderingAlone() {
        when(orderGroupEntityRepository.findAdminOrderPage(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 2), 5));

        adminOrderService.getAllOrders(0, 2);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderGroupEntityRepository).findAdminOrderPage(pageable.capture());
        assertTrue(pageable.getValue().getSort().isUnsorted(),
                "the union query orders by order_id DESC, source_rank ASC itself; a generated sort would replace it");
    }

    @Test
    void getAllOrders_ReportsTheTotalButNoItemsBeyondTheLastPage() {
        when(orderGroupEntityRepository.findAdminOrderPage(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(9, 2), 5));

        OrderPageDto result = adminOrderService.getAllOrders(9, 2);

        assertEquals(5L, result.getTotal());
        assertTrue(result.getItems().isEmpty());
        verify(orderGroupEntityRepository, never()).findAll();
    }

    private AdminOrderRow row(long orderId, long orderGroupId, String total, String status) {
        AdminOrderRow row = mock(AdminOrderRow.class);
        when(row.getOrderId()).thenReturn(orderId);
        when(row.getOrderGroupId()).thenReturn(orderGroupId);
        when(row.getTotalPrice()).thenReturn(new BigDecimal(total));
        when(row.getStatus()).thenReturn(status);
        return row;
    }

    private void assertRow(OrderDto dto, long orderId, long orderGroupId, String total, String status) {
        assertEquals(orderId, dto.getOrderId());
        assertEquals(orderGroupId, dto.getOrderGroupId());
        assertEquals(0, new BigDecimal(total).compareTo(dto.getTotalPrice()));
        assertEquals(status, dto.getStatus());
    }
}
