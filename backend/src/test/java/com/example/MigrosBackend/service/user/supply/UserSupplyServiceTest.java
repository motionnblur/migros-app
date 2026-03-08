package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.admin.panel.DescriptionsDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDto2;
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
import com.example.MigrosBackend.exception.admin.ProductNotFoundException;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.shared.InvalidTokenException;
import com.example.MigrosBackend.exception.shared.TokenNotFoundException;
import com.example.MigrosBackend.exception.user.CategoryNotFoundException;
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
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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

    private UserEntity mockUser;
    private final String testToken = "valid-token";
    private final String testEmail = "user@migros.com";

    @BeforeEach
    void setUp() {
        mockUser = new UserEntity();
        mockUser.setId(1L);
        mockUser.setUserMail(testEmail);
        mockUser.setProductsIdsInCart(new ArrayList<>(List.of(100L)));
    }

    @Test
    void getProductsFromCategory_ShouldCalculateDiscountCorrectly() {
        Long catId = 1L;
        ProductEntity product = new ProductEntity();
        product.setId(10L);
        product.setProductName("Milk");
        product.setProductPrice(100.0f);
        product.setProductDiscount(20.0f);

        Page<ProductEntity> page = new PageImpl<>(List.of(product));

        when(categoryEntityRepository.existsById(catId)).thenReturn(true);
        when(productEntityRepository.findByCategoryEntityId(eq(catId), any())).thenReturn(page);

        List<ProductPreviewDto> results = userSupplyService.getProductsFromCategory(catId, 0, 10);

        assertEquals(80.0f, results.get(0).getProductPrice());
    }

    @Test
    void getProductsFromCategory_ShouldThrowException_WhenCategoryMissing() {
        when(categoryEntityRepository.existsById(1L)).thenReturn(false);

        assertThrows(CategoryNotFoundException.class, () ->
                userSupplyService.getProductsFromCategory(1L, 0, 10));
    }

    @Test
    void addProductToInventory_ShouldUpdateUserCart() {
        when(tokenService.extractUsername(testToken)).thenReturn(testEmail);
        when(userEntityRepository.findByUserMail(testEmail)).thenReturn(mockUser);

        userSupplyService.addProductToInventory(200L, testToken);

        assertTrue(mockUser.getProductsIdsInCart().contains(200L));
        assertEquals(2, mockUser.getProductsIdsInCart().size());

        verify(userEntityRepository).save(mockUser);
    }

    @Test
    void addProductToInventory_ShouldThrowTokenNotFound_WhenTokenMissing() {
        when(tokenService.extractUsername(testToken)).thenReturn(null);

        assertThrows(TokenNotFoundException.class, () -> userSupplyService.addProductToInventory(200L, testToken));
    }

    @Test
    void removeProductFromInventory_ShouldRemoveAllInstancesOfProduct() {
        mockUser.setProductsIdsInCart(new ArrayList<>(List.of(100L, 100L, 200L)));
        when(tokenService.extractUsername(testToken)).thenReturn(testEmail);
        when(userEntityRepository.findByUserMail(testEmail)).thenReturn(mockUser);
        when(tokenService.validateToken(testToken, testEmail)).thenReturn(true);

        userSupplyService.removeProductFromInventory(100L, testToken);

        assertFalse(mockUser.getProductsIdsInCart().contains(100L));
        assertEquals(1, mockUser.getProductsIdsInCart().size());
    }

    @Test
    void updateProductCountInInventory_ShouldSetExactCount() {
        when(tokenService.extractUsername(testToken)).thenReturn(testEmail);
        when(userEntityRepository.findByUserMail(testEmail)).thenReturn(mockUser);
        when(tokenService.validateToken(testToken, testEmail)).thenReturn(true);

        userSupplyService.updateProductCountInInventory(100L, 3, testToken);

        long count = mockUser.getProductsIdsInCart().stream().filter(id -> id == 100L).count();
        assertEquals(3, count);
    }

    @Test
    void updateProductCountInInventory_ShouldThrowException_WhenCountIsNegative() {
        assertThrows(GeneralException.class, () ->
                userSupplyService.updateProductCountInInventory(100L, -1, testToken));
    }

    @Test
    void getProductImage_Success() throws IOException {
        Long itemId = 1L;
        Path tempFile = Files.createTempFile("test-image", ".png");
        String fakePath = tempFile.toString();

        ProductImageEntity imageEntity = new ProductImageEntity();
        imageEntity.setImagePath(fakePath);

        when(productImageEntityRepository.findByProductEntityId(itemId))
                .thenReturn(List.of(imageEntity));

        Resource result = userSupplyService.getProductImage(itemId);

        assertNotNull(result);
        assertTrue(result.exists());
        assertEquals(tempFile.toUri(), result.getURI());

        Files.deleteIfExists(tempFile);
    }

    @Test
    void getProductImage_ThrowsGeneralException_WhenFileDoesNotExist() {
        Long itemId = 2L;
        ProductImageEntity imageEntity = new ProductImageEntity();
        imageEntity.setImagePath("non/existent/path/image.png");

        when(productImageEntityRepository.findByProductEntityId(itemId))
                .thenReturn(List.of(imageEntity));

        GeneralException exception = assertThrows(GeneralException.class, () -> {
            userSupplyService.getProductImage(itemId);
        });

        assertEquals("Error while loading image", exception.getMessage());
    }

    @Test
    void getProductImage_ThrowsException_WhenNoImageInDatabase() {
        Long itemId = 3L;
        when(productImageEntityRepository.findByProductEntityId(itemId))
                .thenReturn(Collections.emptyList());

        assertThrows(IndexOutOfBoundsException.class, () -> {
            userSupplyService.getProductImage(itemId);
        });
    }

    @Test
    void getAllCategoryNames_ShouldReturnListOfNames() {
        CategoryEntity cat1 = new CategoryEntity();
        cat1.setCategoryName("Beverages");

        CategoryEntity cat2 = new CategoryEntity();
        cat2.setCategoryName("Dairy");

        List<CategoryEntity> mockCategories = List.of(cat1, cat2);

        when(categoryEntityRepository.findAll()).thenReturn(mockCategories);

        List<String> result = userSupplyService.getAllCategoryNames();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.contains("Beverages"));
        assertTrue(result.contains("Dairy"));

        verify(categoryEntityRepository, times(1)).findAll();
    }

    @Test
    void getAllCategoryNames_ShouldReturnEmptyList_WhenNoCategoriesExist() {
        when(categoryEntityRepository.findAll()).thenReturn(Collections.emptyList());

        List<String> result = userSupplyService.getAllCategoryNames();

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(categoryEntityRepository, times(1)).findAll();
    }

    @Test
    void getProductImageNames_ShouldReturnListOfPaths() {
        Long itemId = 10L;

        ProductImageEntity img1 = new ProductImageEntity();
        img1.setImagePath("/uploads/image1.png");

        ProductImageEntity img2 = new ProductImageEntity();
        img2.setImagePath("/uploads/image2.png");

        List<ProductImageEntity> mockImages = List.of(img1, img2);

        when(productImageEntityRepository.findByProductEntityId(itemId))
                .thenReturn(mockImages);

        List<String> result = userSupplyService.getProductImageNames(itemId);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("/uploads/image1.png", result.get(0));
        assertEquals("/uploads/image2.png", result.get(1));

        verify(productImageEntityRepository, times(1)).findByProductEntityId(itemId);
    }

    @Test
    void getProductImageNames_ShouldReturnEmptyList_WhenNoImagesFound() {
        Long itemId = 20L;
        when(productImageEntityRepository.findByProductEntityId(itemId))
                .thenReturn(Collections.emptyList());

        List<String> result = userSupplyService.getProductImageNames(itemId);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(productImageEntityRepository, times(1)).findByProductEntityId(itemId);
    }

    @Test
    void getProductCountsFromCategory_Success() {
        Long categoryId = 1L;
        int expectedCount = 15;

        when(categoryEntityRepository.existsById(categoryId)).thenReturn(true);
        when(productEntityRepository.countByCategoryEntityId(categoryId)).thenReturn(expectedCount);

        int result = userSupplyService.getProductCountsFromCategory(categoryId);

        assertEquals(expectedCount, result);

        verify(categoryEntityRepository, times(1)).existsById(categoryId);
        verify(productEntityRepository, times(1)).countByCategoryEntityId(categoryId);
    }

    @Test
    void getProductCountsFromCategory_ThrowsException_WhenCategoryNotFound() {
        Long categoryId = 99L;

        when(categoryEntityRepository.existsById(categoryId)).thenReturn(false);

        CategoryNotFoundException exception = assertThrows(CategoryNotFoundException.class, () -> {
            userSupplyService.getProductCountsFromCategory(categoryId);
        });

        assertTrue(exception.getMessage().contains(categoryId.toString()));

        verify(productEntityRepository, never()).countByCategoryEntityId(anyLong());
    }

    @Test
    void getSubCategories_ShouldGroupAndCountSubcategories() {
        Long categoryId = 1L;
        CategoryEntity category = new CategoryEntity();
        category.setId(categoryId);

        ProductEntity p1 = new ProductEntity();
        p1.setSubcategoryName("Fruits");

        ProductEntity p2 = new ProductEntity();
        p2.setSubcategoryName("Fruits");

        ProductEntity p3 = new ProductEntity();
        p3.setSubcategoryName("Vegetables");

        ProductEntity p4 = new ProductEntity();
        p4.setSubcategoryName("");

        category.setItemEntities(List.of(p1, p2, p3, p4));

        when(categoryEntityRepository.findById(categoryId)).thenReturn(Optional.of(category));

        List<SubCategoryDto> result = userSupplyService.getSubCategories(categoryId);

        assertNotNull(result);
        assertEquals(2, result.size());

        SubCategoryDto fruitsDto = result.stream()
                .filter(d -> d.getSubCategoryName().equals("Fruits")).findFirst().orElseThrow();
        SubCategoryDto veggiesDto = result.stream()
                .filter(d -> d.getSubCategoryName().equals("Vegetables")).findFirst().orElseThrow();

        assertEquals(2, fruitsDto.getProductCount());
        assertEquals(1, veggiesDto.getProductCount());
        assertEquals(categoryId, fruitsDto.getSubCategoryId());
    }

    @Test
    void getSubCategories_ShouldThrowException_WhenCategoryNotFound() {
        Long categoryId = 99L;
        when(categoryEntityRepository.findById(categoryId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> {
            userSupplyService.getSubCategories(categoryId);
        });
    }

    @Test
    void getProductsFromSubcategory_ShouldCalculateDiscountedPrices() {
        String subcat = "Snacks";
        int page = 0;
        int range = 10;
        Pageable pageable = PageRequest.of(page, range);

        ProductEntity p1 = new ProductEntity();
        p1.setId(1L);
        p1.setProductName("Chips");
        p1.setProductPrice(100.0f);
        p1.setProductDiscount(20.0f);

        ProductEntity p2 = new ProductEntity();
        p2.setId(2L);
        p2.setProductName("Water");
        p2.setProductPrice(10.0f);
        p2.setProductDiscount(0.0f);

        Page<ProductEntity> productPage = new PageImpl<>(List.of(p1, p2));
        when(productEntityRepository.findBySubcategoryName(subcat, pageable)).thenReturn(productPage);

        List<ProductPreviewDto> result = userSupplyService.getProductsFromSubcategory(subcat, page, range);

        assertEquals(2, result.size());

        ProductPreviewDto chips = result.get(0);
        assertEquals("Chips", chips.getProductName());
        assertEquals(80.0f, chips.getProductPrice(), 0.001);

        ProductPreviewDto water = result.get(1);
        assertEquals("Water", water.getProductName());
        assertEquals(10.0f, water.getProductPrice(), 0.001);

        verify(productEntityRepository).findBySubcategoryName(subcat, pageable);
    }

    @Test
    void getProductsFromSubcategory_ShouldReturnEmptyList_WhenNoProductsFound() {
        String subcat = "EmptyCat";
        Pageable pageable = PageRequest.of(0, 10);
        when(productEntityRepository.findBySubcategoryName(subcat, pageable)).thenReturn(Page.empty());

        List<ProductPreviewDto> result = userSupplyService.getProductsFromSubcategory(subcat, 0, 10);

        assertTrue(result.isEmpty());
    }

    @Test
    void getProductData_ShouldAggregateCartItemsCorrectly() {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setProductsIdsInCart(List.of(101L, 101L, 102L));

        when(userEntityRepository.findById(1L)).thenReturn(Optional.of(user));

        ProductEntity apple = new ProductEntity();
        apple.setId(101L);
        apple.setProductName("Apple");
        apple.setProductPrice(1.5f);

        ProductEntity milk = new ProductEntity();
        milk.setId(102L);
        milk.setProductName("Milk");
        milk.setProductPrice(3.0f);

        when(productEntityRepository.findAllById(anySet())).thenReturn(List.of(apple, milk));

        List<UserCartItemDto> result = userSupplyService.getProductData();

        assertNotNull(result);
        assertEquals(2, result.size());

        UserCartItemDto appleDto = result.stream()
                .filter(d -> d.getProductId().equals(101L)).findFirst().orElseThrow();
        assertEquals("Apple", appleDto.getProductName());
        assertEquals(2, appleDto.getProductCount());
        assertEquals(1.5f, appleDto.getProductPrice());

        UserCartItemDto milkDto = result.stream()
                .filter(d -> d.getProductId().equals(102L)).findFirst().orElseThrow();
        assertEquals(1, milkDto.getProductCount());

        verify(userEntityRepository).findById(1L);
        verify(productEntityRepository).findAllById(anySet());
    }

    @Test
    void getProductData_ShouldThrowException_WhenUserNotFound() {
        when(userEntityRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> userSupplyService.getProductData());
    }

    @Test
    void getProductData_Success() {
        Long productId = 50L;

        CategoryEntity category = new CategoryEntity();
        category.setId(5L);

        ProductEntity product = new ProductEntity();
        product.setId(productId);
        product.setProductName("Classic T-Shirt");
        product.setSubcategoryName("Apparel");
        product.setProductPrice(29.99f);
        product.setProductCount(100);
        product.setProductDiscount(10.0f);
        product.setProductDescription("A comfortable cotton shirt.");
        product.setCategoryEntity(category);

        when(productEntityRepository.findById(productId)).thenReturn(Optional.of(product));

        ProductDto2 result = userSupplyService.getProductData(productId);

        assertNotNull(result);
        assertEquals("Classic T-Shirt", result.getProductName());
        assertEquals("Apparel", result.getSubCategoryName());
        assertEquals(29.99f, result.getProductPrice());
        assertEquals(100, result.getProductCount());
        assertEquals(10.0f, result.getProductDiscount());
        assertEquals("A comfortable cotton shirt.", result.getProductDescription());
        assertEquals(5, result.getProductCategoryId());

        verify(productEntityRepository, times(1)).findById(productId);
    }

    @Test
    void getProductData_ThrowsProductNotFoundException() {
        Long productId = 999L;
        when(productEntityRepository.findById(productId)).thenReturn(Optional.empty());

        ProductNotFoundException exception = assertThrows(ProductNotFoundException.class, () -> {
            userSupplyService.getProductData(productId);
        });

        assertTrue(exception.getMessage().contains(productId.toString()));
    }

    @Test
    void getAllOrderIds_Success() {
        String token = "valid-token";
        String email = "user@example.com";

        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUserMail(email);

        OrderGroupEntity group1 = new OrderGroupEntity();
        group1.setId(500L);
        OrderGroupEntity group2 = new OrderGroupEntity();
        group2.setId(501L);

        OrderEntity legacy1 = new OrderEntity();
        legacy1.setId(600L);
        OrderEntity legacy2 = new OrderEntity();
        legacy2.setId(601L);

        when(tokenService.extractUsername(token)).thenReturn(email);
        when(tokenService.validateToken(token, email)).thenReturn(true);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);

        when(orderGroupEntityRepository.findByUserId(user.getId())).thenReturn(List.of(group1, group2));
        when(orderEntityRepository.findByUserIdAndOrderGroupIsNull(user.getId())).thenReturn(List.of(legacy1, legacy2));

        List<Long> result = userSupplyService.getAllOrderIds(token);

        assertNotNull(result);
        assertEquals(4, result.size());
        assertTrue(result.contains(500L));
        assertTrue(result.contains(601L));

        verify(tokenService).validateToken(token, email);
        verify(userEntityRepository).findByUserMail(email);
    }

    @Test
    void getAllOrderIds_ThrowsInvalidTokenException_WhenTokenIsInvalid() {
        String token = "invalid-token";
        String email = "user@example.com";

        UserEntity user = new UserEntity();
        user.setUserMail(email);

        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(false);

        assertThrows(InvalidTokenException.class, () -> {
            userSupplyService.getAllOrderIds(token);
        });

        verify(orderGroupEntityRepository, never()).findByUserId(anyLong());
        verify(orderEntityRepository, never()).findByUserIdAndOrderGroupIsNull(anyLong());
    }

    @Test
    void getOrderStatusByOrderId_Success_ForGroup() {
        Long orderId = 101L;
        String token = "valid-jwt-token";
        String email = "test@example.com";
        String expectedStatus = "SHIPPED";

        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUserMail(email);

        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(orderId);
        group.setStatus(expectedStatus);

        when(tokenService.extractUsername(token)).thenReturn(email);
        when(tokenService.validateToken(token, email)).thenReturn(true);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(orderGroupEntityRepository.findByIdAndUserId(orderId, user.getId())).thenReturn(Optional.of(group));

        String result = userSupplyService.getOrderStatusByOrderId(orderId, token);

        assertEquals(expectedStatus, result);
        verify(orderEntityRepository, never()).findByIdAndUserId(anyLong(), anyLong());
    }

    @Test
    void getOrderStatusByOrderId_ThrowsGeneralException_WhenOrderMissing() {
        Long orderId = 404L;
        String token = "valid-token";
        String email = "user@test.com";

        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUserMail(email);

        when(tokenService.extractUsername(token)).thenReturn(email);
        when(tokenService.validateToken(token, email)).thenReturn(true);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(orderGroupEntityRepository.findByIdAndUserId(orderId, user.getId())).thenReturn(Optional.empty());
        when(orderEntityRepository.findByIdAndUserId(orderId, user.getId())).thenReturn(Optional.empty());

        GeneralException exception = assertThrows(GeneralException.class, () -> {
            userSupplyService.getOrderStatusByOrderId(orderId, token);
        });

        assertEquals("Order not found", exception.getMessage());
    }

    @Test
    void getOrderStatusByOrderId_ThrowsInvalidTokenException_WhenTokenInvalid() {
        String token = "fake-token";
        String email = "user@test.com";
        UserEntity user = new UserEntity();
        user.setUserMail(email);

        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(false);

        assertThrows(InvalidTokenException.class, () -> {
            userSupplyService.getOrderStatusByOrderId(1L, token);
        });

        verify(orderGroupEntityRepository, never()).findByIdAndUserId(anyLong(), anyLong());
        verify(orderEntityRepository, never()).findByIdAndUserId(anyLong(), anyLong());
    }

    @Test
    void cancelOrder_Success_ForGroup() {
        Long orderId = 202L;
        String token = "valid-token";
        String email = "customer@example.com";

        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUserMail(email);

        OrderGroupEntity group = new OrderGroupEntity();
        group.setId(orderId);
        group.setOrderItems(new ArrayList<>(List.of(new OrderEntity(), new OrderEntity())));

        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(true);

        when(orderGroupEntityRepository.findByIdAndUserId(orderId, user.getId())).thenReturn(Optional.of(group));

        userSupplyService.cancelOrder(orderId, token);

        assertTrue(group.getOrderItems().isEmpty());
        verify(orderGroupEntityRepository, times(1)).save(group);
        verify(orderGroupEntityRepository, times(1)).delete(group);
        verify(orderEntityRepository, never()).delete(any());
    }

    @Test
    void cancelOrder_ThrowsException_WhenOrderNotFound() {
        Long orderId = 404L;
        String token = "valid-token";
        String email = "customer@example.com";

        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUserMail(email);

        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(true);

        when(orderGroupEntityRepository.findByIdAndUserId(orderId, user.getId())).thenReturn(Optional.empty());
        when(orderEntityRepository.findByIdAndUserId(orderId, user.getId())).thenReturn(Optional.empty());

        GeneralException exception = assertThrows(GeneralException.class, () -> {
            userSupplyService.cancelOrder(orderId, token);
        });

        assertEquals("Order not found", exception.getMessage());
        verify(orderEntityRepository, never()).delete(any());
    }

    @Test
    void cancelOrder_ThrowsInvalidTokenException_WhenTokenInvalid() {
        String token = "bad-token";
        String email = "customer@example.com";
        UserEntity user = new UserEntity();
        user.setUserMail(email);

        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(false);

        assertThrows(InvalidTokenException.class, () -> {
            userSupplyService.cancelOrder(1L, token);
        });

        verify(orderGroupEntityRepository, never()).findByIdAndUserId(anyLong(), anyLong());
        verify(orderEntityRepository, never()).findByIdAndUserId(anyLong(), anyLong());
    }

    @Test
    void getProductDescription_Success() {
        Long productId = 10L;

        ProductDescriptionEntity desc1 = new ProductDescriptionEntity();
        desc1.setId(101L);
        desc1.setDescriptionTabName("Ingredients");
        desc1.setDescriptionTabContent("Sugar, Flour, Cocoa");

        ProductDescriptionEntity desc2 = new ProductDescriptionEntity();
        desc2.setId(102L);
        desc2.setDescriptionTabName("Usage");
        desc2.setDescriptionTabContent("Store in a cool dry place");

        List<ProductDescriptionEntity> entities = List.of(desc1, desc2);

        when(productDescriptionEntityRepository.findByProductEntityId(productId))
                .thenReturn(entities);

        ProductDescriptionListDto result = userSupplyService.getProductDescription(productId);

        assertNotNull(result);
        assertEquals(productId, result.getProductId());
        assertEquals(2, result.getDescriptionList().size());

        DescriptionsDto dto1 = result.getDescriptionList().get(0);
        assertEquals(101L, dto1.getDescriptionId());
        assertEquals("Ingredients", dto1.getDescriptionTabName());
        assertEquals("Sugar, Flour, Cocoa", dto1.getDescriptionTabContent());

        verify(productDescriptionEntityRepository).findByProductEntityId(productId);
    }


    @Test
    void getProductDescription_ReturnsEmptyList_WhenNoDescriptions() {
        Long productId = 88L;

        when(productDescriptionEntityRepository.findByProductEntityId(productId))
                .thenReturn(Collections.emptyList());

        ProductDescriptionListDto result = userSupplyService.getProductDescription(productId);

        assertNotNull(result);
        assertEquals(productId, result.getProductId());
        assertTrue(result.getDescriptionList().isEmpty());
    }
    @Test
    void getProductDescription_ThrowsException_WhenRepositoryReturnsNull() {
        Long productId = 99L;
        when(productDescriptionEntityRepository.findByProductEntityId(productId))
                .thenReturn(null);

        assertThrows(ProductNotFoundException.class, () -> {
            userSupplyService.getProductDescription(productId);
        });
    }
}


