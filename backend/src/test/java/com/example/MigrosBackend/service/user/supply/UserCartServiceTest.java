package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserCartServiceTest {
    @Mock
    private ProductEntityRepository productEntityRepository;

    @Mock
    private UserEntityRepository userEntityRepository;

    private UserCartService userCartService;

    private static final String USER_MAIL = "user@migros.com";

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        user.setId(1L);
        user.setUserMail(USER_MAIL);
        user.setProductsIdsInCart(new ArrayList<>());

        UserCatalogReadService catalogReadService =
                new UserCatalogReadService(null, null, null, null, null);
        userCartService = new UserCartService(
                productEntityRepository, userEntityRepository, catalogReadService);
    }

    private void stubAuthenticatedUser() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
    }

    private void stubAuthenticatedUserForUpdate() {
        when(userEntityRepository.findByUserMailForUpdate(USER_MAIL)).thenReturn(Optional.of(user));
    }

    @Test
    void addProductToCart_ShouldThrow_WhenCartAlreadyAtStockLimit() {
        stubAuthenticatedUserForUpdate();
        user.setProductsIdsInCart(new ArrayList<>(List.of(20L, 20L)));

        ProductEntity product = new ProductEntity();
        product.setId(20L);
        product.setProductName("Apple");
        product.setProductCount(2);

        when(productEntityRepository.findById(20L)).thenReturn(Optional.of(product));

        assertThrows(GeneralException.class, () -> userCartService.addProductToCart(20L, USER_MAIL));
        verify(userEntityRepository, never()).save(any());
    }

    @Test
    void removeProductFromCart_ShouldInitializeNullCartAndSaveUser() {
        stubAuthenticatedUserForUpdate();
        user.setProductsIdsInCart(null);

        userCartService.removeProductFromCart(20L, USER_MAIL);

        assertEquals(List.of(), user.getProductsIdsInCart());
        verify(userEntityRepository).save(user);
    }

    @Test
    void updateProductCountInCart_ShouldThrow_WhenCountExceedsStock() {
        stubAuthenticatedUserForUpdate();
        ProductEntity product = new ProductEntity();
        product.setId(25L);
        product.setProductCount(3);

        when(productEntityRepository.findById(25L)).thenReturn(Optional.of(product));

        assertThrows(GeneralException.class, () -> userCartService.updateProductCountInCart(25L, 4, USER_MAIL));
        verify(userEntityRepository, never()).save(any());
    }

    /**
     * The read is pure. The stored list is left exactly as it was, including the
     * entries the response omits, so rendering the cart can never undo a
     * concurrent mutation - and the managed list the entity holds is not even
     * touched while the response is being mapped.
     */
    @Test
    void getCartData_ShouldNormalizeTheResponseWithoutTouchingTheStoredList() {
        stubAuthenticatedUser();
        List<Long> storedCart = new ArrayList<>(List.of(1L, 1L, 1L, 2L, 3L, 4L));
        user.setProductsIdsInCart(storedCart);

        ProductEntity inStock = new ProductEntity();
        inStock.setId(1L);
        inStock.setProductName("Apple");
        inStock.setProductPrice(new BigDecimal("12"));
        inStock.setProductDiscount(new BigDecimal("50"));
        inStock.setProductCount(2);

        ProductEntity soldOut = new ProductEntity();
        soldOut.setId(2L);
        soldOut.setProductName("Orange");
        soldOut.setProductPrice(new BigDecimal("7"));
        soldOut.setProductCount(0);

        ProductEntity inStockToo = new ProductEntity();
        inStockToo.setId(3L);
        inStockToo.setProductName("Pear");
        inStockToo.setProductPrice(new BigDecimal("4"));
        inStockToo.setProductCount(1);

        // Product 4 is absent from the result set: it was deleted while it sat
        // in the cart.
        when(productEntityRepository.findAllById(any())).thenReturn(List.of(inStock, soldOut, inStockToo));

        List<UserCartItemDto> result = userCartService.getCartData(USER_MAIL);

        assertEquals(2, result.size(), "the deleted and the sold-out product are omitted from the response");
        assertEquals(1L, result.get(0).getProductId());
        assertEquals(2, result.get(0).getProductCount(), "the quantity is still clamped to available stock");
        assertEquals(0, new BigDecimal("6").compareTo(result.get(0).getProductPrice()));
        assertEquals(2, result.get(0).getAvailableStock());
        assertEquals(3L, result.get(1).getProductId());
        assertEquals(1, result.get(1).getProductCount());

        assertEquals(List.of(1L, 1L, 1L, 2L, 3L, 4L), storedCart,
                "a cart read must leave the persisted list exactly as it found it");
        verify(userEntityRepository, never()).save(any());
    }

    @Test
    void getCartData_ShouldNotWriteWhenNothingNeedsNormalizing() {
        stubAuthenticatedUser();
        user.setProductsIdsInCart(new ArrayList<>(List.of(1L)));

        ProductEntity inStock = new ProductEntity();
        inStock.setId(1L);
        inStock.setProductName("Apple");
        inStock.setProductPrice(new BigDecimal("12"));
        inStock.setProductCount(5);
        when(productEntityRepository.findAllById(any())).thenReturn(List.of(inStock));

        List<UserCartItemDto> result = userCartService.getCartData(USER_MAIL);

        assertEquals(1, result.size());
        verify(userEntityRepository, never()).save(any());
    }

    /**
     * The clear is a cart mutation like any other, so it has to take the same
     * row write lock: without it a clear racing an add would let the clear write
     * back the list it read before the add.
     */
    @Test
    void clearUserCart_ShouldTakeTheSameUserRowLockAsTheOtherMutations() {
        stubAuthenticatedUserForUpdate();
        user.setProductsIdsInCart(new ArrayList<>(List.of(1L, 2L, 2L)));

        userCartService.clearUserCart(USER_MAIL);

        assertEquals(List.of(), user.getProductsIdsInCart());
        verify(userEntityRepository).findByUserMailForUpdate(USER_MAIL);
        verify(userEntityRepository, never()).findByUserMail(USER_MAIL);
        verify(userEntityRepository).save(user);
    }

    @Test
    void clearUserCart_ShouldThrowNotFound_ForAnUnknownMailbox() {
        when(userEntityRepository.findByUserMailForUpdate("nobody@migros.com")).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> userCartService.clearUserCart("nobody@migros.com"));
        verify(userEntityRepository, never()).save(any());
    }

    @Test
    void getCartData_ShouldThrowNotFound_ForAnUnknownMailbox() {
        when(userEntityRepository.findByUserMail("nobody@migros.com")).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> userCartService.getCartData("nobody@migros.com"));
    }

    @Test
    void getCartData_ShouldReturnAnEmptyListForAnEmptyStoredCart() {
        stubAuthenticatedUser();

        assertTrue(userCartService.getCartData(USER_MAIL).isEmpty());
        verify(productEntityRepository, never()).findAllById(any());
    }
}
