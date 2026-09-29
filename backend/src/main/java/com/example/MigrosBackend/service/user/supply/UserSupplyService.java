package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDto2;
import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.order.UserOrderDetailDto;
import com.example.MigrosBackend.dto.user.order.UserOrderGroupDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.ProductNotFoundException;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UserSupplyService {
    private final ProductEntityRepository productEntityRepository;
    private final UserEntityRepository userEntityRepository;
    private final TokenService tokenService;
    private final OrderEntityRepository orderEntityRepository;
    private final OrderGroupEntityRepository orderGroupEntityRepository;
    private final UserCatalogReadService catalogReadService;
    private final UserOrderHistoryReadService orderHistoryReadService;

    @Autowired
    public UserSupplyService(
            CategoryEntityRepository categoryEntityRepository,
            ProductEntityRepository productEntityRepository,
            ProductImageEntityRepository productImageEntityRepository,
            UserEntityRepository userEntityRepository,
            TokenService tokenService,
            OrderEntityRepository orderEntityRepository,
            OrderGroupEntityRepository orderGroupEntityRepository,
            ProductDescriptionEntityRepository productDescriptionEntityRepository,
            FileService fileService
    ) {
        this.productEntityRepository = productEntityRepository;
        this.userEntityRepository = userEntityRepository;
        this.tokenService = tokenService;
        this.orderEntityRepository = orderEntityRepository;
        this.orderGroupEntityRepository = orderGroupEntityRepository;
        this.catalogReadService = new UserCatalogReadService(
                categoryEntityRepository,
                productEntityRepository,
                productImageEntityRepository,
                productDescriptionEntityRepository,
                fileService
        );
        this.orderHistoryReadService = new UserOrderHistoryReadService(
                userEntityRepository,
                tokenService,
                orderEntityRepository,
                orderGroupEntityRepository,
                productEntityRepository
        );
    }

    public List<String> getAllCategoryNames() {
        return catalogReadService.getAllCategoryNames();
    }

    public List<ProductPreviewDto> getProductsFromCategory(Long categoryId, int page, int itemRange) {
        return catalogReadService.getProductsFromCategory(categoryId, page, itemRange);
    }

    public List<ProductPreviewDto> getAllProducts(int page, int itemRange) {
        return catalogReadService.getAllProducts(page, itemRange);
    }

    public int getAllProductCounts() {
        return catalogReadService.getAllProductCounts();
    }

    public List<String> getProductImageNames(Long itemId) {
        return catalogReadService.getProductImageNames(itemId);
    }

    public Resource getProductImage(Long itemId) {
        return catalogReadService.getProductImage(itemId);
    }

    public int getProductCountsFromCategory(Long categoryId) {
        return catalogReadService.getProductCountsFromCategory(categoryId);
    }

    public List<SubCategoryDto> getSubCategories(Long categoryId) {
        return catalogReadService.getSubCategories(categoryId);
    }

    public List<ProductPreviewDto> getProductsFromSubcategory(String subcategoryName, int page, int productRange) {
        return catalogReadService.getProductsFromSubcategory(subcategoryName, page, productRange);
    }

    public int getProductCountsFromSubcategory(String subcategoryName) {
        return catalogReadService.getProductCountsFromSubcategory(subcategoryName);
    }

    public void addProductToInventory(Long productId, String token) {
        UserEntity user = getValidatedUserFromToken(token);
        ProductEntity product = productEntityRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId.toString()));

        if (product.getProductCount() <= 0) {
            throw new GeneralException("Product is out of stock.");
        }

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

    public List<UserCartItemDto> getProductData(String token) {
        UserEntity user = getValidatedUserFromToken(token);

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

    public ProductDto2 getProductData(Long productId) {
        return catalogReadService.getProductData(productId);
    }

    public void removeProductFromInventory(Long productId, String token) {
        UserEntity user = getValidatedUserFromToken(token);

        getOrInitializeCart(user).removeAll(Collections.singleton(productId));
        userEntityRepository.save(user);
    }

    public void updateProductCountInInventory(Long productId, int count, String token) {
        if (count <= 0) {
            throw new GeneralException("Count can not be negative or zero");
        }

        UserEntity user = getValidatedUserFromToken(token);
        ProductEntity product = productEntityRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId.toString()));

        if (product.getProductCount() <= 0) {
            throw new GeneralException("Product is out of stock.");
        }

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

    public List<Long> getAllOrderIds(String token) {
        return orderHistoryReadService.getAllOrderIds(token);
    }

    public String getOrderStatusByOrderId(Long orderId, String token) {
        return orderHistoryReadService.getOrderStatusByOrderId(orderId, token);
    }

    @Transactional
    public void cancelOrder(Long orderId, String token) {
        UserEntity user = getValidatedUserFromToken(token);

        OrderGroupEntity orderGroup = orderGroupEntityRepository.findByIdAndUserId(orderId, user.getId()).orElse(null);
        if (orderGroup != null) {
            if (!"Pending".equalsIgnoreCase(orderGroup.getStatus())) {
                throw new GeneralException("Only pending orders can be canceled.");
            }

            List<OrderEntity> orderItems = new ArrayList<>(orderGroup.getOrderItems());
            for (OrderEntity orderItem : orderItems) {
                restockProduct(orderItem.getItemId(), orderItem.getCount());
            }

            orderEntityRepository.deleteAll(orderItems);
            orderGroupEntityRepository.delete(orderGroup);
            return;
        }

        OrderEntity legacyOrder = orderEntityRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new GeneralException("Order not found"));

        if (!"Pending".equalsIgnoreCase(legacyOrder.getStatus())) {
            throw new GeneralException("Only pending orders can be canceled.");
        }

        restockProduct(legacyOrder.getItemId(), legacyOrder.getCount());
        orderEntityRepository.delete(legacyOrder);
    }

    public ProductDescriptionListDto getProductDescription(Long productId) {
        return catalogReadService.getProductDescription(productId);
    }

    public List<UserOrderDetailDto> getUserOrderDetails(String token) {
        return orderHistoryReadService.getUserOrderDetails(token);
    }

    public List<UserOrderGroupDto> getUserOrderGroups(String token) {
        return orderHistoryReadService.getUserOrderGroups(token);
    }
    private void restockProduct(Long productId, int amount) {
        if (amount <= 0) {
            return;
        }

        productEntityRepository.findById(productId).ifPresent(product -> {
            product.setProductCount(product.getProductCount() + amount);
            productEntityRepository.save(product);
        });
    }

    private List<Long> getOrInitializeCart(UserEntity user) {
        if (user.getProductsIdsInCart() == null) {
            user.setProductsIdsInCart(new ArrayList<>());
        }
        return user.getProductsIdsInCart();
    }

    private UserEntity getValidatedUserFromToken(String token) {
        String userName = tokenService.validateAndExtractUser(token);

        UserEntity user = userEntityRepository.findByUserMail(userName);
        if (user == null) {
            throw new UserNotFoundException(userName);
        }

        return user;
    }
}


