package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.user.order.UserOrderDetailDto;
import com.example.MigrosBackend.dto.user.order.UserOrderGroupDto;
import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductDescriptionEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.ProductNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductDescriptionEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
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
class UserSupplyServiceTest {
    @Mock
    private CategoryEntityRepository categoryEntityRepository;

    @Mock
    private ProductEntityRepository productEntityRepository;

    @Mock
    private ProductImageEntityRepository productImageEntityRepository;

    @Mock
    private UserEntityRepository userEntityRepository;

    @Mock
    private TokenService tokenService;

    @Mock
    private OrderEntityRepository orderEntityRepository;

    @Mock
    private OrderGroupEntityRepository orderGroupEntityRepository;

    @Mock
    private ProductDescriptionEntityRepository productDescriptionEntityRepository;

    @InjectMocks
    private UserSupplyService userSupplyService;

    private static final String TOKEN = "valid-token";
    private static final String USER_MAIL = "user@migros.com";

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        user.setId(1L);
        user.setUserMail(USER_MAIL);
        user.setProductsIdsInCart(new ArrayList<>());
    }

    private void stubAuthenticatedUser() {
        when(tokenService.extractUsername(TOKEN)).thenReturn(USER_MAIL);
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        when(tokenService.validateToken(TOKEN, USER_MAIL)).thenReturn(true);
    }

    @Test
    void getProductsFromCategory_ShouldReturnOnlyInStockProducts() {
        ProductEntity product = new ProductEntity();
        product.setId(10L);
        product.setProductName("Milk");
        product.setProductPrice(100f);
        product.setProductDiscount(10f);
        product.setProductCount(4);

        when(categoryEntityRepository.existsById(1L)).thenReturn(true);
        when(productEntityRepository.findByCategoryEntityIdAndProductCountGreaterThan(1L, 0, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(product)));

        List<ProductPreviewDto> results = userSupplyService.getProductsFromCategory(1L, 0, 10);

        assertEquals(1, results.size());
        assertEquals(4, results.get(0).getProductCount());
        assertEquals(90f, results.get(0).getProductPrice());
    }

    @Test
    void addProductToInventory_ShouldThrow_WhenCartAlreadyAtStockLimit() {
        stubAuthenticatedUser();
        user.setProductsIdsInCart(new ArrayList<>(List.of(20L, 20L)));

        ProductEntity product = new ProductEntity();
        product.setId(20L);
        product.setProductName("Apple");
        product.setProductCount(2);

        when(productEntityRepository.findById(20L)).thenReturn(Optional.of(product));

        assertThrows(GeneralException.class, () -> userSupplyService.addProductToInventory(20L, TOKEN));
        verify(userEntityRepository, never()).save(any());
    }

    @Test
    void updateProductCountInInventory_ShouldThrow_WhenCountExceedsStock() {
        stubAuthenticatedUser();
        ProductEntity product = new ProductEntity();
        product.setId(25L);
        product.setProductCount(3);

        when(productEntityRepository.findById(25L)).thenReturn(Optional.of(product));

        assertThrows(GeneralException.class, () -> userSupplyService.updateProductCountInInventory(25L, 4, TOKEN));
        verify(userEntityRepository, never()).save(any());
    }

    @Test
    void getProductData_ShouldNormalizeCartToAvailableStock() {
        stubAuthenticatedUser();
        user.setProductsIdsInCart(new ArrayList<>(List.of(1L, 1L, 1L, 2L)));

        ProductEntity inStock = new ProductEntity();
        inStock.setId(1L);
        inStock.setProductName("Apple");
        inStock.setProductPrice(12f);
        inStock.setProductDiscount(50f);
        inStock.setProductCount(2);

        ProductEntity soldOut = new ProductEntity();
        soldOut.setId(2L);
        soldOut.setProductName("Orange");
        soldOut.setProductPrice(7f);
        soldOut.setProductCount(0);

        when(productEntityRepository.findAllById(any())).thenReturn(List.of(inStock, soldOut));

        List<UserCartItemDto> result = userSupplyService.getProductData(TOKEN);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getProductId());
        assertEquals(2, result.get(0).getProductCount());
        assertEquals(6f, result.get(0).getProductPrice());
        assertEquals(2, result.get(0).getAvailableStock());
        verify(userEntityRepository, times(1)).save(user);
    }

    @Test
    void getSubCategories_ShouldCountOnlyProductsWithStock() {
        CategoryEntity category = new CategoryEntity();
        category.setId(9L);

        ProductEntity fruitsInStock = new ProductEntity();
        fruitsInStock.setSubcategoryName("Fruits");
        fruitsInStock.setProductCount(2);

        ProductEntity fruitsSoldOut = new ProductEntity();
        fruitsSoldOut.setSubcategoryName("Fruits");
        fruitsSoldOut.setProductCount(0);

        ProductEntity dairyInStock = new ProductEntity();
        dairyInStock.setSubcategoryName("Dairy");
        dairyInStock.setProductCount(1);

        category.setItemEntities(List.of(fruitsInStock, fruitsSoldOut, dairyInStock));
        when(categoryEntityRepository.findById(9L)).thenReturn(Optional.of(category));

        List<SubCategoryDto> result = userSupplyService.getSubCategories(9L);

        assertEquals(2, result.size());
        SubCategoryDto fruits = result.stream()
                .filter(item -> "Fruits".equals(item.getSubCategoryName()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, fruits.getProductCount());
    }

    @Test
    void getProductCountsFromCategory_ShouldUseInStockCount() {
        when(categoryEntityRepository.existsById(3L)).thenReturn(true);
        when(productEntityRepository.countByCategoryEntityIdAndProductCountGreaterThan(3L, 0)).thenReturn(7);

        int result = userSupplyService.getProductCountsFromCategory(3L);

        assertEquals(7, result);
    }

    @Test
    void cancelOrder_ShouldRestockAndDeleteGroup_WhenPending() {
        stubAuthenticatedUser();
        OrderEntity orderA = new OrderEntity();
        orderA.setItemId(11L);
        orderA.setCount(2);

        OrderEntity orderB = new OrderEntity();
        orderB.setItemId(12L);
        orderB.setCount(1);

        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(90L);
        group.setStatus("Pending");
        group.setOrderItems(new ArrayList<>(List.of(orderA, orderB)));

        ProductEntity productA = new ProductEntity();
        productA.setId(11L);
        productA.setProductCount(0);

        ProductEntity productB = new ProductEntity();
        productB.setId(12L);
        productB.setProductCount(3);

        when(orderGroupEntityRepository.findByIdAndUserId(90L, user.getId())).thenReturn(Optional.of(group));
        when(productEntityRepository.findById(11L)).thenReturn(Optional.of(productA));
        when(productEntityRepository.findById(12L)).thenReturn(Optional.of(productB));

        userSupplyService.cancelOrder(90L, TOKEN);

        assertEquals(2, productA.getProductCount());
        assertEquals(4, productB.getProductCount());
        verify(orderEntityRepository, times(1)).deleteAll(any());
        verify(orderGroupEntityRepository, times(1)).delete(group);
    }

    @Test
    void cancelOrder_ShouldThrow_WhenOrderIsNotPending() {
        stubAuthenticatedUser();
        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(91L);
        group.setStatus("Delivered");
        group.setOrderItems(new ArrayList<>());

        when(orderGroupEntityRepository.findByIdAndUserId(91L, user.getId())).thenReturn(Optional.of(group));

        assertThrows(GeneralException.class, () -> userSupplyService.cancelOrder(91L, TOKEN));
        verify(orderEntityRepository, never()).deleteAll(any());
        verify(orderGroupEntityRepository, never()).delete(any());
    }
    @Test
    void getProductDescription_ShouldMapAllDescriptions() {
        ProductDescriptionEntity first = new ProductDescriptionEntity();
        first.setId(100L);
        first.setDescriptionTabName("Ingredients");
        first.setDescriptionTabContent("Milk, sugar");

        ProductDescriptionEntity second = new ProductDescriptionEntity();
        second.setId(101L);
        second.setDescriptionTabName("Storage");
        second.setDescriptionTabContent("Keep refrigerated");

        when(productDescriptionEntityRepository.findByProductEntityId(55L)).thenReturn(List.of(first, second));

        ProductDescriptionListDto result = userSupplyService.getProductDescription(55L);

        assertEquals(55L, result.getProductId());
        assertEquals(2, result.getDescriptionList().size());
        assertEquals(100L, result.getDescriptionList().get(0).getDescriptionId());
        assertEquals("Ingredients", result.getDescriptionList().get(0).getDescriptionTabName());
        assertEquals("Milk, sugar", result.getDescriptionList().get(0).getDescriptionTabContent());
        assertEquals(101L, result.getDescriptionList().get(1).getDescriptionId());
        assertEquals("Storage", result.getDescriptionList().get(1).getDescriptionTabName());
    }

    @Test
    void getProductDescription_ShouldReturnEmptyList_WhenNoDescriptionsExist() {
        when(productDescriptionEntityRepository.findByProductEntityId(56L)).thenReturn(List.of());

        ProductDescriptionListDto result = userSupplyService.getProductDescription(56L);

        assertEquals(56L, result.getProductId());
        assertEquals(0, result.getDescriptionList().size());
    }

    @Test
    void getProductDescription_ShouldThrow_WhenRepositoryReturnsNull() {
        when(productDescriptionEntityRepository.findByProductEntityId(57L)).thenReturn(null);

        assertThrows(ProductNotFoundException.class, () -> userSupplyService.getProductDescription(57L));
    }

    @Test
    void getUserOrderDetails_ShouldReturnEmpty_WhenUserHasNoOrders() {
        stubAuthenticatedUser();
        when(orderGroupEntityRepository.findByUserId(user.getId())).thenReturn(List.of());
        when(orderEntityRepository.findByUserIdAndOrderGroupIsNull(user.getId())).thenReturn(List.of());

        List<UserOrderDetailDto> result = userSupplyService.getUserOrderDetails(TOKEN);

        assertEquals(0, result.size());
        verify(productEntityRepository, never()).findAllById(any());
    }

    @Test
    void getUserOrderDetails_ShouldIncludeGroupedAndLegacyOrders_AndFallbackMissingProductName() {
        stubAuthenticatedUser();

        OrderEntity groupedOrder = new OrderEntity();
        groupedOrder.setId(201L);
        groupedOrder.setItemId(5001L);
        groupedOrder.setCount(2);
        groupedOrder.setPrice(12.5f);
        groupedOrder.setTotalPrice(25f);

        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(900L);
        group.setStatus("Delivered");
        group.setOrderItems(List.of(groupedOrder));

        OrderEntity legacyOrder = new OrderEntity();
        legacyOrder.setId(202L);
        legacyOrder.setItemId(5002L);
        legacyOrder.setCount(1);
        legacyOrder.setPrice(5f);
        legacyOrder.setTotalPrice(5f);
        legacyOrder.setStatus("Pending");

        ProductEntity existingProduct = new ProductEntity();
        existingProduct.setId(5001L);
        existingProduct.setProductName("Yogurt");

        when(orderGroupEntityRepository.findByUserId(user.getId())).thenReturn(List.of(group));
        when(orderEntityRepository.findByUserIdAndOrderGroupIsNull(user.getId())).thenReturn(List.of(legacyOrder));
        when(productEntityRepository.findAllById(any())).thenReturn(List.of(existingProduct));

        List<UserOrderDetailDto> result = userSupplyService.getUserOrderDetails(TOKEN);

        assertEquals(2, result.size());

        UserOrderDetailDto first = result.get(0);
        assertEquals(201L, first.getOrderId());
        assertEquals(5001L, first.getProductId());
        assertEquals("Yogurt", first.getProductName());
        assertEquals("Delivered", first.getStatus());

        UserOrderDetailDto second = result.get(1);
        assertEquals(202L, second.getOrderId());
        assertEquals(5002L, second.getProductId());
        assertEquals("", second.getProductName());
        assertEquals("Pending", second.getStatus());
    }

    @Test
    void getUserOrderGroups_ShouldReturnEmpty_WhenUserHasNoOrders() {
        stubAuthenticatedUser();
        when(orderGroupEntityRepository.findByUserId(user.getId())).thenReturn(List.of());
        when(orderEntityRepository.findByUserIdAndOrderGroupIsNull(user.getId())).thenReturn(List.of());

        List<UserOrderGroupDto> result = userSupplyService.getUserOrderGroups(TOKEN);

        assertEquals(0, result.size());
        verify(productEntityRepository, never()).findAllById(any());
    }

    @Test
    void getUserOrderGroups_ShouldSortDesc_AndMapGroupAndLegacyItems() {
        stubAuthenticatedUser();

        OrderEntity groupOrder = new OrderEntity();
        groupOrder.setId(3001L);
        groupOrder.setItemId(7001L);
        groupOrder.setCount(1);
        groupOrder.setPrice(8f);
        groupOrder.setTotalPrice(8f);

        OrderGroupEntity orderGroup = new OrderGroupEntity();
        orderGroup.setId(10L);
        orderGroup.setStatus("Shipped");
        orderGroup.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
        orderGroup.setOrderItems(List.of(groupOrder));

        OrderEntity legacyOrder = new OrderEntity();
        legacyOrder.setId(20L);
        legacyOrder.setItemId(7002L);
        legacyOrder.setCount(3);
        legacyOrder.setPrice(2f);
        legacyOrder.setTotalPrice(6f);
        legacyOrder.setStatus("Pending");

        ProductEntity product = new ProductEntity();
        product.setId(7001L);
        product.setProductName("Bread");

        when(orderGroupEntityRepository.findByUserId(user.getId())).thenReturn(List.of(orderGroup));
        when(orderEntityRepository.findByUserIdAndOrderGroupIsNull(user.getId())).thenReturn(List.of(legacyOrder));
        when(productEntityRepository.findAllById(any())).thenReturn(List.of(product));

        List<UserOrderGroupDto> result = userSupplyService.getUserOrderGroups(TOKEN);

        assertEquals(2, result.size());
        assertEquals(20L, result.get(0).getOrderGroupId());
        assertEquals(10L, result.get(1).getOrderGroupId());

        UserOrderGroupDto legacyGroup = result.get(0);
        assertEquals(null, legacyGroup.getCreatedAt());
        assertEquals(1, legacyGroup.getItems().size());
        assertEquals("", legacyGroup.getItems().get(0).getProductName());
        assertEquals("Pending", legacyGroup.getItems().get(0).getStatus());

        UserOrderGroupDto grouped = result.get(1);
        assertEquals(LocalDateTime.of(2026, 1, 1, 12, 0), grouped.getCreatedAt());
        assertEquals(1, grouped.getItems().size());
        assertEquals("Bread", grouped.getItems().get(0).getProductName());
        assertEquals("Shipped", grouped.getItems().get(0).getStatus());
    }
    @Test
    void getAllOrderIds_ShouldMergeAndDeduplicateGroupAndLegacyIds() {
        stubAuthenticatedUser();

        OrderGroupEntity groupA = new OrderGroupEntity();
        groupA.setId(11L);
        OrderGroupEntity groupB = new OrderGroupEntity();
        groupB.setId(12L);

        OrderEntity legacyA = new OrderEntity();
        legacyA.setId(12L);
        OrderEntity legacyB = new OrderEntity();
        legacyB.setId(13L);

        when(orderGroupEntityRepository.findByUserId(user.getId())).thenReturn(List.of(groupA, groupB));
        when(orderEntityRepository.findByUserIdAndOrderGroupIsNull(user.getId())).thenReturn(List.of(legacyA, legacyB));

        List<Long> result = userSupplyService.getAllOrderIds(TOKEN);

        assertEquals(List.of(11L, 12L, 13L), result);
    }

    @Test
    void getAllOrderIds_ShouldReturnEmpty_WhenUserHasNoOrderGroupsAndNoLegacyOrders() {
        stubAuthenticatedUser();
        when(orderGroupEntityRepository.findByUserId(user.getId())).thenReturn(List.of());
        when(orderEntityRepository.findByUserIdAndOrderGroupIsNull(user.getId())).thenReturn(List.of());

        List<Long> result = userSupplyService.getAllOrderIds(TOKEN);

        assertEquals(0, result.size());
    }

    @Test
    void getOrderStatusByOrderId_ShouldPreferGroupStatus_WhenGroupExists() {
        stubAuthenticatedUser();

        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(500L);
        group.setStatus("Pending");

        when(orderGroupEntityRepository.findByIdAndUserId(500L, user.getId())).thenReturn(Optional.of(group));

        String result = userSupplyService.getOrderStatusByOrderId(500L, TOKEN);

        assertEquals("Pending", result);
        verify(orderEntityRepository, never()).findByIdAndUserId(any(), any());
    }

    @Test
    void getOrderStatusByOrderId_ShouldReturnLegacyStatus_WhenGroupMissing() {
        stubAuthenticatedUser();

        OrderEntity legacyOrder = new OrderEntity();
        legacyOrder.setId(501L);
        legacyOrder.setStatus("Delivered");

        when(orderGroupEntityRepository.findByIdAndUserId(501L, user.getId())).thenReturn(Optional.empty());
        when(orderEntityRepository.findByIdAndUserId(501L, user.getId())).thenReturn(Optional.of(legacyOrder));

        String result = userSupplyService.getOrderStatusByOrderId(501L, TOKEN);

        assertEquals("Delivered", result);
    }

    @Test
    void getOrderStatusByOrderId_ShouldThrow_WhenOrderNotFoundInBothSources() {
        stubAuthenticatedUser();

        when(orderGroupEntityRepository.findByIdAndUserId(999L, user.getId())).thenReturn(Optional.empty());
        when(orderEntityRepository.findByIdAndUserId(999L, user.getId())).thenReturn(Optional.empty());

        assertThrows(GeneralException.class, () -> userSupplyService.getOrderStatusByOrderId(999L, TOKEN));
    }
}

