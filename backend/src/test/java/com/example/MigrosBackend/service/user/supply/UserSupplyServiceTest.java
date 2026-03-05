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
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.ProductNotFoundException;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.shared.InvalidTokenException;
import com.example.MigrosBackend.exception.user.CategoryNotFoundException;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductDescriptionEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.admin.supply.AdminSupplyService;
import com.example.MigrosBackend.service.global.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
    private AdminSupplyService adminSupplyService;

    @Mock
    private OrderEntityRepository orderEntityRepository;

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
        mockUser.setUserMail(testEmail);
        mockUser.setProductsIdsInCart(new ArrayList<>(List.of(100L)));
    }

    @Test
    void getProductsFromCategory_ShouldCalculateDiscountCorrectly() {
        // Arrange
        Long catId = 1L;
        ProductEntity product = new ProductEntity();
        product.setId(10L);
        product.setProductName("Milk");
        product.setProductPrice(100.0f);
        product.setProductDiscount(20.0f); // 20% off

        Page<ProductEntity> page = new PageImpl<>(List.of(product));

        when(categoryEntityRepository.existsById(catId)).thenReturn(true);
        when(productEntityRepository.findByCategoryEntityId(eq(catId), any())).thenReturn(page);

        // Act
        List<ProductPreviewDto> results = userSupplyService.getProductsFromCategory(catId, 0, 10);

        // Assert
        assertEquals(80.0f, results.get(0).getProductPrice()); // 100 - 20%
    }

    @Test
    void getProductsFromCategory_ShouldThrowException_WhenCategoryMissing() {
        when(categoryEntityRepository.existsById(1L)).thenReturn(false);

        assertThrows(CategoryNotFoundException.class, () ->
                userSupplyService.getProductsFromCategory(1L, 0, 10));
    }

    @Test
    void addProductToInventory_ShouldUpdateUserCart() {
        // Arrange
        when(tokenService.extractUsername(testToken)).thenReturn(testEmail);
        when(userEntityRepository.findByUserMail(testEmail)).thenReturn(mockUser);

        // Act
        userSupplyService.addProductToInventory(200L, testToken);

        // Assert
        assertTrue(mockUser.getProductsIdsInCart().contains(200L));
        assertEquals(2, mockUser.getProductsIdsInCart().size());

        verify(userEntityRepository).save(mockUser);
    }

    @Test
    void removeProductFromInventory_ShouldRemoveAllInstancesOfProduct() {
        // Arrange
        mockUser.setProductsIdsInCart(new ArrayList<>(List.of(100L, 100L, 200L)));
        when(tokenService.extractUsername(testToken)).thenReturn(testEmail);
        when(userEntityRepository.findByUserMail(testEmail)).thenReturn(mockUser);
        when(tokenService.validateToken(testToken, testEmail)).thenReturn(true);

        // Act
        userSupplyService.removeProductFromInventory(100L, testToken);

        // Assert
        assertFalse(mockUser.getProductsIdsInCart().contains(100L));
        assertEquals(1, mockUser.getProductsIdsInCart().size()); // Only 200L remains
    }

    @Test
    void updateProductCountInInventory_ShouldSetExactCount() {
        // Arrange
        when(tokenService.extractUsername(testToken)).thenReturn(testEmail);
        when(userEntityRepository.findByUserMail(testEmail)).thenReturn(mockUser);
        when(tokenService.validateToken(testToken, testEmail)).thenReturn(true);

        // Act - Set count of product 100 to exactly 3
        userSupplyService.updateProductCountInInventory(100L, 3, testToken);

        // Assert
        long count = mockUser.getProductsIdsInCart().stream().filter(id -> id == 100L).count();
        assertEquals(3, count);
    }

    @Test
    void updateProductCountInInventory_ShouldThrowException_WhenCountIsNegative() {
        assertThrows(RuntimeException.class, () ->
                userSupplyService.updateProductCountInInventory(100L, -1, testToken));
    }

    @Test
    void getProductImage_Success() throws IOException {
        // Arrange
        Long itemId = 1L;
        // Create a temporary file so UrlResource actually finds something on 'disk'
        Path tempFile = Files.createTempFile("test-image", ".png");
        String fakePath = tempFile.toString();

        ProductImageEntity imageEntity = new ProductImageEntity();
        imageEntity.setImagePath(fakePath);

        when(productImageEntityRepository.findByProductEntityId(itemId))
                .thenReturn(List.of(imageEntity));

        // Act
        Resource result = userSupplyService.getProductImage(itemId);

        // Assert
        assertNotNull(result);
        assertTrue(result.exists());
        assertEquals(tempFile.toUri(), result.getURI());

        // Cleanup
        Files.deleteIfExists(tempFile);
    }

    @Test
    void getProductImage_ThrowsGeneralException_WhenFileDoesNotExist() {
        // Arrange
        Long itemId = 2L;
        ProductImageEntity imageEntity = new ProductImageEntity();
        imageEntity.setImagePath("non/existent/path/image.png");

        when(productImageEntityRepository.findByProductEntityId(itemId))
                .thenReturn(List.of(imageEntity));

        // Act & Assert
        GeneralException exception = assertThrows(GeneralException.class, () -> {
            userSupplyService.getProductImage(itemId);
        });

        assertEquals("Error while loading image", exception.getMessage());
    }

    @Test
    void getProductImage_ThrowsException_WhenNoImageInDatabase() {
        // Arrange
        Long itemId = 3L;
        // If the list is empty, .get(0) will throw IndexOutOfBoundsException
        when(productImageEntityRepository.findByProductEntityId(itemId))
                .thenReturn(Collections.emptyList());

        // Act & Assert
        assertThrows(IndexOutOfBoundsException.class, () -> {
            userSupplyService.getProductImage(itemId);
        });
    }

    @Test
    void getAllCategoryNames_ShouldReturnListOfNames() {
        // Arrange
        CategoryEntity cat1 = new CategoryEntity();
        cat1.setCategoryName("Beverages");

        CategoryEntity cat2 = new CategoryEntity();
        cat2.setCategoryName("Dairy");

        List<CategoryEntity> mockCategories = List.of(cat1, cat2);

        when(categoryEntityRepository.findAll()).thenReturn(mockCategories);

        // Act
        List<String> result = userSupplyService.getAllCategoryNames();

        // Assert
        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.contains("Beverages"));
        assertTrue(result.contains("Dairy"));

        // Ensure the repository was called exactly once
        verify(categoryEntityRepository, times(1)).findAll();
    }

    @Test
    void getAllCategoryNames_ShouldReturnEmptyList_WhenNoCategoriesExist() {
        // Arrange
        when(categoryEntityRepository.findAll()).thenReturn(Collections.emptyList());

        // Act
        List<String> result = userSupplyService.getAllCategoryNames();

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(categoryEntityRepository, times(1)).findAll();
    }

    @Test
    void getProductImageNames_ShouldReturnListOfPaths() {
        // Arrange
        Long itemId = 10L;

        ProductImageEntity img1 = new ProductImageEntity();
        img1.setImagePath("/uploads/image1.png");

        ProductImageEntity img2 = new ProductImageEntity();
        img2.setImagePath("/uploads/image2.png");

        List<ProductImageEntity> mockImages = List.of(img1, img2);

        // Mock the repo to return our list when the specific itemId is passed
        when(productImageEntityRepository.findByProductEntityId(itemId))
                .thenReturn(mockImages);

        // Act
        List<String> result = userSupplyService.getProductImageNames(itemId);

        // Assert
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("/uploads/image1.png", result.get(0));
        assertEquals("/uploads/image2.png", result.get(1));

        // Verify the repo was called with the exact itemId
        verify(productImageEntityRepository, times(1)).findByProductEntityId(itemId);
    }

    @Test
    void getProductImageNames_ShouldReturnEmptyList_WhenNoImagesFound() {
        // Arrange
        Long itemId = 20L;
        when(productImageEntityRepository.findByProductEntityId(itemId))
                .thenReturn(Collections.emptyList());

        // Act
        List<String> result = userSupplyService.getProductImageNames(itemId);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(productImageEntityRepository, times(1)).findByProductEntityId(itemId);
    }

    @Test
    void getProductCountsFromCategory_Success() {
        // Arrange
        Long categoryId = 1L;
        int expectedCount = 15;

        // Mock first repo call: check if category exists
        when(categoryEntityRepository.existsById(categoryId)).thenReturn(true);

        // Mock second repo call: get the count
        when(productEntityRepository.countByCategoryEntityId(categoryId)).thenReturn(expectedCount);

        // Act
        int result = userSupplyService.getProductCountsFromCategory(categoryId);

        // Assert
        assertEquals(expectedCount, result);

        // Verify both interactions occurred
        verify(categoryEntityRepository, times(1)).existsById(categoryId);
        verify(productEntityRepository, times(1)).countByCategoryEntityId(categoryId);
    }

    @Test
    void getProductCountsFromCategory_ThrowsException_WhenCategoryNotFound() {
        // Arrange
        Long categoryId = 99L;

        // Mock repo to return false
        when(categoryEntityRepository.existsById(categoryId)).thenReturn(false);

        // Act & Assert
        CategoryNotFoundException exception = assertThrows(CategoryNotFoundException.class, () -> {
            userSupplyService.getProductCountsFromCategory(categoryId);
        });

        // Verify the exception message (adjust based on your actual Exception logic)
        assertTrue(exception.getMessage().contains(categoryId.toString()));

        // Verify that we NEVER called the product count repo because the check failed
        verify(productEntityRepository, never()).countByCategoryEntityId(anyLong());
    }

    @Test
    void getSubCategories_ShouldGroupAndCountSubcategories() {
        // Arrange
        Long categoryId = 1L;
        CategoryEntity category = new CategoryEntity();
        category.setId(categoryId);

        // Create mock products with different subcategories
        ProductEntity p1 = new ProductEntity();
        p1.setSubcategoryName("Fruits");

        ProductEntity p2 = new ProductEntity();
        p2.setSubcategoryName("Fruits");

        ProductEntity p3 = new ProductEntity();
        p3.setSubcategoryName("Vegetables");

        ProductEntity p4 = new ProductEntity();
        p4.setSubcategoryName(""); // Should be filtered out

        category.setItemEntities(List.of(p1, p2, p3, p4));

        when(categoryEntityRepository.findById(categoryId)).thenReturn(Optional.of(category));

        // Act
        List<SubCategoryDto> result = userSupplyService.getSubCategories(categoryId);

        // Assert
        assertNotNull(result);
        assertEquals(2, result.size()); // "Fruits" and "Vegetables"

        // Find specific DTOs to verify counts
        SubCategoryDto fruitsDto = result.stream()
                .filter(d -> d.getSubCategoryName().equals("Fruits")).findFirst().orElseThrow();
        SubCategoryDto veggiesDto = result.stream()
                .filter(d -> d.getSubCategoryName().equals("Vegetables")).findFirst().orElseThrow();

        assertEquals(2, fruitsDto.getProductCount()); // p1 and p2
        assertEquals(1, veggiesDto.getProductCount()); // p3
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
        // Arrange
        String subcat = "Snacks";
        int page = 0;
        int range = 10;
        Pageable pageable = PageRequest.of(page, range);

        // 1. Product with a discount (100 - 20% = 80)
        ProductEntity p1 = new ProductEntity();
        p1.setId(1L);
        p1.setProductName("Chips");
        p1.setProductPrice(100.0f);
        p1.setProductDiscount(20.0f);

        // 2. Product without a discount
        ProductEntity p2 = new ProductEntity();
        p2.setId(2L);
        p2.setProductName("Water");
        p2.setProductPrice(10.0f);
        p2.setProductDiscount(0.0f);

        Page<ProductEntity> productPage = new PageImpl<>(List.of(p1, p2));
        when(productEntityRepository.findBySubcategoryName(subcat, pageable)).thenReturn(productPage);

        // Act
        List<ProductPreviewDto> result = userSupplyService.getProductsFromSubcategory(subcat, page, range);

        // Assert
        assertEquals(2, result.size());

        // Check Product 1 (Discount applied)
        ProductPreviewDto chips = result.get(0);
        assertEquals("Chips", chips.getProductName());
        assertEquals(80.0f, chips.getProductPrice(), 0.001); // 100 - (100 * 20 / 100)

        // Check Product 2 (No discount)
        ProductPreviewDto water = result.get(1);
        assertEquals("Water", water.getProductName());
        assertEquals(10.0f, water.getProductPrice(), 0.001);

        verify(productEntityRepository).findBySubcategoryName(subcat, pageable);
    }

    @Test
    void getProductsFromSubcategory_ShouldReturnEmptyList_WhenNoProductsFound() {
        // Arrange
        String subcat = "EmptyCat";
        Pageable pageable = PageRequest.of(0, 10);
        when(productEntityRepository.findBySubcategoryName(subcat, pageable)).thenReturn(Page.empty());

        // Act
        List<ProductPreviewDto> result = userSupplyService.getProductsFromSubcategory(subcat, 0, 10);

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void getProductData_ShouldAggregateCartItemsCorrectly() {
        // Arrange
        Long userId = 1L;

        // 1. Mock User with duplicate IDs in cart (e.g., 2 apples, 1 milk)
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setProductsIdsInCart(List.of(101L, 101L, 102L));

        when(userEntityRepository.findById(userId)).thenReturn(Optional.of(user));

        // 2. Mock Product Entities
        ProductEntity apple = new ProductEntity();
        apple.setId(101L);
        apple.setProductName("Apple");
        apple.setProductPrice(1.5f);

        ProductEntity milk = new ProductEntity();
        milk.setId(102L);
        milk.setProductName("Milk");
        milk.setProductPrice(3.0f);

        // Note: Use ArgumentMatchers.anySet() or the specific keyset
        when(productEntityRepository.findAllById(anySet())).thenReturn(List.of(apple, milk));

        // Act
        List<UserCartItemDto> result = userSupplyService.getProductData();

        // Assert
        assertNotNull(result);
        assertEquals(2, result.size());

        // Verify Apple aggregation (Count should be 2)
        UserCartItemDto appleDto = result.stream()
                .filter(d -> d.getProductId().equals(101L)).findFirst().orElseThrow();
        assertEquals("Apple", appleDto.getProductName());
        assertEquals(2, appleDto.getProductCount());
        assertEquals(1.5f, appleDto.getProductPrice());

        // Verify Milk aggregation (Count should be 1)
        UserCartItemDto milkDto = result.stream()
                .filter(d -> d.getProductId().equals(102L)).findFirst().orElseThrow();
        assertEquals(1, milkDto.getProductCount());

        verify(userEntityRepository).findById(1L);
        verify(productEntityRepository).findAllById(anySet());
    }

    @Test
    void getProductData_ShouldThrowException_WhenUserNotFound() {
        // Arrange
        when(userEntityRepository.findById(1L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(UserNotFoundException.class, () -> userSupplyService.getProductData());
    }

    @Test
    void getProductData_Success() {
        // Arrange
        Long productId = 50L;

        // Create Mock Category
        CategoryEntity category = new CategoryEntity();
        category.setId(5L); // This will be converted to int

        // Create Mock Product
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

        // Act
        ProductDto2 result = userSupplyService.getProductData(productId);

        // Assert
        assertNotNull(result);
        assertEquals("Classic T-Shirt", result.getProductName());
        assertEquals("Apparel", result.getSubCategoryName());
        assertEquals(29.99f, result.getProductPrice());
        assertEquals(100, result.getProductCount());
        assertEquals(10.0f, result.getProductDiscount());
        assertEquals("A comfortable cotton shirt.", result.getProductDescription());

        // Verify the ID conversion (Long 5L to int 5)
        assertEquals(5, result.getProductCategoryId());

        verify(productEntityRepository, times(1)).findById(productId);
    }

    @Test
    void getProductData_ThrowsProductNotFoundException() {
        // Arrange
        Long productId = 999L;
        when(productEntityRepository.findById(productId)).thenReturn(Optional.empty());

        // Act & Assert
        ProductNotFoundException exception = assertThrows(ProductNotFoundException.class, () -> {
            userSupplyService.getProductData(productId);
        });

        assertTrue(exception.getMessage().contains(productId.toString()));
    }

    @Test
    void getAllOrderIds_Success() {
        // Arrange
        String token = "valid-token";
        String email = "user@example.com";

        UserEntity user = new UserEntity();
        user.setUserMail(email);

        OrderEntity order1 = new OrderEntity();
        order1.setId(500L);
        OrderEntity order2 = new OrderEntity();
        order2.setId(501L);

        user.setOrderEntities(List.of(order1, order2));

        // Mock TokenService behavior
        when(tokenService.extractUsername(token)).thenReturn(email);
        when(tokenService.validateToken(token, email)).thenReturn(true);

        // Mock Repository behavior
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);

        // Act
        List<Long> result = userSupplyService.getAllOrderIds(token);

        // Assert
        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.contains(500L));
        assertTrue(result.contains(501L));

        verify(tokenService).validateToken(token, email);
        verify(userEntityRepository).findByUserMail(email);
    }

    @Test
    void getAllOrderIds_ThrowsInvalidTokenException_WhenTokenIsInvalid() {
        // Arrange
        String token = "invalid-token";
        String email = "user@example.com";

        UserEntity user = new UserEntity();
        user.setUserMail(email);

        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        // Force the token validation to fail
        when(tokenService.validateToken(token, email)).thenReturn(false);

        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> {
            userSupplyService.getAllOrderIds(token);
        });
    }

    @Test
    void getOrderStatusByOrderId_Success() {
        // Arrange
        Long orderId = 101L;
        String token = "valid-jwt-token";
        String email = "test@example.com";
        String expectedStatus = "SHIPPED";

        UserEntity user = new UserEntity();
        user.setUserMail(email);

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setStatus(expectedStatus);

        // Mock TokenService
        when(tokenService.extractUsername(token)).thenReturn(email);
        when(tokenService.validateToken(token, email)).thenReturn(true);

        // Mock Repositories
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(orderEntityRepository.findById(orderId)).thenReturn(Optional.of(order));

        // Act
        String result = userSupplyService.getOrderStatusByOrderId(orderId, token);

        // Assert
        assertEquals(expectedStatus, result);
        verify(orderEntityRepository).findById(orderId);
    }

    @Test
    void getOrderStatusByOrderId_ThrowsGeneralException_WhenOrderMissing() {
        // Arrange
        Long orderId = 404L;
        String token = "valid-token";
        String email = "user@test.com";

        UserEntity user = new UserEntity();
        user.setUserMail(email);

        when(tokenService.extractUsername(token)).thenReturn(email);
        when(tokenService.validateToken(token, email)).thenReturn(true);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);

        // Mock order not found
        when(orderEntityRepository.findById(orderId)).thenReturn(Optional.empty());

        // Act & Assert
        GeneralException exception = assertThrows(GeneralException.class, () -> {
            userSupplyService.getOrderStatusByOrderId(orderId, token);
        });

        assertEquals("Order not found", exception.getMessage());
    }

    @Test
    void getOrderStatusByOrderId_ThrowsInvalidTokenException_WhenTokenInvalid() {
        // Arrange
        String token = "fake-token";
        String email = "user@test.com";
        UserEntity user = new UserEntity();
        user.setUserMail(email);

        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(false);

        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> {
            userSupplyService.getOrderStatusByOrderId(1L, token);
        });

        // Ensure we never even tried to look up the order
        verify(orderEntityRepository, never()).findById(anyLong());
    }

    @Test
    void cancelOrder_Success() {
        // Arrange
        Long orderId = 202L;
        String token = "valid-token";
        String email = "customer@example.com";

        UserEntity user = new UserEntity();
        user.setUserMail(email);

        OrderEntity order = new OrderEntity();
        order.setId(orderId);

        // Mock Security Flow
        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(true);

        // Mock Order Retrieval
        when(orderEntityRepository.findById(orderId)).thenReturn(Optional.of(order));

        // Act
        userSupplyService.cancelOrder(orderId, token);

        // Assert
        // Verify that delete was called with the specific order object
        verify(orderEntityRepository, times(1)).delete(order);
    }

    @Test
    void cancelOrder_ThrowsException_WhenOrderNotFound() {
        // Arrange
        Long orderId = 404L;
        String token = "valid-token";
        String email = "customer@example.com";

        UserEntity user = new UserEntity();
        user.setUserMail(email);

        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(true);

        // Mock order missing
        when(orderEntityRepository.findById(orderId)).thenReturn(Optional.empty());

        // Act & Assert
        GeneralException exception = assertThrows(GeneralException.class, () -> {
            userSupplyService.cancelOrder(orderId, token);
        });

        assertEquals("Order not found", exception.getMessage());
        // Verify delete was NEVER called
        verify(orderEntityRepository, never()).delete(any());
    }

    @Test
    void cancelOrder_ThrowsInvalidTokenException_WhenTokenInvalid() {
        // Arrange
        String token = "bad-token";
        String email = "customer@example.com";
        UserEntity user = new UserEntity();
        user.setUserMail(email);

        when(tokenService.extractUsername(token)).thenReturn(email);
        when(userEntityRepository.findByUserMail(email)).thenReturn(user);
        when(tokenService.validateToken(token, email)).thenReturn(false);

        // Act & Assert
        assertThrows(InvalidTokenException.class, () -> {
            userSupplyService.cancelOrder(1L, token);
        });

        // Verify system stopped before reaching the repository
        verify(orderEntityRepository, never()).findById(anyLong());
        verify(orderEntityRepository, never()).delete(any());
    }

    @Test
    void getProductDescription_Success() {
        // Arrange
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

        // Act
        ProductDescriptionListDto result = userSupplyService.getProductDescription(productId);

        // Assert
        assertNotNull(result);
        assertEquals(productId, result.getProductId());
        assertEquals(2, result.getDescriptionList().size());

        // Verify first DTO content
        DescriptionsDto dto1 = result.getDescriptionList().get(0);
        assertEquals(101L, dto1.getDescriptionId());
        assertEquals("Ingredients", dto1.getDescriptionTabName());
        assertEquals("Sugar, Flour, Cocoa", dto1.getDescriptionTabContent());

        verify(productDescriptionEntityRepository).findByProductEntityId(productId);
    }

    @Test
    void getProductDescription_ThrowsException_WhenRepositoryReturnsNull() {
        // Arrange
        Long productId = 99L;
        // Mocking the scenario where the repo returns null as per your 'if' check
        when(productDescriptionEntityRepository.findByProductEntityId(productId))
                .thenReturn(null);

        // Act & Assert
        assertThrows(ProductNotFoundException.class, () -> {
            userSupplyService.getProductDescription(productId);
        });
    }
}