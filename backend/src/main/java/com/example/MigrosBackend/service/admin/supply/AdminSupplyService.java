package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.admin.panel.*;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.product.ProductImageEntity;
import com.example.MigrosBackend.exception.admin.AdminHasNoProductException;
import com.example.MigrosBackend.exception.admin.AdminNotFoundException;
import com.example.MigrosBackend.exception.admin.ProductNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductDescriptionEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
import com.example.MigrosBackend.service.global.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
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

    @Autowired
    public AdminSupplyService(CategoryEntityRepository categoryEntityRepository, ProductEntityRepository productEntityRepository, ProductImageEntityRepository productImageEntityRepository, AdminEntityRepository adminEntityRepository, ProductDescriptionEntityRepository productDescriptionEntityRepository, FileService fileService) {
        this.categoryEntityRepository = categoryEntityRepository;
        this.productEntityRepository = productEntityRepository;
        this.productImageEntityRepository = productImageEntityRepository;
        this.adminEntityRepository = adminEntityRepository;
        this.fileService = fileService;
        this.productDescriptionOperations = new AdminProductDescriptionOperations(
                productEntityRepository, productDescriptionEntityRepository);
        this.productImageOperations = new AdminProductImageOperations(fileService);
    }

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

    public void uploadProduct(Long adminId, String productName,
                              String subCategoryName, BigDecimal productPrice,
                              int productCount, BigDecimal productDiscount,
                              String productDescription, int categoryValue,
                              MultipartFile selectedImage) {
        NormalizedProductDetails details = normalizeProductDetails(
                productName, subCategoryName, productPrice, productCount, productDiscount, productDescription);
        productImageOperations.validateProductImage(selectedImage);

        // Every database reference is resolved before a single byte is written.
        // The file is the one part of this operation that cannot be undone by a
        // database rollback, so nothing may be written until the rows it belongs
        // to are known to be viable; otherwise an invalid category or admin
        // leaves an orphan image on disk that nothing ever references.
        CategoryEntity categoryEntity = categoryEntityRepository.findByCategoryId(categoryValue);
        if (categoryEntity == null) {
            throw new GeneralException("Invalid category value: " + categoryValue);
        }
        AdminEntity adminEntity = adminEntityRepository.findById(adminId)
                .orElseThrow(() -> new AdminNotFoundException(adminId.toString()));

        Path savedFilePath = productImageOperations.writeProductImage(selectedImage);
        try {
            ProductEntity productEntity = new ProductEntity();
            applyProductDetails(productEntity, adminEntity, categoryEntity, details,
                    productPrice, productCount, productDiscount);
            productEntityRepository.save(productEntity);

            ProductImageEntity productImageEntity = new ProductImageEntity();
            productImageEntity.setImagePath(savedFilePath.toString());
            productImageEntity.setProductEntity(productEntity);
            productImageEntityRepository.save(productImageEntity);
        } catch (RuntimeException ex) {
            // The product row never became durable, so the image it points at
            // would be an orphan that no product can ever serve or clean up.
            fileService.deleteFileIfExists(savedFilePath);
            throw ex;
        }
    }

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
            List<ProductImageEntity> images = productImageEntityRepository.findByProductEntityId(productEntity.getId());
            Path savedFilePath = productImageOperations.writeProductImage(selectedImage);
            try {
                // Update the image entity only if a new file was provided
                if (!images.isEmpty()) {
                    ProductImageEntity productImageEntity = images.get(0);
                    productImageEntity.setImagePath(savedFilePath.toString());
                    productImageEntityRepository.save(productImageEntity);
                }
            } catch (RuntimeException ex) {
                fileService.deleteFileIfExists(savedFilePath);
                throw ex;
            }
        }
    }

    public List<AdminProductPreviewDto> getAllAdminProducts(Long adminId, int page, int productRange) {
        Pageable pageable = PageRequest.of(page, productRange);
        Page<ProductEntity> entities = productEntityRepository.findByAdminEntityId(adminId, pageable);
        if (entities.isEmpty()) throw new AdminHasNoProductException(adminId.toString());

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

    public ProductDto2 getProductData(Long productId) {
        ProductEntity productEntity = productEntityRepository.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId.toString()));

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
