package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.admin.panel.*;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductDescriptionEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.product.ProductImageEntity;
import com.example.MigrosBackend.exception.admin.AdminHasNoProductException;
import com.example.MigrosBackend.exception.admin.AdminNotFoundException;
import com.example.MigrosBackend.exception.admin.FileUploadFailedException;
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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AdminSupplyService {
    private final CategoryEntityRepository categoryEntityRepository;
    private final ProductEntityRepository productEntityRepository;
    private final ProductImageEntityRepository productImageEntityRepository;
    private final AdminEntityRepository adminEntityRepository;
    private final ProductDescriptionEntityRepository productDescriptionEntityRepository;
    private final FileService fileService;

    @Autowired
    public AdminSupplyService(CategoryEntityRepository categoryEntityRepository, ProductEntityRepository productEntityRepository, ProductImageEntityRepository productImageEntityRepository, AdminEntityRepository adminEntityRepository, ProductDescriptionEntityRepository productDescriptionEntityRepository, FileService fileService) {
        this.categoryEntityRepository = categoryEntityRepository;
        this.productEntityRepository = productEntityRepository;
        this.productImageEntityRepository = productImageEntityRepository;
        this.adminEntityRepository = adminEntityRepository;
        this.productDescriptionEntityRepository = productDescriptionEntityRepository;
        this.fileService = fileService;
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
                              String subCategoryName, float productPrice,
                              int productCount, float productDiscount,
                              String productDescription, int categoryValue,
                              MultipartFile selectedImage) {
        String normalizedProductName = normalizeRequiredText("Product name", productName);
        String normalizedSubCategoryName = normalizeRequiredText("Subcategory name", subCategoryName);
        String normalizedDescription = normalizeOptionalText(productDescription);
        validateProductNumbers(productPrice, productCount, productDiscount);

        if (selectedImage == null || selectedImage.isEmpty()) {
            throw new GeneralException("Product image is required");
        }
        validatePngImage(selectedImage);

        String fileNameToSave = "image_" + System.currentTimeMillis() + ".png";
        Path savedFilePath;
        try {
            savedFilePath = fileService.writeFileToDisk(selectedImage.getBytes(), fileNameToSave);
        } catch (IOException e) {
            throw new FileUploadFailedException("Failed to read file bytes");
        }

        CategoryEntity categoryEntity = categoryEntityRepository.findByCategoryId(categoryValue);
        if (categoryEntity == null) {
            throw new GeneralException("Invalid category value: " + categoryValue);
        }
        AdminEntity adminEntity = adminEntityRepository.findById(adminId).orElseThrow(() -> new AdminNotFoundException(adminId.toString()));

        ProductEntity productEntity = new ProductEntity();
        productEntity.setAdminEntity(adminEntity);
        productEntity.setProductName(normalizedProductName);
        productEntity.setSubcategoryName(normalizedSubCategoryName);
        productEntity.setProductCount(productCount);
        productEntity.setProductPrice(productPrice);
        productEntity.setProductDiscount(productDiscount);
        productEntity.setCategoryEntity(categoryEntity);
        productEntity.setProductDescription(normalizedDescription);
        productEntityRepository.save(productEntity);

        ProductImageEntity productImageEntity = new ProductImageEntity();
        productImageEntity.setImagePath(savedFilePath.toString());
        productImageEntity.setProductEntity(productEntity);
        productImageEntityRepository.save(productImageEntity);
    }

    public void updateProduct(Long adminId, Long productId, String productName,
                              String subCategoryName, float productPrice,
                              int productCount, float productDiscount,
                              String productDescription, int categoryValue,
                              MultipartFile selectedImage) {
        String normalizedProductName = normalizeRequiredText("Product name", productName);
        String normalizedSubCategoryName = normalizeRequiredText("Subcategory name", subCategoryName);
        String normalizedDescription = normalizeOptionalText(productDescription);
        validateProductNumbers(productPrice, productCount, productDiscount);

        CategoryEntity categoryEntity = categoryEntityRepository.findByCategoryId(categoryValue);
        if (categoryEntity == null) {
            throw new GeneralException("Invalid category value: " + categoryValue);
        }
        AdminEntity adminEntity = adminEntityRepository.findById(adminId).orElseThrow(() -> new AdminNotFoundException(adminId.toString()));

        ProductEntity productEntity = productEntityRepository.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId.toString()));
        productEntity.setAdminEntity(adminEntity);
        productEntity.setProductName(normalizedProductName);
        productEntity.setSubcategoryName(normalizedSubCategoryName);
        productEntity.setProductCount(productCount);
        productEntity.setProductPrice(productPrice);
        productEntity.setProductDiscount(productDiscount);
        productEntity.setCategoryEntity(categoryEntity);
        productEntity.setProductDescription(normalizedDescription);
        productEntityRepository.save(productEntity);

        if (selectedImage != null && !selectedImage.isEmpty()) {
            validatePngImage(selectedImage);

            String fileNameToSave = "image_" + System.currentTimeMillis() + ".png";
            Path savedFilePath;
            try {
                savedFilePath = fileService.writeFileToDisk(selectedImage.getBytes(), fileNameToSave);
            } catch (IOException e) {
                throw new FileUploadFailedException("Failed to read file bytes");
            }

            // Update the image entity only if a new file was provided
            List<ProductImageEntity> images = productImageEntityRepository.findByProductEntityId(productEntity.getId());
            if (!images.isEmpty()) {
                ProductImageEntity productImageEntity = images.get(0);
                productImageEntity.setImagePath(savedFilePath.toString());
                productImageEntityRepository.save(productImageEntity);
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
        ProductEntity productEntity = productEntityRepository.findById(productDescriptions.getProductId()).orElseThrow(() -> new ProductNotFoundException(productDescriptions.getProductId().toString()));

        List<ProductDescriptionEntity> productDescriptionEntities = productDescriptionEntityRepository.findByProductEntityId(productEntity.getId());
        if (productDescriptionEntities.isEmpty()) {
            for (DescriptionsDto item : productDescriptions.getDescriptionList()) {
                ProductDescriptionEntity productDescriptionEntity = new ProductDescriptionEntity();
                productDescriptionEntity.setDescriptionTabName(item.getDescriptionTabName());
                productDescriptionEntity.setDescriptionTabContent(item.getDescriptionTabContent());
                productDescriptionEntity.setProductEntity(productEntity);

                productDescriptionEntityRepository.save(productDescriptionEntity);
            }
            return;
        }

        List<DescriptionsDto> descriptionsDtoList = productDescriptions.getDescriptionList();
        for (int i = 0; i < descriptionsDtoList.size(); i++) {
            Optional<ProductDescriptionEntity> pE = productDescriptionEntityRepository.findById(descriptionsDtoList.get(i).getDescriptionId());
            if (pE.isPresent()) {
                ProductDescriptionEntity productDescriptionEntity = pE.get();
                productDescriptionEntity.setDescriptionTabName(descriptionsDtoList.get(i).getDescriptionTabName());
                productDescriptionEntity.setDescriptionTabContent(descriptionsDtoList.get(i).getDescriptionTabContent());

                productDescriptionEntityRepository.save(productDescriptionEntity);
            } else {
                ProductDescriptionEntity productDescriptionEntity = new ProductDescriptionEntity();
                productDescriptionEntity.setDescriptionTabName(descriptionsDtoList.get(i).getDescriptionTabName());
                productDescriptionEntity.setDescriptionTabContent(descriptionsDtoList.get(i).getDescriptionTabContent());
                productDescriptionEntity.setProductEntity(productEntity);

                productDescriptionEntityRepository.save(productDescriptionEntity);
            }
        }
    }

    public ProductDescriptionListDto getProductDescription(Long productId) {
        List<ProductDescriptionEntity> productDescriptionEntities = productDescriptionEntityRepository.findByProductEntityId(productId);
        if (productDescriptionEntities == null || productDescriptionEntities.isEmpty())
            throw new ProductNotFoundException(productId.toString());

        ProductDescriptionListDto productDescriptionDto = new ProductDescriptionListDto();
        productDescriptionDto.setProductId(productId);
        productDescriptionDto.setDescriptionList(new ArrayList<>());

        for (ProductDescriptionEntity item : productDescriptionEntities) {
            DescriptionsDto dto = new DescriptionsDto(
                    item.getId(),
                    item.getDescriptionTabName(),
                    item.getDescriptionTabContent()
            );

            productDescriptionDto.getDescriptionList().add(dto);
        }

        return productDescriptionDto;
    }

    public void deleteProductDescription(Long descriptionId) {
        productDescriptionEntityRepository.deleteById(descriptionId);
    }

    private void validatePngImage(MultipartFile selectedImage) {
        if (!Objects.equals(selectedImage.getContentType(), "image/png")) {
            throw new GeneralException("Only PNG files are allowed");
        }
    }

    private void validateProductNumbers(float productPrice, int productCount, float productDiscount) {
        if (productPrice < 0) {
            throw new GeneralException("Product price cannot be negative");
        }

        if (productCount < 0) {
            throw new GeneralException("Product count cannot be negative");
        }

        if (productDiscount < 0 || productDiscount > 100) {
            throw new GeneralException("Product discount must be between 0 and 100");
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
}
