package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.admin.panel.DescriptionsDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDto2;
import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.order.UserOrderDetailDto;
import com.example.MigrosBackend.dto.user.order.UserOrderGroupDto;
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
import com.example.MigrosBackend.exception.shared.FileNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.shared.InvalidTokenException;
import com.example.MigrosBackend.exception.shared.TokenNotFoundException;
import com.example.MigrosBackend.exception.user.CategoryHasNoProductException;
import com.example.MigrosBackend.exception.user.CategoryNotFoundException;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductDescriptionEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UserSupplyService {
    private final CategoryEntityRepository categoryEntityRepository;
    private final ProductEntityRepository productEntityRepository;
    private final ProductImageEntityRepository productImageEntityRepository;
    private final UserEntityRepository userEntityRepository;
    private final TokenService tokenService;
    private final OrderEntityRepository orderEntityRepository;
    private final OrderGroupEntityRepository orderGroupEntityRepository;
    private final ProductDescriptionEntityRepository productDescriptionEntityRepository;

    @Autowired
    public UserSupplyService(
            CategoryEntityRepository categoryEntityRepository,
            ProductEntityRepository productEntityRepository,
            ProductImageEntityRepository productImageEntityRepository,
            UserEntityRepository userEntityRepository,
            TokenService tokenService,
            OrderEntityRepository orderEntityRepository,
            OrderGroupEntityRepository orderGroupEntityRepository,
            ProductDescriptionEntityRepository productDescriptionEntityRepository
    ) {
        this.categoryEntityRepository = categoryEntityRepository;
        this.productEntityRepository = productEntityRepository;
        this.productImageEntityRepository = productImageEntityRepository;
        this.userEntityRepository = userEntityRepository;
        this.tokenService = tokenService;
        this.orderEntityRepository = orderEntityRepository;
        this.orderGroupEntityRepository = orderGroupEntityRepository;
        this.productDescriptionEntityRepository = productDescriptionEntityRepository;
    }

    public List<String> getAllCategoryNames() {
        return categoryEntityRepository.findAll().stream().map(CategoryEntity::getCategoryName).toList();
    }

    public List<ProductPreviewDto> getProductsFromCategory(Long categoryId, int page, int itemRange) {
        boolean b = categoryEntityRepository.existsById(categoryId);
        if (!b) throw new CategoryNotFoundException(categoryId.toString());

        Pageable pageable = PageRequest.of(page, itemRange);
        Page<ProductEntity> entities = productEntityRepository.findByCategoryEntityId(categoryId, pageable);
        if (entities.isEmpty()) throw new CategoryHasNoProductException(categoryId.toString());

        return entities.stream().map(itemEntity -> {
            ProductPreviewDto itemDto = new ProductPreviewDto();
            itemDto.setProductId(itemEntity.getId());
            itemDto.setProductName(itemEntity.getProductName());
            if (itemEntity.getProductDiscount() != 0) {
                itemDto.setProductPrice(itemEntity.getProductPrice() - (itemEntity.getProductPrice() * itemEntity.getProductDiscount() / 100));
            } else {
                itemDto.setProductPrice(itemEntity.getProductPrice());
            }
            return itemDto;
        }).collect(Collectors.toList());
    }

    public List<String> getProductImageNames(Long itemId) {
        List<ProductImageEntity> productImageEntity = productImageEntityRepository.findByProductEntityId(itemId);
        return productImageEntity.stream().map(ProductImageEntity::getImagePath).toList();
    }

    public Resource getProductImage(Long itemId) {
        ProductImageEntity productImageEntity = productImageEntityRepository.findByProductEntityId(itemId).get(0);
        String filename = productImageEntity.getImagePath();

        try {
            Resource resource = new UrlResource(Paths.get(filename).toUri());
            if (resource.exists() && resource.isReadable()) {
                return resource;
            } else {
                throw new FileNotFoundException();
            }
        } catch (Exception e) {
            throw new GeneralException("Error while loading image");
        }
    }

    public int getProductCountsFromCategory(Long categoryId) {
        boolean b = categoryEntityRepository.existsById(categoryId);
        if (!b) throw new CategoryNotFoundException(categoryId.toString());
        return productEntityRepository.countByCategoryEntityId(categoryId);
    }

    public List<SubCategoryDto> getSubCategories(Long categoryId) {
        CategoryEntity categoryEntity = categoryEntityRepository.findById(categoryId).get();

        return categoryEntity.getItemEntities().stream()
                .filter(itemEntity -> itemEntity.getSubcategoryName() != null && !itemEntity.getSubcategoryName().isEmpty())
                .collect(Collectors.groupingBy(ProductEntity::getSubcategoryName, Collectors.counting()))
                .entrySet().stream()
                .map(entry -> {
                    SubCategoryDto dto = new SubCategoryDto();
                    dto.setSubCategoryId(categoryEntity.getId());
                    dto.setSubCategoryName(entry.getKey());
                    dto.setProductCount(entry.getValue().intValue());
                    return dto;
                }).collect(Collectors.toList());
    }

    public List<ProductPreviewDto> getProductsFromSubcategory(String subcategoryName, int page, int productRange) {
        Pageable pageable = PageRequest.of(page, productRange);
        Page<ProductEntity> entities = productEntityRepository.findBySubcategoryName(subcategoryName, pageable);
        return entities.stream().map(itemEntity -> {
            ProductPreviewDto itemDto = new ProductPreviewDto();
            itemDto.setProductId(itemEntity.getId());
            itemDto.setProductName(itemEntity.getProductName());
            if (itemEntity.getProductDiscount() != 0) {
                itemDto.setProductPrice(itemEntity.getProductPrice() - (itemEntity.getProductPrice() * itemEntity.getProductDiscount() / 100));
            } else {
                itemDto.setProductPrice(itemEntity.getProductPrice());
            }
            return itemDto;
        }).collect(Collectors.toList());
    }

    public int getProductCountsFromSubcategory(String subcategoryName) {
        return productEntityRepository.countBySubcategoryName(subcategoryName);
    }

    public void addProductToInventory(Long productId, String token) {
        String userName = tokenService.extractUsername(token);
        if (userName == null) throw new TokenNotFoundException();
        UserEntity user = userEntityRepository.findByUserMail(userName);

        if (user.getProductsIdsInCart() == null) {
            user.setProductsIdsInCart(new ArrayList<>());
        }
        user.getProductsIdsInCart().add(productId);
        userEntityRepository.save(user);
    }

    public List<UserCartItemDto> getProductData() {
        UserEntity user = userEntityRepository.findById(1L).orElseThrow(() -> new UserNotFoundException("User not found"));
        List<Long> productIds = user.getProductsIdsInCart();

        Map<Long, Long> productIdCounts = productIds.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        List<ProductEntity> productEntities = productEntityRepository.findAllById(productIdCounts.keySet());
        Map<Long, ProductEntity> productEntityMap = productEntities.stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));

        return productIdCounts.entrySet().stream()
                .map(entry -> {
                    ProductEntity productEntity = productEntityMap.get(entry.getKey());
                    UserCartItemDto dto = new UserCartItemDto();
                    dto.setProductId(productEntity.getId());
                    dto.setProductName(productEntity.getProductName());
                    dto.setProductPrice(productEntity.getProductPrice());
                    dto.setProductCount(entry.getValue().intValue());
                    return dto;
                }).toList();
    }

    public ProductDto2 getProductData(Long productId) {
        ProductEntity productEntity = productEntityRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId.toString()));

        ProductDto2 productDto2 = new ProductDto2();
        productDto2.setProductName(productEntity.getProductName());
        productDto2.setSubCategoryName(productEntity.getSubcategoryName());
        productDto2.setProductPrice(productEntity.getProductPrice());
        productDto2.setProductCount(productEntity.getProductCount());
        productDto2.setProductDiscount(productEntity.getProductDiscount());
        productDto2.setProductDescription(productEntity.getProductDescription());
        productDto2.setProductCategoryId(Math.toIntExact(productEntity.getCategoryEntity().getId()));
        return productDto2;
    }

    public void removeProductFromInventory(Long productId, String token) {
        String userName = tokenService.extractUsername(token);
        UserEntity user = userEntityRepository.findByUserMail(userName);
        if (tokenService.validateToken(token, user.getUserMail())) {
            if (user.getProductsIdsInCart() == null) {
                user.setProductsIdsInCart(new ArrayList<>());
            }
            user.getProductsIdsInCart().removeAll(Collections.singleton(productId));
            userEntityRepository.save(user);
        } else {
            throw new RuntimeException("Token not valid");
        }
    }

    public void updateProductCountInInventory(Long productId, int count, String token) {
        if (count <= 0) throw new GeneralException("Count can not be negative or zero");

        String userName = tokenService.extractUsername(token);
        if (userName == null) throw new InvalidTokenException();

        UserEntity user = userEntityRepository.findByUserMail(userName);
        if (user == null) throw new UserNotFoundException(userName);

        if (tokenService.validateToken(token, user.getUserMail())) {
            if (user.getProductsIdsInCart() == null) {
                user.setProductsIdsInCart(new ArrayList<>());
            }
            user.getProductsIdsInCart().removeAll(Collections.singleton(productId));
            for (int i = 0; i < count; i++) {
                user.getProductsIdsInCart().add(productId);
            }
            userEntityRepository.save(user);
        } else {
            throw new InvalidTokenException();
        }
    }

    public List<Long> getAllOrderIds(String token) {
        String userName = tokenService.extractUsername(token);
        UserEntity user = userEntityRepository.findByUserMail(userName);

        if (!tokenService.validateToken(token, user.getUserMail())) {
            throw new InvalidTokenException();
        }

        List<Long> ids = new ArrayList<>();
        ids.addAll(orderGroupEntityRepository.findByUserId(user.getId()).stream().map(OrderGroupEntity::getId).toList());
        ids.addAll(orderEntityRepository.findByUserIdAndOrderGroupIsNull(user.getId()).stream().map(OrderEntity::getId).toList());
        return ids.stream().distinct().toList();
    }

    public String getOrderStatusByOrderId(Long orderId, String token) {
        String userName = tokenService.extractUsername(token);
        UserEntity user = userEntityRepository.findByUserMail(userName);

        if (!tokenService.validateToken(token, user.getUserMail())) {
            throw new InvalidTokenException();
        }

        OrderGroupEntity orderGroup = orderGroupEntityRepository.findByIdAndUserId(orderId, user.getId()).orElse(null);
        if (orderGroup != null) {
            return orderGroup.getStatus();
        }

        OrderEntity legacyOrder = orderEntityRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new GeneralException("Order not found"));
        return legacyOrder.getStatus();
    }

    public void cancelOrder(Long orderId, String token) {
        String userName = tokenService.extractUsername(token);
        UserEntity user = userEntityRepository.findByUserMail(userName);

        if (!tokenService.validateToken(token, user.getUserMail())) {
            throw new InvalidTokenException();
        }

        OrderGroupEntity orderGroup = orderGroupEntityRepository.findByIdAndUserId(orderId, user.getId()).orElse(null);
        if (orderGroup != null) {
            orderGroup.getOrderItems().clear();
            orderGroupEntityRepository.save(orderGroup);
            orderGroupEntityRepository.delete(orderGroup);
            return;
        }

        OrderEntity legacyOrder = orderEntityRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new GeneralException("Order not found"));
        orderEntityRepository.delete(legacyOrder);
    }

    public ProductDescriptionListDto getProductDescription(Long productId) {
        List<ProductDescriptionEntity> productDescriptionEntities = productDescriptionEntityRepository.findByProductEntityId(productId);
        if (productDescriptionEntities == null)
            throw new ProductNotFoundException(productId.toString());

        ProductDescriptionListDto productDescriptionDto = new ProductDescriptionListDto();
        productDescriptionDto.setProductId(productId);
        productDescriptionDto.setDescriptionList(new ArrayList<>());

        for (ProductDescriptionEntity item : productDescriptionEntities) {
            DescriptionsDto dto = new DescriptionsDto(item.getId(), item.getDescriptionTabName(), item.getDescriptionTabContent());
            productDescriptionDto.getDescriptionList().add(dto);
        }

        return productDescriptionDto;
    }

    public List<UserOrderDetailDto> getUserOrderDetails(String token) {
        String userName = tokenService.extractUsername(token);
        UserEntity user = userEntityRepository.findByUserMail(userName);

        if (!tokenService.validateToken(token, user.getUserMail())) {
            throw new InvalidTokenException();
        }

        List<UserOrderDetailDto> result = new ArrayList<>();

        List<OrderGroupEntity> groups = orderGroupEntityRepository.findByUserId(user.getId());
        List<OrderEntity> legacyOrders = orderEntityRepository.findByUserIdAndOrderGroupIsNull(user.getId());

        List<Long> productIds = new ArrayList<>();
        productIds.addAll(groups.stream().flatMap(g -> g.getOrderItems().stream()).map(OrderEntity::getItemId).toList());
        productIds.addAll(legacyOrders.stream().map(OrderEntity::getItemId).toList());

        if (productIds.isEmpty()) {
            return result;
        }

        Map<Long, ProductEntity> productMap = productEntityRepository.findAllById(productIds.stream().distinct().toList())
                .stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));

        for (OrderGroupEntity group : groups) {
            for (OrderEntity order : group.getOrderItems()) {
                ProductEntity product = productMap.get(order.getItemId());
                UserOrderDetailDto dto = new UserOrderDetailDto();
                dto.setOrderId(order.getId());
                dto.setProductId(order.getItemId());
                dto.setProductName(product != null ? product.getProductName() : "");
                dto.setCount(order.getCount());
                dto.setPrice(order.getPrice());
                dto.setTotalPrice(order.getTotalPrice());
                dto.setStatus(group.getStatus());
                result.add(dto);
            }
        }

        for (OrderEntity legacy : legacyOrders) {
            ProductEntity product = productMap.get(legacy.getItemId());
            UserOrderDetailDto dto = new UserOrderDetailDto();
            dto.setOrderId(legacy.getId());
            dto.setProductId(legacy.getItemId());
            dto.setProductName(product != null ? product.getProductName() : "");
            dto.setCount(legacy.getCount());
            dto.setPrice(legacy.getPrice());
            dto.setTotalPrice(legacy.getTotalPrice());
            dto.setStatus(legacy.getStatus());
            result.add(dto);
        }

        return result;
    }

    public List<UserOrderGroupDto> getUserOrderGroups(String token) {
        String userName = tokenService.extractUsername(token);
        UserEntity user = userEntityRepository.findByUserMail(userName);

        if (!tokenService.validateToken(token, user.getUserMail())) {
            throw new InvalidTokenException();
        }

        List<UserOrderGroupDto> result = new ArrayList<>();

        List<OrderGroupEntity> groups = orderGroupEntityRepository.findByUserId(user.getId());
        List<OrderEntity> legacyOrders = orderEntityRepository.findByUserIdAndOrderGroupIsNull(user.getId());

        List<Long> productIds = new ArrayList<>();
        productIds.addAll(groups.stream().flatMap(g -> g.getOrderItems().stream()).map(OrderEntity::getItemId).toList());
        productIds.addAll(legacyOrders.stream().map(OrderEntity::getItemId).toList());

        if (productIds.isEmpty()) {
            return result;
        }

        Map<Long, ProductEntity> productMap = productEntityRepository.findAllById(productIds.stream().distinct().toList())
                .stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));

        for (OrderGroupEntity group : groups) {
            UserOrderGroupDto groupDto = new UserOrderGroupDto();
            groupDto.setOrderGroupId(group.getId());
            groupDto.setCreatedAt(group.getCreatedAt());

            List<UserOrderDetailDto> items = new ArrayList<>();
            for (OrderEntity order : group.getOrderItems()) {
                ProductEntity product = productMap.get(order.getItemId());
                UserOrderDetailDto dto = new UserOrderDetailDto();
                dto.setOrderId(order.getId());
                dto.setProductId(order.getItemId());
                dto.setProductName(product != null ? product.getProductName() : "");
                dto.setCount(order.getCount());
                dto.setPrice(order.getPrice());
                dto.setTotalPrice(order.getTotalPrice());
                dto.setStatus(group.getStatus());
                items.add(dto);
            }
            groupDto.setItems(items);
            result.add(groupDto);
        }

        for (OrderEntity legacy : legacyOrders) {
            ProductEntity product = productMap.get(legacy.getItemId());
            UserOrderGroupDto groupDto = new UserOrderGroupDto();
            groupDto.setOrderGroupId(legacy.getId());
            groupDto.setCreatedAt(null);

            UserOrderDetailDto dto = new UserOrderDetailDto();
            dto.setOrderId(legacy.getId());
            dto.setProductId(legacy.getItemId());
            dto.setProductName(product != null ? product.getProductName() : "");
            dto.setCount(legacy.getCount());
            dto.setPrice(legacy.getPrice());
            dto.setTotalPrice(legacy.getTotalPrice());
            dto.setStatus(legacy.getStatus());
            groupDto.setItems(List.of(dto));

            result.add(groupDto);
        }

        result.sort((a, b) -> Long.compare(b.getOrderGroupId(), a.getOrderGroupId()));
        return result;
    }
}
