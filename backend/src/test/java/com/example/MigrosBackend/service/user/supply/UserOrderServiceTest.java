package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.order.OrderDto;
import com.example.MigrosBackend.dto.order.OrderPageDto;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.OrderNotFoundException;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
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
import static org.mockito.ArgumentMatchers.any;
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
    private OrderGroupEntityRepository orderGroupEntityRepository;
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
        user.setProductsIdsInCart(new ArrayList<>(List.of(101L, 101L, 102L)));
    }

    @Test
    void getOrderPrice_ShouldCalculateCorrectTotal() {
        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(true);

        ProductEntity p1 = new ProductEntity();
        p1.setProductPrice(10.0f);
        ProductEntity p2 = new ProductEntity();
        p2.setProductPrice(5.0f);

        when(productEntityRepository.findById(101L)).thenReturn(Optional.of(p1));
        when(productEntityRepository.findById(102L)).thenReturn(Optional.of(p2));

        float total = userOrderService.getOrderPrice(token);

        assertEquals(25.0f, total);
    }

    @Test
    void getOrderPrice_ShouldReturnZero_WhenTokenInvalid() {
        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(false);

        float total = userOrderService.getOrderPrice(token);

        assertEquals(0.0f, total);
        verify(productEntityRepository, never()).findById(anyLong());
    }

    @Test
    void createOrder_ShouldSaveOrderGroupAndItems_AndClearCart() {
        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(true);

        when(orderGroupEntityRepository.save(any(OrderGroupEntity.class)))
                .thenAnswer(invocation -> {
                    OrderGroupEntity group = invocation.getArgument(0);
                    group.setId(10L);
                    return group;
                });

        ProductEntity p1 = new ProductEntity();
        p1.setProductPrice(10.0f);
        when(productEntityRepository.findById(anyLong())).thenReturn(Optional.of(p1));

        userOrderService.createOrder(token);

        verify(orderGroupEntityRepository, times(1)).save(any(OrderGroupEntity.class));
        verify(orderEntityRepository, times(2)).save(any(OrderEntity.class));
        assertTrue(user.getProductsIdsInCart().isEmpty());
        verify(userEntityRepository, atLeastOnce()).save(user);
    }

    @Test
    void createOrder_ShouldNotSave_WhenTokenInvalid() {
        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(false);

        userOrderService.createOrder(token);

        verify(orderGroupEntityRepository, never()).save(any());
        verify(orderEntityRepository, never()).save(any());
    }

    @Test
    void createOrder_ShouldNotSave_WhenCartEmpty() {
        user.setProductsIdsInCart(new ArrayList<>());
        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(true);

        userOrderService.createOrder(token);

        verify(orderGroupEntityRepository, never()).save(any());
        verify(orderEntityRepository, never()).save(any());
    }

    @Test
    void clearUserCart_ShouldEmptyList_WhenTokenValid() {
        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(true);

        userOrderService.clearUserCart(token);

        assertEquals(0, user.getProductsIdsInCart().size());
        verify(userEntityRepository).save(user);
    }

    @Test
    void updateOrderStatus_ShouldUpdateGroupAndItems() {
        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(1L);
        group.setStatus("Pending");

        OrderEntity item1 = new OrderEntity();
        item1.setStatus("Pending");
        OrderEntity item2 = new OrderEntity();
        item2.setStatus("Pending");

        when(orderGroupEntityRepository.findById(1L)).thenReturn(Optional.of(group));
        when(orderEntityRepository.findByOrderGroup_Id(1L)).thenReturn(List.of(item1, item2));

        userOrderService.updateOrderStatus(1L, "Shipped");

        assertEquals("Shipped", group.getStatus());
        assertEquals("Shipped", item1.getStatus());
        assertEquals("Shipped", item2.getStatus());
        verify(orderGroupEntityRepository).save(group);
        verify(orderEntityRepository).saveAll(List.of(item1, item2));
    }

    @Test
    void updateOrderStatus_ShouldUpdateLegacyOrder_WhenGroupMissing() {
        OrderEntity legacy = new OrderEntity();
        legacy.setId(5L);
        legacy.setStatus("Pending");

        when(orderGroupEntityRepository.findById(5L)).thenReturn(Optional.empty());
        when(orderEntityRepository.findById(5L)).thenReturn(Optional.of(legacy));

        userOrderService.updateOrderStatus(5L, "Delivered");

        assertEquals("Delivered", legacy.getStatus());
        verify(orderEntityRepository).save(legacy);
    }

    @Test
    void getAllOrders_ShouldReturnPagedDto() {
        OrderGroupEntity group1 = new OrderGroupEntity();
        group1.setId(2L);
        group1.setStatus("Pending");

        OrderGroupEntity group2 = new OrderGroupEntity();
        group2.setId(5L);
        group2.setStatus("Shipped");

        OrderEntity g1Item1 = new OrderEntity();
        g1Item1.setTotalPrice(10.0f);
        OrderEntity g1Item2 = new OrderEntity();
        g1Item2.setTotalPrice(5.0f);

        OrderEntity g2Item1 = new OrderEntity();
        g2Item1.setTotalPrice(20.0f);

        OrderEntity legacy = new OrderEntity();
        legacy.setId(3L);
        legacy.setTotalPrice(7.0f);
        legacy.setStatus("Legacy");

        when(orderGroupEntityRepository.findAll()).thenReturn(List.of(group1, group2));
        when(orderEntityRepository.findByOrderGroup_Id(2L)).thenReturn(List.of(g1Item1, g1Item2));
        when(orderEntityRepository.findByOrderGroup_Id(5L)).thenReturn(List.of(g2Item1));
        when(orderEntityRepository.findByOrderGroupIsNull()).thenReturn(List.of(legacy));

        OrderPageDto page = userOrderService.getAllOrders(0, 2);

        assertNotNull(page);
        assertEquals(3, page.getTotal());
        assertEquals(2, page.getItems().size());
        assertEquals(5L, page.getItems().get(0).getOrderId());
        assertEquals(3L, page.getItems().get(1).getOrderId());
    }

    @Test
    void getUserProfileData_ShouldUseGroup_WhenExists() {
        Long orderId = 100L;
        Long userId = 1L;

        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(orderId);
        group.setUserId(userId);

        UserEntity profileUser = new UserEntity();
        profileUser.setUserName("John");
        profileUser.setUserLastName("Doe");
        profileUser.setUserAddress("123 Java St");
        profileUser.setUserAddress2("Apt 4B");
        profileUser.setUserTown("Springfield");
        profileUser.setUserCountry("USA");
        profileUser.setUserPostalCode("12345");

        when(orderGroupEntityRepository.findById(orderId)).thenReturn(Optional.of(group));
        when(userEntityRepository.findById(userId)).thenReturn(Optional.of(profileUser));

        UserProfileTableDto result = userOrderService.getUserProfileData(orderId);

        assertNotNull(result);
        assertEquals("John", result.getUserFirstName());
        verify(orderEntityRepository, never()).findById(anyLong());
    }

    @Test
    void getUserProfileData_ShouldUseLegacy_WhenGroupMissing() {
        Long orderId = 200L;
        Long userId = 2L;

        OrderEntity legacyOrder = new OrderEntity();
        legacyOrder.setId(orderId);
        legacyOrder.setUserId(userId);

        UserEntity profileUser = new UserEntity();
        profileUser.setUserName("Jane");
        profileUser.setUserLastName("Smith");

        when(orderGroupEntityRepository.findById(orderId)).thenReturn(Optional.empty());
        when(orderEntityRepository.findById(orderId)).thenReturn(Optional.of(legacyOrder));
        when(userEntityRepository.findById(userId)).thenReturn(Optional.of(profileUser));

        UserProfileTableDto result = userOrderService.getUserProfileData(orderId);

        assertNotNull(result);
        assertEquals("Jane", result.getUserFirstName());
    }

    @Test
    void getUserProfileData_ThrowsOrderNotFound() {
        Long orderId = 999L;
        when(orderGroupEntityRepository.findById(orderId)).thenReturn(Optional.empty());
        when(orderEntityRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> userOrderService.getUserProfileData(orderId));
        verify(userEntityRepository, never()).findById(anyLong());
    }

    @Test
    void getUserProfileData_ThrowsUserNotFound() {
        Long orderId = 100L;
        Long userId = 1L;

        OrderGroupEntity group = new OrderGroupEntity();
        group.setUserId(userId);

        when(orderGroupEntityRepository.findById(orderId)).thenReturn(Optional.of(group));
        when(userEntityRepository.findById(userId)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> userOrderService.getUserProfileData(orderId));
    }

    @Test
    void deleteOrder_shouldDeleteGroupAndItems_whenGroupExists() {
        Long orderId = 10L;
        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(orderId);

        OrderEntity item1 = new OrderEntity();
        item1.setId(1L);
        OrderEntity item2 = new OrderEntity();
        item2.setId(2L);

        when(orderGroupEntityRepository.findById(orderId)).thenReturn(Optional.of(group));
        when(orderEntityRepository.findByOrderGroup_Id(orderId)).thenReturn(List.of(item1, item2));

        userOrderService.deleteOrder(orderId);

        verify(orderEntityRepository).deleteAll(List.of(item1, item2));
        verify(orderGroupEntityRepository).delete(group);
        verify(orderEntityRepository, never()).delete(any());
    }

    @Test
    void deleteOrder_shouldDeleteLegacyOrder_whenGroupMissing() {
        Long orderId = 20L;
        OrderEntity legacy = new OrderEntity();
        legacy.setId(orderId);

        when(orderGroupEntityRepository.findById(orderId)).thenReturn(Optional.empty());
        when(orderEntityRepository.findById(orderId)).thenReturn(Optional.of(legacy));

        userOrderService.deleteOrder(orderId);

        verify(orderEntityRepository).delete(legacy);
        verify(orderGroupEntityRepository, never()).delete(any());
        verify(orderEntityRepository, never()).deleteAll(any());
    }

    @Test
    void deleteOrder_shouldThrowOrderNotFound_whenMissing() {
        Long orderId = 30L;

        when(orderGroupEntityRepository.findById(orderId)).thenReturn(Optional.empty());
        when(orderEntityRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> userOrderService.deleteOrder(orderId));
        verify(orderEntityRepository, never()).delete(any());
        verify(orderEntityRepository, never()).deleteAll(any());
        verify(orderGroupEntityRepository, never()).delete(any());
    }

}

