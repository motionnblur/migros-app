package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserOrderServiceTest {
    @Mock
    private TokenService tokenService;
    @Mock
    private UserEntityRepository userEntityRepository;
    @Mock
    private OrderEntityRepository orderEntityRepository;
    @Mock
    private ProductEntityRepository productEntityRepository;

    @InjectMocks
    private UserOrderService userOrderService;

    private UserEntity user;
    private final String token = "mock-token";
    private final String email = "test@migros.com";

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        user.setId(1L);
        user.setUserMail(email);
        user.setProductsIdsInCart(new ArrayList<>(List.of(101L, 101L, 102L))); // 2x item 101, 1x item 102
    }

    @Test
    void getOrderPrice_ShouldCalculateCorrectTotal() {
        // Arrange
        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(true);

        ProductEntity p1 = new ProductEntity();
        p1.setProductPrice(10.0f);
        ProductEntity p2 = new ProductEntity();
        p2.setProductPrice(5.0f);

        when(productEntityRepository.findById(101L)).thenReturn(Optional.of(p1));
        when(productEntityRepository.findById(102L)).thenReturn(Optional.of(p2));

        // Act
        float total = userOrderService.getOrderPrice(token);

        // Assert
        // (2 * 10.0) + (1 * 5.0) = 25.0
        assertEquals(25.0f, total);
    }

    @Test
    void createOrder_ShouldSaveOrdersAndClearCart() {
        // Arrange
        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(true);

        ProductEntity p1 = new ProductEntity();
        p1.setProductPrice(10.0f);
        when(productEntityRepository.findById(anyLong())).thenReturn(Optional.of(p1));

        // Act
        userOrderService.createOrder(token);

        // Assert
        // Verify orders saved (one for each unique product ID: 101 and 102)
        verify(orderEntityRepository, times(2)).save(any(OrderEntity.class));
        // Verify cart is cleared (empty list)
        assertTrue(user.getProductsIdsInCart().isEmpty());
        verify(userEntityRepository, atLeastOnce()).save(user);
    }

    @Test
    void clearUserCart_ShouldEmptyList_WhenTokenValid() {
        // Arrange
        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(true);

        // Act
        userOrderService.clearUserCart(token);

        // Assert
        assertEquals(0, user.getProductsIdsInCart().size());
        verify(userEntityRepository).save(user);
    }

    @Test
    void updateOrderStatus_ShouldUpdateAndSave() {
        // Arrange
        OrderEntity order = new OrderEntity();
        order.setId(1L);
        order.setStatus("Pending");
        when(orderEntityRepository.findById(1L)).thenReturn(Optional.of(order));

        // Act
        userOrderService.updateOrderStatus(1L, "Shipped");

        // Assert
        assertEquals("Shipped", order.getStatus());
        verify(orderEntityRepository).save(order);
    }
}