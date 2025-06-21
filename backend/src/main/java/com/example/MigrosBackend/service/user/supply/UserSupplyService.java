package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.user.product.ProductDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.product.ProductImageEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @Autowired
    public UserSupplyService(
            CategoryEntityRepository categoryEntityRepository,
            ProductEntityRepository productEntityRepository,
            ProductImageEntityRepository productImageEntityRepository,
            UserEntityRepository userEntityRepository,
            TokenService tokenService,
            OrderEntityRepository orderEntityRepository
    ) {
        this.categoryEntityRepository = categoryEntityRepository;
        this.productEntityRepository = productEntityRepository;
        this.productImageEntityRepository = productImageEntityRepository;
        this.userEntityRepository = userEntityRepository;
        this.tokenService = tokenService;
        this.orderEntityRepository = orderEntityRepository;
    }

    public List<String> getAllCategoryNames() {
        return categoryEntityRepository.findAll().stream().map(CategoryEntity::getCategoryName).toList();
    }

    public List<ProductPreviewDto> getProductsFromCategory(Long categoryId, int page, int itemRange) throws Exception {
        boolean b = categoryEntityRepository.existsById(categoryId);
        if(!b) throw new Exception("Category with that ID: " +categoryId+ " could not be found.");

        //Pageable pageable = PageRequest.of(page, itemRange, Sort.by("id").ascending());
        Pageable pageable = PageRequest.of(page, itemRange);
        Page<ProductEntity> entities =  productEntityRepository.findByCategoryEntityId(categoryId, pageable);
        if(entities.isEmpty()) throw new Exception("Category with that ID: " +categoryId+ " has no products.");

        return entities.stream().map(itemEntity -> {
            ProductPreviewDto itemDto = new ProductPreviewDto();
            itemDto.setProductId(itemEntity.getId());
            itemDto.setProductName(itemEntity.getProductName());
            if(itemEntity.getProductDiscount() != 0){
                itemDto.setProductPrice(itemEntity.getProductPrice() - (itemEntity.getProductPrice() * itemEntity.getProductDiscount() / 100));
            }else{
                itemDto.setProductPrice(itemEntity.getProductPrice());
            }

            return itemDto;
        }).collect(Collectors.toList());
    }
    public List<String> getProductImageNames(Long itemId) {
        List<ProductImageEntity> productImageEntity = productImageEntityRepository.findByProductEntityId(itemId);
        return productImageEntity.stream().map(ProductImageEntity::getImagePath).toList();
    }

    public ResponseEntity<Resource> getProductImage(Long itemId) throws Exception {
        ProductImageEntity productImageEntity = productImageEntityRepository.findByProductEntityId(itemId).get(0);
        String filename = productImageEntity.getImagePath();

        Resource resource = new UrlResource(Paths.get(filename).toUri());

        if (resource.exists() && resource.isReadable()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);
        } else {
            throw new Exception("File not found");
        }
    }

    public ResponseEntity<?> getProductCountsFromCategory(Long categoryId) {
        boolean b = categoryEntityRepository.existsById(categoryId);
        if(!b) return new ResponseEntity<>("Category with that ID: " +categoryId+ " could not be found.", HttpStatus.BAD_REQUEST);

        return ResponseEntity.ok(productEntityRepository.countByCategoryEntityId(categoryId));
    }

    public ResponseEntity<?> getSubCategories(Long categoryId) {
        CategoryEntity categoryEntity = categoryEntityRepository.findById(categoryId).get();

        List<SubCategoryDto> subCategoryDto = categoryEntity.getItemEntities().stream()
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

        return ResponseEntity.ok(subCategoryDto);
    }

    public List<ProductPreviewDto> getProductsFromSubcategory(String subcategoryName, int page, int productRange) {
        Pageable pageable = PageRequest.of(page, productRange);
        Page<ProductEntity> entities =  productEntityRepository.findBySubcategoryName(subcategoryName, pageable);
        return entities.stream().map(itemEntity -> {
            ProductPreviewDto itemDto = new ProductPreviewDto();
            itemDto.setProductId(itemEntity.getId());
            itemDto.setProductName(itemEntity.getProductName());
            if(itemEntity.getProductDiscount() != 0){
                itemDto.setProductPrice(itemEntity.getProductPrice() - (itemEntity.getProductPrice() * itemEntity.getProductDiscount() / 100));
            }else{
                itemDto.setProductPrice(itemEntity.getProductPrice());
            }

            return itemDto;
        }).collect(Collectors.toList());
    }

    public ResponseEntity<?> getProductCountsFromSubcategory(String subcategoryName) {
        return ResponseEntity.ok(productEntityRepository.countBySubcategoryName(subcategoryName));
    }

    public void addProductToInventory(Long productId, String token) {
        String userName = tokenService.extractUsername(token);
        UserEntity user = userEntityRepository.findByUserMail(userName);
        if(tokenService.validateToken(token, user.getUserMail()))
        {
            if (user.getProductsIdsInCart() == null) {
                user.setProductsIdsInCart(new ArrayList<>()); // Initialize if null
            }
            user.getProductsIdsInCart().add(productId);
            userEntityRepository.save(user);
        }else{
            throw new RuntimeException("Token not valid");
        }
    }

    public List<UserCartItemDto> getProductData() {
        UserEntity user = userEntityRepository.findById(1L).orElseThrow(() -> new RuntimeException("User not found"));
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

    public void removeProductFromInventory(Long productId, String token) {
        String userName = tokenService.extractUsername(token);
        UserEntity user = userEntityRepository.findByUserMail(userName);
        if(tokenService.validateToken(token, user.getUserMail()))
        {
            if (user.getProductsIdsInCart() == null) {
                user.setProductsIdsInCart(new ArrayList<>()); // Initialize if null
            }
            user.getProductsIdsInCart().removeAll(Collections.singleton(productId));
            userEntityRepository.save(user);
        }else{
            throw new RuntimeException("Token not valid");
        }
    }

    public void updateProductCountInInventory(Long productId, int count, String token) {
        if(count <= 0) throw new RuntimeException("Count can not be negative or zero");

        String userName = tokenService.extractUsername(token);
        if(userName == null) throw new RuntimeException("User not found");

        UserEntity user = userEntityRepository.findByUserMail(userName);
        if(user == null) throw new RuntimeException("User not found");

        if(tokenService.validateToken(token, user.getUserMail()))
        {
            if (user.getProductsIdsInCart() == null) {
                user.setProductsIdsInCart(new ArrayList<>()); // Initialize if null
            }
            user.getProductsIdsInCart().removeAll(Collections.singleton(productId));
            for(int i = 0; i < count; i++)
            {
                user.getProductsIdsInCart().add(productId);
            }
            userEntityRepository.save(user);
        }else{
            throw new RuntimeException("Token not valid");
        }
    }

    public ResponseEntity<?> getAllOrderIds(String token) {
        String userName = tokenService.extractUsername(token);
        UserEntity user = userEntityRepository.findByUserMail(userName);
        if(tokenService.validateToken(token, user.getUserMail()))
        {
            List<OrderEntity> orders = user.getOrderEntities();
            List<Long> orderIds = orders.stream().map(OrderEntity::getId).toList();
            return ResponseEntity.ok(orderIds);
        }else{
            throw new RuntimeException("Token not valid");
        }
    }

    public ResponseEntity<?> getOrderStatusByOrderId(Long orderId, String token) {
        String userName = tokenService.extractUsername(token);
        UserEntity user = userEntityRepository.findByUserMail(userName);
        if(tokenService.validateToken(token, user.getUserMail()))
        {
            OrderEntity order = orderEntityRepository.findById(orderId).orElseThrow(() -> new RuntimeException("Order not found"));
            return ResponseEntity.ok(order.getStatus());
        }else{
            throw new RuntimeException("Token not valid");
        }
    }

    public ResponseEntity<?> cancelOrder(Long orderId, String token) {
        String userName = tokenService.extractUsername(token);
        UserEntity user = userEntityRepository.findByUserMail(userName);

        if(tokenService.validateToken(token, user.getUserMail()))
        {
            OrderEntity order = orderEntityRepository.findById(orderId).orElseThrow(() -> new RuntimeException("Order not found"));
            orderEntityRepository.delete(order);
            return ResponseEntity.ok("Order cancelled");
        }else{
            throw new RuntimeException("Token not valid");
        }
    }
}
