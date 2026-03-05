package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.order.OrderDto;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.OrderNotFoundException;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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

    @Test
    void getAllOrders_ShouldReturnMappedDtoList() {
        // Arrange
        int page = 0;
        int size = 5;
        Pageable pageable = PageRequest.of(page, size);

        OrderEntity order1 = new OrderEntity();
        order1.setId(10L);
        order1.setTotalPrice(150.50f);
        order1.setStatus("PENDING");

        OrderEntity order2 = new OrderEntity();
        order2.setId(11L);
        order2.setTotalPrice(200.00f);
        order2.setStatus("COMPLETED");

        // Create a Page object containing our mock entities
        Page<OrderEntity> orderPage = new PageImpl<>(List.of(order1, order2));

        when(orderEntityRepository.findAll(pageable)).thenReturn(orderPage);

        // Act
        List<OrderDto> result = userOrderService.getAllOrders(page, size);

        // Assert
        assertNotNull(result);
        assertEquals(2, result.size());

        // Verify mapping for the first item
        OrderDto dto1 = result.get(0);
        assertEquals(10L, dto1.getOrderId());
        assertEquals(150.50f, dto1.getTotalPrice());
        assertEquals("PENDING", dto1.getStatus());

        // Verify mapping for the second item
        OrderDto dto2 = result.get(1);
        assertEquals(11L, dto2.getOrderId());
        assertEquals("COMPLETED", dto2.getStatus());

        verify(orderEntityRepository, times(1)).findAll(pageable);
    }

    @Test
    void getAllOrders_ShouldReturnEmptyList_WhenNoOrdersExist() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 5);
        when(orderEntityRepository.findAll(pageable)).thenReturn(Page.empty());

        // Act
        List<OrderDto> result = userOrderService.getAllOrders(0, 5);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void getUserProfileData_Success() {
        // Arrange
        Long orderId = 100L;
        Long userId = 1L;

        // 1. Mock the Order
        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setUserId(userId); // The link to the user

        // 2. Mock the User
        UserEntity user = new UserEntity();
        user.setUserName("John");
        user.setUserLastName("Doe");
        user.setUserAddress("123 Java St");
        user.setUserAddress2("Apt 4B");
        user.setUserTown("Springfield");
        user.setUserCountry("USA");
        user.setUserPostalCode("12345");

        when(orderEntityRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(userEntityRepository.findById(userId)).thenReturn(Optional.of(user));

        // Act
        UserProfileTableDto result = userOrderService.getUserProfileData(orderId);

        // Assert
        assertNotNull(result);
        assertEquals("John", result.getUserFirstName());
        assertEquals("Doe", result.getUserLastName());
        assertEquals("123 Java St", result.getUserAddress());
        assertEquals("Springfield", result.getUserTown());
        assertEquals("12345", result.getUserPostalCode());

        // Verify both repositories were called
        verify(orderEntityRepository).findById(orderId);
        verify(userEntityRepository).findById(userId);
    }

    @Test
    void getUserProfileData_ThrowsOrderNotFound() {
        // Arrange
        Long orderId = 999L;
        when(orderEntityRepository.findById(orderId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(OrderNotFoundException.class, () -> {
            userOrderService.getUserProfileData(orderId);
        });

        // Verify the second repo was NEVER called
        verify(userEntityRepository, never()).findById(anyLong());
    }

    @Test
    void getUserProfileData_ThrowsUserNotFound() {
        // Arrange
        Long orderId = 100L;
        Long userId = 1L;
        OrderEntity order = new OrderEntity();
        order.setUserId(userId);

        when(orderEntityRepository.findById(orderId)).thenReturn(Optional.of(order));
        // Mocking the scenario where order exists but user doesn't
        when(userEntityRepository.findById(userId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(UserNotFoundException.class, () -> {
            userOrderService.getUserProfileData(orderId);
        });
    }
}