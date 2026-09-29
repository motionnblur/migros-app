package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.admin.panel.*;
import com.example.MigrosBackend.dto.user.product.ProductDetailDto;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.product.ProductImageEntity;
import com.example.MigrosBackend.exception.admin.AdminNotFoundException;
import com.example.MigrosBackend.exception.admin.ProductNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
import com.example.MigrosBackend.service.global.FileService;
import com.example.MigrosBackend.service.user.supply.UserCatalogReadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AdminSupplyService {
    private final CategoryEntityRepository categoryEntityRepository;
    private final ProductEntityRepository productEntityRepository;
    private final ProductImageEntityRepository productImageEntityRepository;
    private final AdminEntityRepository adminEntityRepository;
    private final AdminProductDescriptionOperations productDescriptionOperations;
    private final AdminProductImageOperations productImageOperations;
    private final FileService fileService;
    private final UserCatalogReadService catalogReadService;

    @Autowired
    public AdminSupplyService(CategoryEntityRepository categoryEntityRepository, ProductEntityRepository productEntityRepository, ProductImageEntityRepository productImageEntityRepository, AdminEntityRepository adminEntityRepository, FileService fileService, AdminProductDescriptionOperations productDescriptionOperations, AdminProductImageOperations productImageOperations, UserCatalogReadService catalogReadService) {
        this.categoryEntityRepository = categoryEntityRepository;
        this.productEntityRepository = productEntityRepository;
        this.productImageEntityRepository = productImageEntityRepository;
        this.adminEntityRepository = adminEntityRepository;
        this.fileService = fileService;
        this.productDescriptionOperations = productDescriptionOperations;
        this.productImageOperations = productImageOperations;
        this.catalogReadService = catalogReadService;
    }

    /**
     * Registers a product and links it to its administrator.
 *
     * <p>Both writes are one transaction: the product row and the admin's
     * back-reference either both exist or neither does. As separate commits a
     * failure between them leaves a product no administrator can list or edit.
     */
@Transactional
public void addProduct(AdminAddItemDto adminAddItemDto) {
        AdminEntity currentAdminEntity = adminEntityRepository.findById(adminAddItemDto.getAdminId()).orElseThrow(() -> new AdminNotFoundException(adminAddItemDto.getAdminId().toString()));

        ProductEntity newProductEntity = new ProductEntity();
        newProductEntity.setProductName(adminAddItemDto.getProductDto().getProductName());
        newProductEntity.setSubcategoryName(adminAddItemDto.getProductDto().getSubCategoryName());
        newProductEntity.setProductCount(adminAddItemDto.getProductDto().getProductCount());
        newProductEntity.setProductPrice(adminAddItemDto.getProductDto().getProductPrice());
        newProductEntity.setProductDiscount(adminAddItemDto.getProductDto().getProductDiscount());

        ProductEntity s = productEntityRepository.save(newProductEntity);
        List<ProductEntity> itemEntities = currentAdminEntity.getItemEntities();
        itemEntities.add(s);

        currentAdminEntity.setItemEntities(itemEntities);

        adminEntityRepository.save(currentAdminEntity);
    }

    public void addCategory(String categoryName) {
        CategoryEntity ce = categoryEntityRepository.findByCategoryName(categoryName);
        if (ce != null)
            throw new GeneralException("Same category with that name: " + categoryName + " already exists.");

        CategoryEntity categoryEntity = new CategoryEntity();
        categoryEntity.setCategoryName(categoryName);

        categoryEntityRepository.save(categoryEntity);
    }

    /**
     * Stores the uploaded file and links it to a product in one transaction.
     *
     * <p>Ordering is load-bearing in both directions. Every database reference is
     * resolved before a single byte is written: the file is the one part of this
     * operation that a database rollback cannot undo, so nothing may be written
     * until the rows it belongs to are known to be viable, or an invalid category
     * or admin leaves an orphan image nothing ever references. And the new file is
     * registered for rollback cleanup the moment it exists, so a failure anywhere in
     * the transaction - including a commit-time failure, after this method has
     * already returned - deletes it again.
     */
    @Transactional
    public void uploadProduct(Long adminId, String productName,
                              String subCategoryName, BigDecimal productPrice,
                              int productCount, BigDecimal productDiscount,
                              String productDescription, int categoryValue,
                              MultipartFile selectedImage) {
        NormalizedProductDetails details = normalizeProductDetails(
                productName, subCategoryName, productPrice, productCount, productDiscount, productDescription);
        productImageOperations.validateProductImage(selectedImage);

        CategoryEntity categoryEntity = categoryEntityRepository.findByCategoryId(categoryValue);
        if (categoryEntity == null) {
            throw new GeneralException("Invalid category value: " + categoryValue);
        }
        AdminEntity adminEntity = adminEntityRepository.findById(adminId)
                .orElseThrow(() -> new AdminNotFoundException(adminId.toString()));

        Path savedFilePath = writeImageForRollbackCleanup(selectedImage);
        try {
            ProductEntity productEntity = new ProductEntity();
            applyProductDetails(productEntity, adminEntity, categoryEntity, details,
                    productPrice, productCount, productDiscount);
            productEntityRepository.save(productEntity);

            saveProductImage(productEntity, savedFilePath);
        } catch (RuntimeException ex) {
            // Fail fast rather than wait for the rollback: the file is the one
            // part of this operation the database cannot undo.
            fileService.deleteFileIfExists(savedFilePath);
            throw ex;
        }
    }

    /**
     * Applies an edit to a product, replacing its image when one is supplied.
     *
     * <p>Same transaction as the upload path, and for the same reason: the product
     * row, its admin/category references, and its image row either all become
     * visible together or none do. Two writes left outside a transaction could
     * commit the product edit and then fail on the image, leaving the product
     * pointing at the previous image (or at none) with no record of the intent.
     *
     * <p>A product that has no image row yet - one created before images existed,
     * or one whose image row was removed - gets one created here. Skipping the
     * update because there was nothing to update would leave the freshly uploaded
     * file on disk with nothing referencing it.
     */
    @Transactional
    public void updateProduct(Long adminId, Long productId, String productName,
                              String subCategoryName, BigDecimal productPrice,
                              int productCount, BigDecimal productDiscount,
                              String productDescription, int categoryValue,
                              MultipartFile selectedImage) {
        NormalizedProductDetails details = normalizeProductDetails(
                productName, subCategoryName, productPrice, productCount, productDiscount, productDescription);

        CategoryEntity categoryEntity = categoryEntityRepository.findByCategoryId(categoryValue);
        if (categoryEntity == null) {
            throw new GeneralException("Invalid category value: " + categoryValue);
        }
        AdminEntity adminEntity = adminEntityRepository.findById(adminId).orElseThrow(() -> new AdminNotFoundException(adminId.toString()));

        ProductEntity productEntity = productEntityRepository.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId.toString()));
        applyProductDetails(productEntity, adminEntity, categoryEntity, details,
                productPrice, productCount, productDiscount);
        productEntityRepository.save(productEntity);

        if (selectedImage != null && !selectedImage.isEmpty()) {
            Path savedFilePath = writeImageForRollbackCleanup(selectedImage);
            try {
                saveProductImage(productEntity, savedFilePath);
            } catch (RuntimeException ex) {
                fileService.deleteFileIfExists(savedFilePath);
                throw ex;
            }
        }
    }

    private void saveProductImage(ProductEntity productEntity, Path savedFilePath) {
        List<ProductImageEntity> images =
                productImageEntityRepository.findByProductEntityId(productEntity.getId());

        ProductImageEntity productImageEntity;
        if (images.isEmpty()) {
            // No row to update. Creating one is the only way the new upload is ever
            // reachable; doing nothing would strand the file on disk.
            productImageEntity = new ProductImageEntity();
            productImageEntity.setProductEntity(productEntity);
        } else {
            productImageEntity = images.get(0);
        }
        productImageEntity.setImagePath(savedFilePath.toString());
        productImageEntityRepository.save(productImageEntity);
    }

    /**
     * Writes the upload and arranges for it to be deleted if this transaction does
     * not commit.
     *
     * <p>Deleting inside a {@code catch} is not enough: the transaction can still
     * roll back after the method returns, on a deferred constraint or a commit-time
     * failure. A transaction synchronization observes the real outcome instead. A
     * call with no active transaction (a direct invocation in a test) has no
     * synchronization to register with, and there is no transaction to roll back
     * either.
     */
    private Path writeImageForRollbackCleanup(MultipartFile selectedImage) {
        Path savedFilePath = productImageOperations.writeProductImage(selectedImage);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return savedFilePath;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                fileService.deleteFileIfExists(savedFilePath);
            }
        });
        return savedFilePath;
    }

    public List<AdminProductPreviewDto> getAllAdminProducts(Long adminId, int page, int productRange) {
        Pageable pageable = PageRequest.of(page, productRange);
        Page<ProductEntity> entities = productEntityRepository.findByAdminEntityId(adminId, pageable);
        // An empty page is a valid result. The admin client sizes its paginator
        // from the separate product-count endpoint, so it never uses an error as
        // an end-of-pages signal; returning the empty list lets a transiently
        // empty page render instead of surfacing a 404.
        return entities.stream().map(productEntity -> {
            AdminProductPreviewDto productPreviewDto = new AdminProductPreviewDto();
            productPreviewDto.setProductId(productEntity.getId());
            productPreviewDto.setProductName(productEntity.getProductName());

            return productPreviewDto;
        }).collect(Collectors.toList());
    }

    public void deleteProduct(Long productId) {
        productEntityRepository.deleteById(productId);
    }

    public ProductDetailDto getProductData(Long productId) {
        return catalogReadService.getProductData(productId);
    }

    public void addProductDescription(ProductDescriptionListDto productDescriptions) {
        productDescriptionOperations.addProductDescription(productDescriptions);
    }

    public ProductDescriptionListDto getProductDescription(Long productId) {
        return productDescriptionOperations.getProductDescription(productId);
    }

    public void deleteProductDescription(Long descriptionId) {
        productDescriptionOperations.deleteProductDescription(descriptionId);
    }

    private NormalizedProductDetails normalizeProductDetails(String productName, String subCategoryName,
                                                              BigDecimal productPrice, int productCount,
                                                              BigDecimal productDiscount, String productDescription) {
        String normalizedProductName = normalizeRequiredText("Product name", productName);
        String normalizedSubCategoryName = normalizeRequiredText("Subcategory name", subCategoryName);
        String normalizedDescription = normalizeOptionalText(productDescription);
        validateProductNumbers(productPrice, productCount, productDiscount);
        return new NormalizedProductDetails(normalizedProductName, normalizedSubCategoryName, normalizedDescription);
    }

    private void applyProductDetails(ProductEntity productEntity, AdminEntity adminEntity,
                                     CategoryEntity categoryEntity, NormalizedProductDetails details,
                                     BigDecimal productPrice, int productCount, BigDecimal productDiscount) {
        productEntity.setAdminEntity(adminEntity);
        productEntity.setProductName(details.productName());
        productEntity.setSubcategoryName(details.subCategoryName());
        productEntity.setProductCount(productCount);
        productEntity.setProductPrice(productPrice);
        productEntity.setProductDiscount(productDiscount);
        productEntity.setCategoryEntity(categoryEntity);
        productEntity.setProductDescription(details.productDescription());
    }

    private void validateProductNumbers(BigDecimal productPrice, int productCount, BigDecimal productDiscount) {
        if (productPrice == null || productPrice.signum() < 0) {
            throw new GeneralException("Product price cannot be negative");
        }

        if (productPrice.stripTrailingZeros().scale() > 2) {
            throw new GeneralException("Product price must not exceed two decimal places");
        }

        if (productCount < 0) {
            throw new GeneralException("Product count cannot be negative");
        }

        if (productDiscount == null || productDiscount.signum() < 0 || productDiscount.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new GeneralException("Product discount must be between 0 and 100");
        }

        if (productDiscount.stripTrailingZeros().scale() > 2) {
            throw new GeneralException("Product discount must not exceed two decimal places");
        }
    }

    private String normalizeRequiredText(String fieldName, String value) {
        if (value == null) {
            throw new GeneralException(fieldName + " is required");
        }

        String normalized = value.trim();
        if (normalized.isEmpty() || "undefined".equalsIgnoreCase(normalized) || "null".equalsIgnoreCase(normalized)) {
            throw new GeneralException(fieldName + " is required");
        }

        return normalized;
    }

    private String normalizeOptionalText(String value) {
        if (value == null) {
            return "";
        }

        String normalized = value.trim();
        if ("undefined".equalsIgnoreCase(normalized) || "null".equalsIgnoreCase(normalized)) {
            return "";
        }

        return normalized;
    }

    private record NormalizedProductDetails(String productName, String subCategoryName, String productDescription) {
    }
}
