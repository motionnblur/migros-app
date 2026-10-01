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
import com.example.MigrosBackend.helper.PageRequestPolicy;
import com.example.MigrosBackend.helper.ProductPricingPolicy;
import com.example.MigrosBackend.helper.ProductUnitPricePolicy;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductDescriptionEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
import com.example.MigrosBackend.service.global.FileService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public final class UserCatalogReadService {
    /**
     * Everything a customer DTO carries about package size, derived once.
     *
     * <p>A record rather than four setters per call site, so the four fields cannot
     * be filled from four different sources: the amount and unit come off the row
     * and the unit price comes off {@link ProductUnitPricePolicy}, which reads that
     * same row. There is exactly one projection here and both catalogue listings
     * and the detail read use it.
     *
     * <p>{@code unitPrice} and {@code unitPriceBasis} are {@code null} together
     * and only when the product has no usable package size. That is what lets a
     * client render one line or none: there is no state in which a unit price
     * appears without a basis to say what it is per.
     */
    private record PackageMetadata(BigDecimal amount,
                                   String unit,
                                   BigDecimal unitPrice,
                                   String unitPriceBasis) {

        private static PackageMetadata absent() {
            return new PackageMetadata(null, null, null, null);
        }
    }

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
        // The bound is applied before any query runs, so an out-of-range page
        // costs no database access and cannot be masked by a category lookup.
        Pageable pageable = PageRequestPolicy.ofProductIdAscending(page, itemRange);
        ensureCategoryExists(categoryId);
        Page<ProductEntity> entities = productEntityRepository.findByCategoryEntityIdAndProductCountGreaterThan(categoryId, 0, pageable);
        // An existing category with no in-stock rows, or a page past the last
        // one, is an empty page rather than an error: the client sizes and
        // clamps the page from the category/subcategory count and renders the
        // returned empty list as an empty listing. Only a nonexistent category
        // id is rejected (above) and keeps the existing 404 mapping.
        return entities.stream().map(this::toProductPreviewDto).collect(Collectors.toList());
    }

    List<ProductPreviewDto> getAllProducts(int page, int itemRange) {
        Pageable pageable = PageRequestPolicy.ofProductIdAscending(page, itemRange);
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
        Pageable pageable = PageRequestPolicy.ofProductIdAscending(page, productRange);
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
        productDto2.setProductVersion(productEntity.getVersion());
        // The same number the preview rows carry, from the same call, so a card and
        // the detail page it opens cannot quote two different prices for one
        // product. The raw columns above stay raw; this is the payable one.
        productDto2.setEffectivePrice(getEffectivePrice(productEntity));
        PackageMetadata metadata = packageMetadataOf(productEntity);
        productDto2.setPackageAmount(metadata.amount());
        productDto2.setPackageUnit(metadata.unit());
        productDto2.setUnitPrice(metadata.unitPrice());
        productDto2.setUnitPriceBasis(metadata.unitPriceBasis());
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

    /**
     * The price a listing shows, and the one the cart line is built from.
     *
     * <p>The discount arithmetic is {@link ProductPricingPolicy}'s, so this is
     * the same number checkout charges rather than a second copy of it that can
     * drift. What is left here is the catalog's own tolerance for a row that is
     * not a valid product, which lives in
     * {@link #legacyListingPrice(ProductEntity)} so the decision is visible at
     * the boundary instead of being the policy's default.
     */
    BigDecimal getEffectivePrice(ProductEntity product) {
        return legacyListingPrice(product);
    }

    /**
     * The catalog's explicit boundary adaptation: a missing price or discount
     * reads as zero, and a price that is finer than the money scale is rounded
     * rather than refused.
     *
     * <p>This is deliberately <em>not</em> the checkout rule, and it is a display
     * decision only. A product page renders a list, so one damaged row must not
     * turn the whole page into a 500; and a product row that is missing its price
     * or discount predates the {@code NOT NULL} migration that now prevents it.
     * Rounding instead of rejecting a display price is the reading that keeps an
     * over-precise row visible, which is also the only way to see it and fix it.
     *
     * <p>None of this can make a price payable: the strictness
     * {@link ProductPricingPolicy#requireValidPrice} enforces is unchanged, so
     * any row catalog renders leniently is still refused at checkout rather than
     * being charged as zero.
     */
    private BigDecimal legacyListingPrice(ProductEntity product) {
        BigDecimal price = product.getProductPrice() == null ? BigDecimal.ZERO : product.getProductPrice();
        BigDecimal discount = product.getProductDiscount() == null ? BigDecimal.ZERO : product.getProductDiscount();
        return ProductPricingPolicy.effectivePrice(price, discount);
    }

    /**
     * The one projection from a product row to the customer listing row.
     *
     * <p>Package-private rather than private because
     * {@link ProductSearchService} builds the same preview and must not build it a
     * second time: two copies of this projection is how a search result starts
     * showing a package size the category listing does not, or a unit price derived
     * from a different price than the one on the card. One projection, called from
     * every catalogue read.
     */
    ProductPreviewDto toProductPreviewDto(ProductEntity itemEntity) {
        ProductPreviewDto itemDto = new ProductPreviewDto();
        itemDto.setProductId(itemEntity.getId());
        itemDto.setProductName(itemEntity.getProductName());
        itemDto.setProductPrice(getEffectivePrice(itemEntity));
        itemDto.setProductCount(itemEntity.getProductCount());
        PackageMetadata metadata = packageMetadataOf(itemEntity);
        itemDto.setPackageAmount(metadata.amount());
        itemDto.setPackageUnit(metadata.unit());
        itemDto.setUnitPrice(metadata.unitPrice());
        itemDto.setUnitPriceBasis(metadata.unitPriceBasis());
        return itemDto;
    }

    /**
     * The package size and the price per basis unit, derived from the same row.
     *
     * <p>Package-level facts pass through untouched, and the unit price is computed
     * from {@link #getEffectivePrice} rather than from {@code effective_price} or
     * from {@code product_price}: the card, the detail page and the cart line are
     * all built from that same number, so a unit price shown next to a package
     * price is necessarily a ratio of two numbers the customer is also looking at.
     *
     * <p>Size with no unit price is a real possibility and is not an error. A row
     * whose unit is outside the closed set - only reachable by a write that bypassed
     * the creation policy - keeps its package size and reports no unit price, so
     * the listing shows the size and hides the comparison rather than inventing a
     * divisor. Most products report nothing at all.
     */
    private PackageMetadata packageMetadataOf(ProductEntity product) {
        ProductUnitPricePolicy.UnitPrice unitPrice = ProductUnitPricePolicy.unitPrice(
                getEffectivePrice(product),
                product.getPackageAmount(),
                product.getPackageUnit());

        if (product.getPackageAmount() == null || product.getPackageUnit() == null) {
            return PackageMetadata.absent();
        }

        return new PackageMetadata(
                product.getPackageAmount(),
                product.getPackageUnit(),
                unitPrice == null ? null : unitPrice.amount(),
                unitPrice == null ? null : unitPrice.basis().name());
    }

    private void ensureCategoryExists(Long categoryId) {
        if (!categoryEntityRepository.existsById(categoryId)) {
            throw new CategoryNotFoundException(categoryId.toString());
        }
    }
}
