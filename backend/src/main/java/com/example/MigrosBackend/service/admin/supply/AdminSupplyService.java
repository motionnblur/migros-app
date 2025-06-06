package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.admin.panel.*;
import com.example.MigrosBackend.dto.user.product.ProductDto;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductDescriptionEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.product.ProductImageEntity;
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
    public AdminSupplyService(
            CategoryEntityRepository categoryEntityRepository,
            ProductEntityRepository productEntityRepository,
            ProductImageEntityRepository productImageEntityRepository,
            AdminEntityRepository adminEntityRepository,
            ProductDescriptionEntityRepository productDescriptionEntityRepository,
            FileService fileService
    ) {
        this.categoryEntityRepository = categoryEntityRepository;
        this.productEntityRepository = productEntityRepository;
        this.productImageEntityRepository = productImageEntityRepository;
        this.adminEntityRepository = adminEntityRepository;
        this.productDescriptionEntityRepository = productDescriptionEntityRepository;
        this.fileService = fileService;
    }

    public void addProduct(AdminAddItemDto adminAddItemDto) throws Exception {
        AdminEntity currentAdminEntity = adminEntityRepository.findById(adminAddItemDto
                .getAdminId()).orElseThrow(() -> new Exception("Admin with that id: " + adminAddItemDto.getAdminId()+ " could not be found."));

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
    public void addCategory(String categoryName) throws Exception {
        CategoryEntity ce = categoryEntityRepository.findByCategoryName(categoryName);
        if(ce != null) throw new Exception("Same category with that name: "+categoryName+" already exists.");

        CategoryEntity categoryEntity = new CategoryEntity();
        categoryEntity.setCategoryName(categoryName);

        categoryEntityRepository.save(categoryEntity);
    }
    public void addProduct(ProductDto productDto) throws Exception {
//        CategoryEntity categoryEntity = categoryEntityRepository.findByCategoryName(productDto.getCategoryName());
//        if(categoryEntity == null) throw new Exception("Category with that name: " +productDto.getCategoryName()+ " could not be found.");
//
//        ProductEntity itemEntity = new ProductEntity();
//        itemEntity.setItemName(productDto.getItemName());
//        itemEntity.setItemCount(productDto.getItemCount());
//        itemEntity.setItemPrice(productDto.getItemPrice());
//        itemEntity.setDiscount(productDto.getDiscount());
//        itemEntity.setCategoryEntity(categoryEntity);
//
//        productEntityRepository.save(itemEntity);
//
//        for(String imageName : productDto.getItemImageNames()) {
//            ProductImageEntity itemImageEntity = new ProductImageEntity();
//            itemImageEntity.setImageName(imageName);
//            itemImageEntity.setProductEntity(itemEntity);
//            productImageEntityRepository.save(itemImageEntity);
//        }
    }

    public void uploadProduct(Long adminId,
                              String productName,
                              String subCategoryName,
                              float productPrice,
                              int productCount,
                              float productDiscount,
                              String productDescription,
                              int categoryValue,
                              MultipartFile selectedImage) throws Exception {
        if (!Objects.equals(selectedImage.getContentType(), "image/png")) {
            throw new Exception("Only PNG files are allowed");
        }
        String fileNameToSave = "image_" + System.currentTimeMillis() + ".png";
        Path savedFilePath = fileService.writeFileToDisk(selectedImage.getBytes(),
                fileNameToSave,
                "UploadFolder"); // Save the file to hard coded "UploadFolder"

        // Process the product data here
        System.out.println("Product data: " + productName + ", " + productPrice + ", " + productCount + ", " + productDiscount + ", " + productDescription + "," + categoryValue);

        CategoryEntity categoryEntity = categoryEntityRepository.findByCategoryId(categoryValue);
        AdminEntity adminEntity = adminEntityRepository.findById(adminId)
                .orElseThrow(() -> new RuntimeException("Admin with that id: " + adminId + " could not be found."));

        ProductEntity productEntity = new ProductEntity();
        productEntity.setAdminEntity(adminEntity);
        productEntity.setProductName(productName);
        productEntity.setSubcategoryName(subCategoryName);
        productEntity.setProductCount(productCount);
        productEntity.setProductPrice(productPrice);
        productEntity.setProductDiscount(productDiscount);
        productEntity.setCategoryEntity(categoryEntity);
        productEntity.setProductDescription(productDescription);
        productEntityRepository.save(productEntity);

        ProductImageEntity productImageEntity = new ProductImageEntity();
        productImageEntity.setImagePath(savedFilePath.toString());
        productImageEntity.setProductEntity(productEntity);
        productImageEntityRepository.save(productImageEntity);
    }
    public void updateProduct(Long adminId, Long productId, String productName, String subCategoryName,
                              float productPrice, int productCount, float productDiscount,
                              String productDescription, int categoryValue, MultipartFile selectedImage) throws Exception {
        if (!Objects.equals(selectedImage.getContentType(), "image/png")) {
            throw new Exception("Only PNG files are allowed");
        }
        String fileNameToSave = "image_" + System.currentTimeMillis() + ".png";
        Path savedFilePath = fileService.writeFileToDisk(selectedImage.getBytes(),
                fileNameToSave,
                "UploadFolder"); // Save the file to hard coded "UploadFolder"

        // Process the product data here
        System.out.println("Product data: " + productName + ", " + productPrice + ", " + productCount + ", " + productDiscount + ", " + productDescription + "," + categoryValue);

        CategoryEntity categoryEntity = categoryEntityRepository.findByCategoryId(categoryValue);
        AdminEntity adminEntity = adminEntityRepository.findById(adminId)
                .orElseThrow(() -> new RuntimeException("Admin with that id: " + adminId + " could not be found."));

        ProductEntity productEntity = productEntityRepository.findById(productId).orElseThrow(() -> new RuntimeException("Product with that id: " + productId + " could not be found."));
        productEntity.setAdminEntity(adminEntity);
        productEntity.setProductName(productName);
        productEntity.setSubcategoryName(subCategoryName);
        productEntity.setProductCount(productCount);
        productEntity.setProductPrice(productPrice);
        productEntity.setProductDiscount(productDiscount);
        productEntity.setCategoryEntity(categoryEntity);
        productEntity.setProductDescription(productDescription);
        productEntityRepository.save(productEntity);

        ProductImageEntity productImageEntity = productImageEntityRepository.findByProductEntityId(productEntity.getId()).get(0);
        productImageEntity.setImagePath(savedFilePath.toString());
        productImageEntityRepository.save(productImageEntity);
    }

    public List<AdminProductPreviewDto> getAllAdminProducts(Long adminId, int page, int productRange) throws Exception {
        Pageable pageable = PageRequest.of(page, productRange);
        Page<ProductEntity> entities =  productEntityRepository.findByAdminEntityId(adminId, pageable);
        if(entities.isEmpty()) throw new Exception("Admin with that ID: " +adminId+ " has no products.");

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
        ProductEntity productEntity = productEntityRepository.findById(productId).orElseThrow(() -> new RuntimeException("Item with that id: " + productId + " could not be found."));

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
        ProductEntity productEntity = productEntityRepository.findById(productDescriptions.getProductId())
                .orElseThrow(() -> new RuntimeException("Item with that id: " + productDescriptions.getProductId() + " could not be found."));

        List<ProductDescriptionEntity> productDescriptionEntities = productDescriptionEntityRepository.findByProductEntityId(productEntity.getId());
        if(productDescriptionEntities.isEmpty()) {
            for(DescriptionsDto item : productDescriptions.getDescriptionList()) {
                ProductDescriptionEntity productDescriptionEntity = new ProductDescriptionEntity();
                productDescriptionEntity.setDescriptionTabName(item.getDescriptionTabName());
                productDescriptionEntity.setDescriptionTabContent(item.getDescriptionTabContent());
                productDescriptionEntity.setProductEntity(productEntity);

                productDescriptionEntityRepository.save(productDescriptionEntity);
            }
            return;
        }

        List<DescriptionsDto> descriptionsDtoList = productDescriptions.getDescriptionList();
        for(int i = 0; i < descriptionsDtoList.size(); i++) {
            Optional<ProductDescriptionEntity> pE = productDescriptionEntityRepository.findById(descriptionsDtoList.get(i).getDescriptionId());
            if(pE.isPresent()) {
                ProductDescriptionEntity productDescriptionEntity = pE.get();
                productDescriptionEntity.setDescriptionTabName(descriptionsDtoList.get(i).getDescriptionTabName());
                productDescriptionEntity.setDescriptionTabContent(descriptionsDtoList.get(i).getDescriptionTabContent());

                productDescriptionEntityRepository.save(productDescriptionEntity);
            }else{
                ProductDescriptionEntity productDescriptionEntity = new ProductDescriptionEntity();
                productDescriptionEntity.setDescriptionTabName(descriptionsDtoList.get(i).getDescriptionTabName());
                productDescriptionEntity.setDescriptionTabContent(descriptionsDtoList.get(i).getDescriptionTabContent());
                productDescriptionEntity.setProductEntity(productEntity);

                productDescriptionEntityRepository.save(productDescriptionEntity);
            }
        }
//        for (int i = 0; i < productDescriptionEntities.size(); i++) {
//            ProductDescriptionEntity productDescriptionEntity = productDescriptionEntities.get(i);
//            DescriptionsDto descriptionsDto = descriptionsDtoList.get(i);
//
//            productDescriptionEntity.setDescriptionTabName(descriptionsDto.getDescriptionTabName());
//            productDescriptionEntity.setDescriptionTabContent(descriptionsDto.getDescriptionTabContent());
//
//            productDescriptionEntityRepository.save(productDescriptionEntity);
//        }
    }

    public ProductDescriptionListDto getProductDescription(Long productId) {
        List<ProductDescriptionEntity> productDescriptionEntities = productDescriptionEntityRepository.findByProductEntityId(productId);
        if(productDescriptionEntities == null)
            throw new RuntimeException("Item with that id: " + productId + " could not be found.");

        ProductDescriptionListDto productDescriptionDto = new ProductDescriptionListDto();
        productDescriptionDto.setProductId(productId);
        productDescriptionDto.setDescriptionList(new ArrayList<>());

        for(ProductDescriptionEntity item : productDescriptionEntities) {
            DescriptionsDto dto = new DescriptionsDto();
            dto.setDescriptionId(item.getId());
            dto.setDescriptionTabName(item.getDescriptionTabName());
            dto.setDescriptionTabContent(item.getDescriptionTabContent());

            productDescriptionDto.getDescriptionList().add(dto);
        }

        return productDescriptionDto;
    }

    public void deleteProductDescription(Long descriptionId) {
        productDescriptionEntityRepository.deleteById(descriptionId);
    }
}
