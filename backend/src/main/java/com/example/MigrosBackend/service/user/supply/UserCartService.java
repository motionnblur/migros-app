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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Owns the user's cart: adding, removing and re-counting products, and mapping
 * the stored product-id list to the cart DTOs the API returns.
 *
 * <p>Every mutation runs in its own transaction and first takes a pessimistic
 * write lock on the user row, and reads the cart only from that locked state.
 * The cart is a single list column on that row, so two concurrent
 * read-modify-write mutations would otherwise both observe the same starting
 * list and the second would overwrite the first's change. The lock serializes
 * them, mirroring the {@code ForUpdate} finder convention the order code uses.
 *
 * <p>{@link #getCartData(String)} is deliberately the odd one out: it is a pure
 * read. It used to persist the normalized cart as a side effect, which meant a
 * display request could overwrite a concurrent add, remove or count change with
 * the list it had read before that change landed.
 *
 * <p>Keeping that read free of writes created the opposite trap, which
 * {@link #reconcileCart(String)} closes: the read hides entries it cannot render
 * (deleted products, sold-out products, quantities above the remaining stock)
 * while {@code CheckoutService.prepareCheckout} still reserves from the stored
 * list. A customer whose cart held one unavailable item saw a cart that looked
 * complete, was refused at checkout for a line they could not see, and had no way
 * to remove it, because the only affordance the UI has is the remove button on a
 * row that was never rendered. The read must stay pure, so the repair is an
 * explicit mutation the customer triggers.
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
        UserEntity user = requireUserByMailForUpdate(userMail);
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

    /**
     * Renders the cart. The stored list is never written and never even dirtied:
     * the managed entity is not touched, so a display request can neither lose
     * a concurrent mutation nor be lost by one.
     *
     * <p>Deleted and out-of-stock products are still omitted and quantities are
     * still clamped to the currently available stock, so the response a client
     * sees is unchanged; only the write that used to accompany it is gone. That
     * omission is why {@link #reconcileCart(String)} exists: this read must stay
     * pure, so the stored list can hold an entry this response hides, and the
     * customer needs an explicit, reported way to clear it.
     */
    @Transactional(readOnly = true)
    public List<UserCartItemDto> getCartData(String userMail) {
        UserEntity user = requireUserByMail(userMail);

        if (user.getProductsIdsInCart() == null || user.getProductsIdsInCart().isEmpty()) {
            return new ArrayList<>();
        }

        // A copy, so mapping can never mutate the list Hibernate manages.
        List<Long> originalProductIds = new ArrayList<>(user.getProductsIdsInCart());

        Map<Long, ProductEntity> productsById = productEntityRepository
                .findAllById(originalProductIds.stream().collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));

        return renderCart(originalProductIds, productsById);
    }

    /**
     * The result of an explicit cart reconciliation.
     *
     * @param cart the cart as it now stands, rendered exactly as
     *             {@link #getCartData(String)} renders it
     * @param removedProductIds products dropped entirely, because the product was
     *                          deleted or has no stock left
     * @param reducedProductIds products whose stored quantity was lowered to the
     *                          stock that is actually available
     */
    public record CartReconciliation(List<UserCartItemDto> cart,
                                     List<Long> removedProductIds,
                                     List<Long> reducedProductIds) {
    }

    /**
     * Repairs the stored cart against what can actually be bought, and reports
     * what it changed.
     *
     * <p>This is the counterpart to the pure {@link #getCartData(String)} read.
     * The read must not write, but the stored list is what checkout reserves
     * from, so a read that hides an unbuyable entry while leaving it stored turns
     * a stale cart into a checkout that fails for a line the customer cannot
     * see or remove. Rather than reintroducing a write into a display request -
     * which is how a concurrent add used to get erased - the repair is its own
     * explicit mutation, and the customer is told exactly what it did.
     *
     * <p>It runs under the same protocol as every other cart writer: the
     * {@code PESSIMISTIC_WRITE} lock on the user row, and the cart read only
     * from that locked state. That lock is what makes reconciliation safe rather
     * than destructive. An add, remove or count change that commits while
     * reconciliation is running is either wholly before the lock (and so is seen
     * and, if unbuyable, correctly corrected) or wholly after it (and so is
     * preserved); there is no window in which a concurrent change is read by one
     * side and overwritten by the other.
     *
     * <p>Two distinct repairs, reported separately because they mean different
     * things to the customer:
     *
     * <ul>
     *   <li>A product that is deleted, or whose stock has reached zero, is
     *       <em>removed</em>. It cannot be bought at any quantity, so keeping it
     *       would leave a checkout that can never succeed.</li>
     *   <li>A product whose stored quantity exceeds the remaining stock is
     *       <em>reduced</em> to that stock, never silently to zero and never left
     *       as is. The clamp is the only value that is both purchasable and
     *       within what the customer previously chose.</li>
     * </ul>
     *
     * <p>Nothing is written when the cart is already consistent, so a customer
     * who opens a healthy cart does not dirty their row. The write happens only
     * when the reconciled list actually differs.
     *
     * <p>This is a cart repair, not a purchase: it reserves no stock, creates no
     * order and moves no money. Checkout remains the only thing that reserves,
     * and it re-validates stock under its own product locks, so a reconciliation
     * that races a reservation can never cause an over-sale - it can only leave
     * the customer to reconcile again.
     */
    @Transactional
    public CartReconciliation reconcileCart(String userMail) {
        UserEntity user = requireUserByMailForUpdate(userMail);

        if (user.getProductsIdsInCart() == null || user.getProductsIdsInCart().isEmpty()) {
            return new CartReconciliation(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        List<Long> storedCart = new ArrayList<>(user.getProductsIdsInCart());
        // Insertion-ordered, so the reconciliation reports follow the customer's
        // own cart order rather than an arbitrary one.
        Map<Long, Long> requestedCounts = storedCart.stream()
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));

        Map<Long, ProductEntity> productsById = productEntityRepository
                .findAllById(requestedCounts.keySet()).stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));

        List<Long> reconciledCart = new ArrayList<>();
        List<Long> removedProductIds = new ArrayList<>();
        List<Long> reducedProductIds = new ArrayList<>();

        for (Map.Entry<Long, Long> entry : requestedCounts.entrySet()) {
            Long productId = entry.getKey();
            int requestedCount = entry.getValue().intValue();
            ProductEntity product = productsById.get(productId);

            if (product == null || product.getProductCount() <= 0) {
                // Unbuyable at any quantity, so it can never be reserved. Left
                // in place it is a checkout that cannot succeed, on a line the
                // customer cannot see.
                removedProductIds.add(productId);
                continue;
            }

            int allowedCount = Math.min(requestedCount, product.getProductCount());
            if (allowedCount < requestedCount) {
                reducedProductIds.add(productId);
            }
            for (int i = 0; i < allowedCount; i++) {
                reconciledCart.add(productId);
            }
        }

        if (!sameCartContents(storedCart, reconciledCart)) {
            user.setProductsIdsInCart(reconciledCart);
            userEntityRepository.save(user);
        }

        // Sorted, so the response for an unchanged customer is byte-identical
        // every time rather than depending on the order the stored ids happened
        // to be grouped in.
        removedProductIds.sort(Long::compareTo);
        reducedProductIds.sort(Long::compareTo);

        return new CartReconciliation(
                renderCart(reconciledCart, productsById),
                removedProductIds,
                reducedProductIds);
    }

    /**
     * Order-insensitive comparison of two stored carts.
     *
     * <p>The stored list is an unordered multiset of product ids - it is only ever
     * counted - so two lists holding the same ids in a different order are the
     * same cart and must not provoke a write.
     */
    private boolean sameCartContents(List<Long> left, List<Long> right) {
        if (left.size() != right.size()) {
            return false;
        }
        Map<Long, Long> leftCounts = left.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Map<Long, Long> rightCounts = right.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return leftCounts.equals(rightCounts);
    }

    /**
     * Maps a stored cart to its response items, omitting anything unbuyable and
     * clamping quantities exactly as {@link #getCartData(String)} does.
     *
     * <p>Shared so the post-reconciliation response and a later plain read cannot
     * disagree about what a cart looks like; a customer who reconciles and is
     * then shown a different rendering of the same cart would have no way to tell
     * which one checkout will use.
     */
    private List<UserCartItemDto> renderCart(List<Long> storedCart, Map<Long, ProductEntity> productsById) {
        Map<Long, Long> productIdCounts = storedCart.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        List<UserCartItemDto> cartItems = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : productIdCounts.entrySet()) {
            ProductEntity productEntity = productsById.get(entry.getKey());
            if (productEntity == null || productEntity.getProductCount() <= 0) {
                continue;
            }

            int requestedCount = entry.getValue().intValue();
            int allowedCount = Math.min(requestedCount, productEntity.getProductCount());
            if (allowedCount <= 0) {
                continue;
            }

            cartItems.add(toCartItem(productEntity, allowedCount));
        }
        return cartItems;
    }

    private UserCartItemDto toCartItem(ProductEntity productEntity, int allowedCount) {
        UserCartItemDto dto = new UserCartItemDto();
        dto.setProductId(productEntity.getId());
        dto.setProductName(productEntity.getProductName());
        dto.setProductPrice(catalogReadService.getEffectivePrice(productEntity));
        dto.setProductCount(allowedCount);
        dto.setAvailableStock(productEntity.getProductCount());
        return dto;
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
