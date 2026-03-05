package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.user.CategoryNotFoundException;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
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

import java.util.ArrayList;
import java.util.List;

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
    private UserEntityRepository userEntityRepository;
    @Mock
    private TokenService tokenService;

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
}