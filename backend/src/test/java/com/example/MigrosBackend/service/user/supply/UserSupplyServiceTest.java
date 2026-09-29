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
import com.example.MigrosBackend.entity.product.ProductImageEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.shared.FileNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductDescriptionEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.FileService;
import com.example.MigrosBackend.service.global.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
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

    @Mock
    private FileService fileService;

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

        userSupplyService = new UserSupplyService(
                userEntityRepository,
                tokenService,
                orderEntityRepository,
                orderGroupEntityRepository,
                new UserCatalogReadService(
                        categoryEntityRepository,
                        productEntityRepository,
                        productImageEntityRepository,
                        productDescriptionEntityRepository,
                        fileService),
                new UserOrderHistoryReadService(
                        userEntityRepository,
                        tokenService,
                        orderEntityRepository,
                        orderGroupEntityRepository,
                        productEntityRepository),
                new OrderStockRestocker(productEntityRepository));
    }

    private void stubAuthenticatedUser() {
        when(tokenService.validateAndExtractUser(TOKEN)).thenReturn(USER_MAIL);
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
    }

    @Test
    void getProductsFromCategory_ShouldReturnOnlyInStockProducts() {
        ProductEntity product = new ProductEntity();
        product.setId(10L);
        product.setProductName("Milk");
        product.setProductPrice(new BigDecimal("100"));
        product.setProductDiscount(new BigDecimal("10"));
        product.setProductCount(4);

        when(categoryEntityRepository.existsById(1L)).thenReturn(true);
        when(productEntityRepository.findByCategoryEntityIdAndProductCountGreaterThan(1L, 0, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(product)));

        List<ProductPreviewDto> results = userSupplyService.getProductsFromCategory(1L, 0, 10);

        assertEquals(1, results.size());
        assertEquals(4, results.get(0).getProductCount());
        assertEquals(0, new BigDecimal("90").compareTo(results.get(0).getProductPrice()));
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
    void getAllProducts_ShouldReturnOnlyInStockProductsAcrossCategories() {
        ProductEntity product = new ProductEntity();
        product.setId(10L);
        product.setProductName("Milk");
        product.setProductPrice(new BigDecimal("100"));
        product.setProductDiscount(new BigDecimal("10"));
        product.setProductCount(4);

        when(productEntityRepository.findByProductCountGreaterThan(0, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(product)));

        List<ProductPreviewDto> results = userSupplyService.getAllProducts(0, 10);

        assertEquals(1, results.size());
        assertEquals(4, results.get(0).getProductCount());
        assertEquals(0, new BigDecimal("90").compareTo(results.get(0).getProductPrice()));
    }

    @Test
    void getAllProductCounts_ShouldUseInStockCount() {
        when(productEntityRepository.countByProductCountGreaterThan(0)).thenReturn(7);

        int result = userSupplyService.getAllProductCounts();

        assertEquals(7, result);
    }

    @Test
    void cancelOrder_LocksTheGroupBeforeItsStatusAndRestocksAtomicallyInProductOrder() {
        stubAuthenticatedUser();
        OrderEntity orderB = new OrderEntity();
        orderB.setItemId(12L);
        orderB.setCount(1);

        OrderEntity orderA = new OrderEntity();
        orderA.setItemId(11L);
        orderA.setCount(2);

        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(90L);
        group.setStatus("Pending");
        group.setOrderItems(new ArrayList<>(List.of(orderB, orderA)));

        when(orderGroupEntityRepository.findByIdAndUserIdForUpdate(90L, user.getId()))
                .thenReturn(Optional.of(group));

        userSupplyService.cancelOrder(90L, TOKEN);

        InOrder restockOrder = inOrder(productEntityRepository);
        restockOrder.verify(productEntityRepository).incrementStock(11L, 2);
        restockOrder.verify(productEntityRepository).incrementStock(12L, 1);
        verify(productEntityRepository, never()).findById(any());
        verify(productEntityRepository, never()).save(any());
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

        when(orderGroupEntityRepository.findByIdAndUserIdForUpdate(91L, user.getId()))
                .thenReturn(Optional.of(group));

        assertThrows(GeneralException.class, () -> userSupplyService.cancelOrder(91L, TOKEN));
        verify(productEntityRepository, never()).incrementStock(any(), anyInt());
        verify(orderEntityRepository, never()).deleteAll(any());
        verify(orderGroupEntityRepository, never()).delete(any());
    }

    @Test
    void cancelOrder_LocksAndRestocksALegacyOrder() {
        stubAuthenticatedUser();
        OrderEntity legacyOrder = new OrderEntity();
        legacyOrder.setId(92L);
        legacyOrder.setItemId(11L);
        legacyOrder.setCount(2);
        legacyOrder.setStatus("Pending");

        when(orderGroupEntityRepository.findByIdAndUserIdForUpdate(92L, user.getId()))
                .thenReturn(Optional.empty());
        when(orderEntityRepository.findByIdAndUserIdAndOrderGroupIsNullForUpdate(92L, user.getId()))
                .thenReturn(Optional.of(legacyOrder));

        userSupplyService.cancelOrder(92L, TOKEN);

        verify(productEntityRepository).incrementStock(11L, 2);
        verify(orderEntityRepository).delete(legacyOrder);
    }

    @Test
    void cancelOrder_ThrowsWhenTheCallerDoesNotOwnTheOrder() {
        stubAuthenticatedUser();

        when(orderGroupEntityRepository.findByIdAndUserIdForUpdate(93L, user.getId()))
                .thenReturn(Optional.empty());
        when(orderEntityRepository.findByIdAndUserIdAndOrderGroupIsNullForUpdate(93L, user.getId()))
                .thenReturn(Optional.empty());

        assertThrows(GeneralException.class, () -> userSupplyService.cancelOrder(93L, TOKEN));
        verify(productEntityRepository, never()).incrementStock(any(), anyInt());
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
        assertEquals(100L, result.getDescriptionList().get(0).descriptionId());
        assertEquals("Ingredients", result.getDescriptionList().get(0).tabName());
        assertEquals("Milk, sugar", result.getDescriptionList().get(0).tabContent());
        assertEquals(101L, result.getDescriptionList().get(1).descriptionId());
        assertEquals("Storage", result.getDescriptionList().get(1).tabName());
    }

    @Test
    void getProductDescription_ShouldReturnEmptyList_WhenNoDescriptionsExist() {
        when(productDescriptionEntityRepository.findByProductEntityId(56L)).thenReturn(List.of());

        ProductDescriptionListDto result = userSupplyService.getProductDescription(56L);

        assertEquals(56L, result.getProductId());
        assertEquals(0, result.getDescriptionList().size());
    }

    @Test
    void getProductImage_ThrowsFileNotFoundException_WhenNoImageRowExists() {
        when(productImageEntityRepository.findByProductEntityId(77L)).thenReturn(List.of());

        assertThrows(FileNotFoundException.class, () -> userSupplyService.getProductImage(77L));
    }

    @Test
    void getProductImage_ThrowsFileNotFoundException_WhenImageFileIsMissing() {
        ProductImageEntity image = new ProductImageEntity();
        image.setId(1L);
        image.setImagePath("missing-image.png");
        when(productImageEntityRepository.findByProductEntityId(78L)).thenReturn(List.of(image));
        when(fileService.resolveImagePath("missing-image.png"))
                .thenReturn(java.nio.file.Paths.get("definitely-not-a-real-file-404.png"));

        assertThrows(FileNotFoundException.class, () -> userSupplyService.getProductImage(78L));
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
        groupedOrder.setPrice(new BigDecimal("12.5"));
        groupedOrder.setTotalPrice(new BigDecimal("25"));

        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(900L);
        group.setStatus("Delivered");
        group.setOrderItems(List.of(groupedOrder));

        OrderEntity legacyOrder = new OrderEntity();
        legacyOrder.setId(202L);
        legacyOrder.setItemId(5002L);
        legacyOrder.setCount(1);
        legacyOrder.setPrice(new BigDecimal("5"));
        legacyOrder.setTotalPrice(new BigDecimal("5"));
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
        assertEquals(2, first.getCount());
        assertEquals(0, new BigDecimal("12.5").compareTo(first.getPrice()));
        assertEquals(0, new BigDecimal("25").compareTo(first.getTotalPrice()));
        assertEquals("Delivered", first.getStatus());

        UserOrderDetailDto second = result.get(1);
        assertEquals(202L, second.getOrderId());
        assertEquals(5002L, second.getProductId());
        assertEquals("", second.getProductName());
        assertEquals(1, second.getCount());
        assertEquals(0, new BigDecimal("5").compareTo(second.getPrice()));
        assertEquals(0, new BigDecimal("5").compareTo(second.getTotalPrice()));
        assertEquals("Pending", second.getStatus());

        InOrder repositoryOrder = inOrder(tokenService, userEntityRepository, orderGroupEntityRepository,
                orderEntityRepository, productEntityRepository);
        repositoryOrder.verify(tokenService).validateAndExtractUser(TOKEN);
        repositoryOrder.verify(userEntityRepository).findByUserMail(USER_MAIL);
        repositoryOrder.verify(orderGroupEntityRepository).findByUserId(user.getId());
        repositoryOrder.verify(orderEntityRepository).findByUserIdAndOrderGroupIsNull(user.getId());
        repositoryOrder.verify(productEntityRepository).findAllById(List.of(5001L, 5002L));
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
        groupOrder.setPrice(new BigDecimal("8"));
        groupOrder.setTotalPrice(new BigDecimal("8"));

        OrderGroupEntity orderGroup = new OrderGroupEntity();
        orderGroup.setId(10L);
        orderGroup.setStatus("Shipped");
        orderGroup.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
        orderGroup.setOrderItems(List.of(groupOrder));

        OrderEntity legacyOrder = new OrderEntity();
        legacyOrder.setId(20L);
        legacyOrder.setItemId(7002L);
        legacyOrder.setCount(3);
        legacyOrder.setPrice(new BigDecimal("2"));
        legacyOrder.setTotalPrice(new BigDecimal("6"));
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
        assertEquals(3, legacyGroup.getItems().get(0).getCount());
        assertEquals(0, new BigDecimal("2").compareTo(legacyGroup.getItems().get(0).getPrice()));
        assertEquals(0, new BigDecimal("6").compareTo(legacyGroup.getItems().get(0).getTotalPrice()));
        assertEquals("Pending", legacyGroup.getItems().get(0).getStatus());

        UserOrderGroupDto grouped = result.get(1);
        assertEquals(LocalDateTime.of(2026, 1, 1, 12, 0), grouped.getCreatedAt());
        assertEquals(1, grouped.getItems().size());
        assertEquals("Bread", grouped.getItems().get(0).getProductName());
        assertEquals(1, grouped.getItems().get(0).getCount());
        assertEquals(0, new BigDecimal("8").compareTo(grouped.getItems().get(0).getPrice()));
        assertEquals(0, new BigDecimal("8").compareTo(grouped.getItems().get(0).getTotalPrice()));
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
        verify(orderEntityRepository, never()).findByIdAndUserIdAndOrderGroupIsNull(any(), any());
    }

    @Test
    void getOrderStatusByOrderId_ShouldReturnLegacyStatus_WhenGroupMissing() {
        stubAuthenticatedUser();

        OrderEntity legacyOrder = new OrderEntity();
        legacyOrder.setId(501L);
        legacyOrder.setStatus("Delivered");

        when(orderGroupEntityRepository.findByIdAndUserId(501L, user.getId())).thenReturn(Optional.empty());
        when(orderEntityRepository.findByIdAndUserIdAndOrderGroupIsNull(501L, user.getId())).thenReturn(Optional.of(legacyOrder));

        String result = userSupplyService.getOrderStatusByOrderId(501L, TOKEN);

        assertEquals("Delivered", result);
    }

    @Test
    void getOrderStatusByOrderId_ShouldThrow_WhenOrderNotFoundInBothSources() {
        stubAuthenticatedUser();

        when(orderGroupEntityRepository.findByIdAndUserId(999L, user.getId())).thenReturn(Optional.empty());
        when(orderEntityRepository.findByIdAndUserIdAndOrderGroupIsNull(999L, user.getId())).thenReturn(Optional.empty());

        assertThrows(GeneralException.class, () -> userSupplyService.getOrderStatusByOrderId(999L, TOKEN));
    }
}

