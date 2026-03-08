package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
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
}
