package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

    private static final String TOKEN = "mock-token";
    private static final String EMAIL = "test@migros.com";

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        user.setId(1L);
        user.setUserMail(EMAIL);
        user.setProductsIdsInCart(new ArrayList<>(List.of(101L, 101L, 102L)));
    }

    private void stubAuthenticatedUser() {
        when(tokenService.extractUsername(TOKEN)).thenReturn(EMAIL);
        when(userEntityRepository.findByUserMail(EMAIL)).thenReturn(user);
        when(tokenService.validateToken(TOKEN, EMAIL)).thenReturn(true);
    }

    @Test
    void getOrderPrice_ShouldCalculate_WhenStockIsAvailable() {
        stubAuthenticatedUser();

        ProductEntity p1 = new ProductEntity();
        p1.setId(101L);
        p1.setProductPrice(10.0f);
        p1.setProductDiscount(20.0f);
        p1.setProductCount(5);

        ProductEntity p2 = new ProductEntity();
        p2.setId(102L);
        p2.setProductPrice(5.0f);
        p2.setProductCount(2);

        when(productEntityRepository.findAllById(any())).thenReturn(List.of(p1, p2));

        float total = userOrderService.getOrderPrice(TOKEN);

        assertEquals(21.0f, total);
    }

    @Test
    void getOrderPrice_ShouldThrow_WhenCartExceedsStock() {
        stubAuthenticatedUser();

        ProductEntity p1 = new ProductEntity();
        p1.setId(101L);
        p1.setProductPrice(10.0f);
        p1.setProductCount(1);

        ProductEntity p2 = new ProductEntity();
        p2.setId(102L);
        p2.setProductPrice(5.0f);
        p2.setProductCount(2);

        when(productEntityRepository.findAllById(any())).thenReturn(List.of(p1, p2));

        assertThrows(GeneralException.class, () -> userOrderService.getOrderPrice(TOKEN));
    }

    @Test
    void createOrder_ShouldUseDiscountedPrice_AndDecreaseStock_AndClearCart() {
        stubAuthenticatedUser();

        ProductEntity p1 = new ProductEntity();
        p1.setId(101L);
        p1.setProductName("Apple");
        p1.setProductPrice(10.0f);
        p1.setProductDiscount(20.0f);
        p1.setProductCount(2);

        ProductEntity p2 = new ProductEntity();
        p2.setId(102L);
        p2.setProductName("Milk");
        p2.setProductPrice(5.0f);
        p2.setProductCount(5);

        when(productEntityRepository.findAllById(any())).thenReturn(List.of(p1, p2));
        when(orderGroupEntityRepository.save(any(OrderGroupEntity.class))).thenAnswer(invocation -> {
            OrderGroupEntity group = invocation.getArgument(0);
            group.setId(10L);
            return group;
        });

        userOrderService.createOrder(TOKEN);

        assertEquals(0, p1.getProductCount());
        assertEquals(4, p2.getProductCount());
        assertEquals(0, user.getProductsIdsInCart().size());

        ArgumentCaptor<OrderEntity> orderCaptor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderEntityRepository, times(2)).save(orderCaptor.capture());
        List<OrderEntity> savedOrders = orderCaptor.getAllValues();

        OrderEntity appleOrder = savedOrders.stream()
                .filter(item -> item.getItemId().equals(101L))
                .findFirst()
                .orElseThrow();
        assertEquals(8.0f, appleOrder.getPrice());
        assertEquals(16.0f, appleOrder.getTotalPrice());

        verify(productEntityRepository, times(1)).saveAll(any());
        verify(userEntityRepository, times(1)).save(user);
    }

    @Test
    void createOrder_ShouldThrow_WhenAnyProductMissing() {
        stubAuthenticatedUser();

        ProductEntity p1 = new ProductEntity();
        p1.setId(101L);
        p1.setProductName("Apple");
        p1.setProductPrice(10.0f);
        p1.setProductCount(3);

        when(productEntityRepository.findAllById(any())).thenReturn(List.of(p1));

        assertThrows(GeneralException.class, () -> userOrderService.createOrder(TOKEN));
    }

    @Test
    void deleteOrder_ShouldRestock_WhenGroupOrderIsPending() {
        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(200L);
        group.setStatus("Pending");

        OrderEntity itemA = new OrderEntity();
        itemA.setItemId(11L);
        itemA.setCount(2);

        OrderEntity itemB = new OrderEntity();
        itemB.setItemId(12L);
        itemB.setCount(1);

        ProductEntity productA = new ProductEntity();
        productA.setId(11L);
        productA.setProductCount(0);

        ProductEntity productB = new ProductEntity();
        productB.setId(12L);
        productB.setProductCount(5);

        when(orderGroupEntityRepository.findById(200L)).thenReturn(Optional.of(group));
        when(orderEntityRepository.findByOrderGroup_Id(200L)).thenReturn(List.of(itemA, itemB));
        when(productEntityRepository.findById(11L)).thenReturn(Optional.of(productA));
        when(productEntityRepository.findById(12L)).thenReturn(Optional.of(productB));

        userOrderService.deleteOrder(200L);

        assertEquals(2, productA.getProductCount());
        assertEquals(6, productB.getProductCount());
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

        when(orderGroupEntityRepository.findById(201L)).thenReturn(Optional.of(group));
        when(orderEntityRepository.findByOrderGroup_Id(201L)).thenReturn(List.of(itemA));

        userOrderService.deleteOrder(201L);

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

        ProductEntity product = new ProductEntity();
        product.setId(31L);
        product.setProductCount(1);

        when(orderGroupEntityRepository.findById(300L)).thenReturn(Optional.empty());
        when(orderEntityRepository.findById(300L)).thenReturn(Optional.of(legacyOrder));
        when(productEntityRepository.findById(31L)).thenReturn(Optional.of(product));

        userOrderService.deleteOrder(300L);

        assertEquals(5, product.getProductCount());
        verify(productEntityRepository, times(1)).save(product);
        verify(orderEntityRepository, times(1)).delete(legacyOrder);
    }

    @Test
    void deleteOrder_ShouldNotRestockLegacyOrder_WhenStatusIsNotPending() {
        OrderEntity legacyOrder = new OrderEntity();
        legacyOrder.setId(301L);
        legacyOrder.setItemId(32L);
        legacyOrder.setCount(4);
        legacyOrder.setStatus("Delivered");

        when(orderGroupEntityRepository.findById(301L)).thenReturn(Optional.empty());
        when(orderEntityRepository.findById(301L)).thenReturn(Optional.of(legacyOrder));

        userOrderService.deleteOrder(301L);

        verify(productEntityRepository, never()).findById(any());
        verify(productEntityRepository, never()).save(any());
        verify(orderEntityRepository, times(1)).delete(legacyOrder);
    }
}
