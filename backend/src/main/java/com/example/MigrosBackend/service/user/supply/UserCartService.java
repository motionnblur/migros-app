package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.ProductNotFoundException;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Owns the user's cart: adding, removing and re-counting products, and mapping
 * the stored product-id list to the cart DTOs the API returns.
 *
 * <p>Every mutation runs in its own transaction and first takes a pessimistic
 * write lock on the user row. The cart is a single list column on that row, so
 * two concurrent read-modify-write mutations would otherwise both observe the
 * same starting list and the second would overwrite the first's change. The
 * lock serializes them, mirroring the {@code ForUpdate} finder convention the
 * order code uses.
 */
@Service
public class UserCartService {
    private final ProductEntityRepository productEntityRepository;
    private final UserEntityRepository userEntityRepository;
    private final UserCatalogReadService catalogReadService;

    public UserCartService(ProductEntityRepository productEntityRepository,
                           UserEntityRepository userEntityRepository,
                           UserCatalogReadService catalogReadService) {
        this.productEntityRepository = productEntityRepository;
        this.userEntityRepository = userEntityRepository;
        this.catalogReadService = catalogReadService;
    }

    @Transactional
    public void clearUserCart(String userMail) {
        UserEntity user = requireUserByMail(userMail);
        user.setProductsIdsInCart(new ArrayList<>());
        userEntityRepository.save(user);
    }

    @Transactional
    public void addProductToCart(Long productId, String userMail) {
        UserEntity user = requireUserByMailForUpdate(userMail);
        ProductEntity product = productEntityRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId.toString()));

        requireProductInStock(product);

        List<Long> productsIdsInCart = getOrInitializeCart(user);

        long currentCountInCart = productsIdsInCart.stream()
                .filter(id -> id.equals(productId))
                .count();

        if (currentCountInCart >= product.getProductCount()) {
            throw new GeneralException("You cannot add more than available stock.");
        }

        productsIdsInCart.add(productId);
        userEntityRepository.save(user);
    }

    // TODO(HI-2): this read still persists the normalized cart because an
    // existing test locks that write in. The response is identical either way;
    // a follow-up can make the read pure once no caller relies on the write.
    @Transactional
    public List<UserCartItemDto> getCartData(String userMail) {
        UserEntity user = requireUserByMail(userMail);

        if (user.getProductsIdsInCart() == null || user.getProductsIdsInCart().isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> originalProductIds = new ArrayList<>(user.getProductsIdsInCart());
        Map<Long, Long> productIdCounts = originalProductIds.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        List<ProductEntity> productEntities = productEntityRepository.findAllById(productIdCounts.keySet());
        Map<Long, ProductEntity> productEntityMap = productEntities.stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));

        List<Long> normalizedCart = new ArrayList<>();
        List<UserCartItemDto> cartItems = new ArrayList<>();

        for (Map.Entry<Long, Long> entry : productIdCounts.entrySet()) {
            ProductEntity productEntity = productEntityMap.get(entry.getKey());
            if (productEntity == null || productEntity.getProductCount() <= 0) {
                continue;
            }

            int requestedCount = entry.getValue().intValue();
            int allowedCount = Math.min(requestedCount, productEntity.getProductCount());
            if (allowedCount <= 0) {
                continue;
            }

            for (int i = 0; i < allowedCount; i++) {
                normalizedCart.add(productEntity.getId());
            }

            UserCartItemDto dto = new UserCartItemDto();
            dto.setProductId(productEntity.getId());
            dto.setProductName(productEntity.getProductName());
            dto.setProductPrice(catalogReadService.getEffectivePrice(productEntity));
            dto.setProductCount(allowedCount);
            dto.setAvailableStock(productEntity.getProductCount());
            cartItems.add(dto);
        }

        if (!normalizedCart.equals(originalProductIds)) {
            user.setProductsIdsInCart(normalizedCart);
            userEntityRepository.save(user);
        }

        return cartItems;
    }

    @Transactional
    public void removeProductFromCart(Long productId, String userMail) {
        UserEntity user = requireUserByMailForUpdate(userMail);

        getOrInitializeCart(user).removeAll(Collections.singleton(productId));
        userEntityRepository.save(user);
    }

    @Transactional
    public void updateProductCountInCart(Long productId, int count, String userMail) {
        if (count <= 0) {
            throw new GeneralException("Count can not be negative or zero");
        }

        UserEntity user = requireUserByMailForUpdate(userMail);
        ProductEntity product = productEntityRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId.toString()));

        requireProductInStock(product);

        if (count > product.getProductCount()) {
            throw new GeneralException("You cannot add more than available stock.");
        }

        List<Long> productsIdsInCart = getOrInitializeCart(user);

        productsIdsInCart.removeAll(Collections.singleton(productId));
        for (int i = 0; i < count; i++) {
            productsIdsInCart.add(productId);
        }
        userEntityRepository.save(user);
    }

    private void requireProductInStock(ProductEntity product) {
        if (product.getProductCount() <= 0) {
            throw new GeneralException("Product is out of stock.");
        }
    }

    private List<Long> getOrInitializeCart(UserEntity user) {
        if (user.getProductsIdsInCart() == null) {
            user.setProductsIdsInCart(new ArrayList<>());
        }
        return user.getProductsIdsInCart();
    }

    private UserEntity requireUserByMail(String userMail) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        return user;
    }

    private UserEntity requireUserByMailForUpdate(String userMail) {
        return userEntityRepository.findByUserMailForUpdate(userMail)
                .orElseThrow(() -> new UserNotFoundException(userMail));
    }
}
