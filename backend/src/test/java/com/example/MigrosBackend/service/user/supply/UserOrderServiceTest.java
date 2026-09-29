package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.exception.admin.OrderNotFoundException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserOrderServiceTest {
    @Mock
    private TokenService tokenService;

    @Mock
    private UserEntityRepository userEntityRepository;

    @Mock
    private OrderEntityRepository orderEntityRepository;

    @Mock
    private OrderGroupEntityRepository orderGroupEntityRepository;

    @Mock
    private ProductEntityRepository productEntityRepository;

    @InjectMocks
    private UserOrderService userOrderService;

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

        userOrderService.deleteOrder(200L);

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

        userOrderService.deleteOrder(201L);

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

        userOrderService.deleteOrder(300L);

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

        userOrderService.deleteOrder(301L);

        verify(productEntityRepository, never()).incrementStock(any(), anyInt());
        verify(productEntityRepository, never()).findById(any());
        verify(productEntityRepository, never()).save(any());
        verify(orderEntityRepository, times(1)).delete(legacyOrder);
    }

    @Test
    void deleteOrder_ThrowsWhenNeitherAGroupNorALegacyOrderExists() {
        when(orderGroupEntityRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());
        when(orderEntityRepository.findByIdAndOrderGroupIsNullForUpdate(999L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> userOrderService.deleteOrder(999L));
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

        userOrderService.deleteOrder(202L);

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

        userOrderService.deleteOrder(203L);

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

        userOrderService.updateOrderStatus(300L, "Shipped");

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

        userOrderService.updateOrderStatus(400L, "Delivered");

        assertEquals("Delivered", legacyOrder.getStatus());
        verify(orderEntityRepository).save(legacyOrder);
        verify(orderEntityRepository, never()).findById(any());
    }

    @Test
    void updateOrderStatus_ThrowsWhenTheOrderDoesNotExist() {
        when(orderGroupEntityRepository.findByIdForUpdate(500L)).thenReturn(Optional.empty());
        when(orderEntityRepository.findByIdAndOrderGroupIsNullForUpdate(500L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> userOrderService.updateOrderStatus(500L, "Shipped"));
    }

    @Test
    void updateOrderStatus_StoresTheAdminStatusStringVerbatim() {
        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(301L);
        group.setStatus("Pending");

        when(orderGroupEntityRepository.findByIdForUpdate(301L)).thenReturn(Optional.of(group));
        when(orderEntityRepository.findByOrderGroup_Id(301L)).thenReturn(List.of());

        userOrderService.updateOrderStatus(301L, "any-status-string");

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

        userOrderService.deleteOrder(302L);

        verify(productEntityRepository, never()).incrementStock(eq(11L), anyInt());
    }
}
