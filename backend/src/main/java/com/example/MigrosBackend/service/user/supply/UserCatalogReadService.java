package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionTabDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.product.ProductDetailDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductDescriptionEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.product.ProductImageEntity;
import com.example.MigrosBackend.exception.admin.ProductNotFoundException;
import com.example.MigrosBackend.exception.shared.FileNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.user.CategoryNotFoundException;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductDescriptionEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
import com.example.MigrosBackend.service.global.FileService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public final class UserCatalogReadService {
    private final CategoryEntityRepository categoryEntityRepository;
    private final ProductEntityRepository productEntityRepository;
    private final ProductImageEntityRepository productImageEntityRepository;
    private final ProductDescriptionEntityRepository productDescriptionEntityRepository;
    private final FileService fileService;

    public UserCatalogReadService(
            CategoryEntityRepository categoryEntityRepository,
            ProductEntityRepository productEntityRepository,
            ProductImageEntityRepository productImageEntityRepository,
            ProductDescriptionEntityRepository productDescriptionEntityRepository,
            FileService fileService
    ) {
        this.categoryEntityRepository = categoryEntityRepository;
        this.productEntityRepository = productEntityRepository;
        this.productImageEntityRepository = productImageEntityRepository;
        this.productDescriptionEntityRepository = productDescriptionEntityRepository;
        this.fileService = fileService;
    }

    List<String> getAllCategoryNames() {
        return categoryEntityRepository.findAll().stream().map(CategoryEntity::getCategoryName).toList();
    }

    List<ProductPreviewDto> getProductsFromCategory(Long categoryId, int page, int itemRange) {
        ensureCategoryExists(categoryId);

        Pageable pageable = PageRequest.of(page, itemRange);
        Page<ProductEntity> entities = productEntityRepository.findByCategoryEntityIdAndProductCountGreaterThan(categoryId, 0, pageable);
        // An existing category with no in-stock rows, or a page past the last
        // one, is an empty page rather than an error: the client sizes and
        // clamps the page from the category/subcategory count and renders the
        // returned empty list as an empty listing. Only a nonexistent category
        // id is rejected (above) and keeps the existing 404 mapping.
        return entities.stream().map(this::toProductPreviewDto).collect(Collectors.toList());
    }

    List<ProductPreviewDto> getAllProducts(int page, int itemRange) {
        Pageable pageable = PageRequest.of(page, itemRange);
        Page<ProductEntity> entities = productEntityRepository.findByProductCountGreaterThan(0, pageable);
        return entities.stream().map(this::toProductPreviewDto).collect(Collectors.toList());
    }

    int getAllProductCounts() {
        return productEntityRepository.countByProductCountGreaterThan(0);
    }

    List<String> getProductImageNames(Long itemId) {
        List<ProductImageEntity> productImageEntity = productImageEntityRepository.findByProductEntityId(itemId);
        return productImageEntity.stream().map(ProductImageEntity::getImagePath).toList();
    }

    Resource getProductImage(Long itemId) {
        List<ProductImageEntity> images = productImageEntityRepository.findByProductEntityId(itemId);
        if (images.isEmpty()) {
            throw new FileNotFoundException();
        }

        String storedPath = images.get(0).getImagePath();

        Resource resource;
        try {
            Path resolvedPath = fileService.resolveImagePath(storedPath);
            resource = new UrlResource(resolvedPath.toUri());
        } catch (Exception e) {
            throw new GeneralException("Error while loading image");
        }

        if (!resource.exists() || !resource.isReadable()) {
            throw new FileNotFoundException();
        }
        return resource;
    }

    int getProductCountsFromCategory(Long categoryId) {
        ensureCategoryExists(categoryId);
        return productEntityRepository.countByCategoryEntityIdAndProductCountGreaterThan(categoryId, 0);
    }

    List<SubCategoryDto> getSubCategories(Long categoryId) {
        CategoryEntity categoryEntity = categoryEntityRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId.toString()));

        return productEntityRepository.countProductsBySubcategory(categoryId).stream()
                .map(count -> {
                    SubCategoryDto dto = new SubCategoryDto();
                    dto.setSubCategoryId(categoryEntity.getId());
                    dto.setSubCategoryName(count.subcategoryName());
                    dto.setProductCount((int) count.productCount());
                    return dto;
                }).collect(Collectors.toList());
    }

    List<ProductPreviewDto> getProductsFromSubcategory(String subcategoryName, int page, int productRange) {
        Pageable pageable = PageRequest.of(page, productRange);
        Page<ProductEntity> entities = productEntityRepository.findBySubcategoryNameAndProductCountGreaterThan(subcategoryName, 0, pageable);
        return entities.stream().map(this::toProductPreviewDto).collect(Collectors.toList());
    }

    int getProductCountsFromSubcategory(String subcategoryName) {
        return productEntityRepository.countBySubcategoryNameAndProductCountGreaterThan(subcategoryName, 0);
    }

    public ProductDetailDto getProductData(Long productId) {
        ProductEntity productEntity = productEntityRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId.toString()));

        ProductDetailDto productDto2 = new ProductDetailDto();
        productDto2.setProductName(productEntity.getProductName());
        productDto2.setSubCategoryName(productEntity.getSubcategoryName());
        productDto2.setProductPrice(productEntity.getProductPrice());
        productDto2.setProductCount(productEntity.getProductCount());
        productDto2.setProductDiscount(productEntity.getProductDiscount());
        productDto2.setProductDescription(productEntity.getProductDescription());
        productDto2.setProductCategoryId(Math.toIntExact(productEntity.getCategoryEntity().getId()));
        return productDto2;
    }

    ProductDescriptionListDto getProductDescription(Long productId) {
        List<ProductDescriptionEntity> productDescriptionEntities = productDescriptionEntityRepository.findByProductEntityId(productId);

        ProductDescriptionListDto productDescriptionDto = new ProductDescriptionListDto();
        productDescriptionDto.setProductId(productId);
        productDescriptionDto.setDescriptionList(new ArrayList<>());

        for (ProductDescriptionEntity item : productDescriptionEntities) {
            ProductDescriptionTabDto dto = new ProductDescriptionTabDto(item.getId(), item.getDescriptionTabName(), item.getDescriptionTabContent());
            productDescriptionDto.getDescriptionList().add(dto);
        }

        return productDescriptionDto;
    }

    BigDecimal getEffectivePrice(ProductEntity product) {
        BigDecimal discount = product.getProductDiscount() == null ? BigDecimal.ZERO : product.getProductDiscount();
        BigDecimal price = product.getProductPrice() == null ? BigDecimal.ZERO : product.getProductPrice();
        BigDecimal normalizedPrice = price.setScale(2, RoundingMode.HALF_UP);
        if (discount.signum() <= 0) {
            return normalizedPrice;
        }
        BigDecimal factor = BigDecimal.ONE.subtract(discount.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        return normalizedPrice.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    private ProductPreviewDto toProductPreviewDto(ProductEntity itemEntity) {
        ProductPreviewDto itemDto = new ProductPreviewDto();
        itemDto.setProductId(itemEntity.getId());
        itemDto.setProductName(itemEntity.getProductName());
        itemDto.setProductPrice(getEffectivePrice(itemEntity));
        itemDto.setProductCount(itemEntity.getProductCount());
        return itemDto;
    }

    private void ensureCategoryExists(Long categoryId) {
        if (!categoryEntityRepository.existsById(categoryId)) {
            throw new CategoryNotFoundException(categoryId.toString());
        }
    }
}
