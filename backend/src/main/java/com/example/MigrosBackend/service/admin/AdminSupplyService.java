package com.example.MigrosBackend.service.admin;

import com.example.MigrosBackend.dto.ProductDto;
import com.example.MigrosBackend.dto.ProductDto2;
import com.example.MigrosBackend.dto.ProductPreviewDto;
import com.example.MigrosBackend.dto.admin.panel.AdminAddItemDto;
import com.example.MigrosBackend.entity.AdminEntity;
import com.example.MigrosBackend.entity.CategoryEntity;
import com.example.MigrosBackend.entity.ProductEntity;
import com.example.MigrosBackend.entity.ProductImageEntity;
import com.example.MigrosBackend.repository.AdminEntityRepository;
import com.example.MigrosBackend.repository.CategoryEntityRepository;
import com.example.MigrosBackend.repository.ProductEntityRepository;
import com.example.MigrosBackend.repository.ProductImageEntityRepository;
import com.example.MigrosBackend.service.global.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class AdminSupplyService {
    private final CategoryEntityRepository categoryEntityRepository;
    private final ProductEntityRepository productEntityRepository;
    private final ProductImageEntityRepository productImageEntityRepository;
    private final AdminEntityRepository adminEntityRepository;
    private final FileService fileService;

    @Autowired
    public AdminSupplyService(
            CategoryEntityRepository categoryEntityRepository,
            ProductEntityRepository productEntityRepository,
            ProductImageEntityRepository productImageEntityRepository,
            AdminEntityRepository adminEntityRepository,
            FileService fileService
    ) {
        this.categoryEntityRepository = categoryEntityRepository;
        this.productEntityRepository = productEntityRepository;
        this.productImageEntityRepository = productImageEntityRepository;
        this.adminEntityRepository = adminEntityRepository;
        this.fileService = fileService;
    }

    public void addProduct(AdminAddItemDto adminAddItemDto) throws Exception {
        AdminEntity currentAdminEntity = adminEntityRepository.findById(adminAddItemDto
                .getAdminId()).orElseThrow(() -> new Exception("Admin with that id: " + adminAddItemDto.getAdminId()+ " could not be found."));

        ProductEntity newProductEntity = new ProductEntity();
        newProductEntity.setProductName(adminAddItemDto.getProductDto().getProductName());
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
    public void updateProduct(Long adminId, Long productId, String productName,
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

    public List<ProductPreviewDto> getAllAdminProducts(Long adminId, int page, int productRange) throws Exception {
        Pageable pageable = PageRequest.of(page, productRange);
        Page<ProductEntity> entities =  productEntityRepository.findByAdminEntityId(adminId, pageable);
        if(entities.isEmpty()) throw new Exception("Admin with that ID: " +adminId+ " has no products.");

        return entities.stream().map(productEntity -> {
            ProductPreviewDto productPreviewDto = new ProductPreviewDto();
            productPreviewDto.setProductId(productEntity.getId());
            productPreviewDto.setProductName(productEntity.getProductName());
            productPreviewDto.setProductTitle(productEntity.getProductName());
            productPreviewDto.setProductPrice(productEntity.getProductPrice());

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
        productDto2.setProductPrice(productEntity.getProductPrice());
        productDto2.setProductCount(productEntity.getProductCount());
        productDto2.setProductDiscount(productEntity.getProductDiscount());
        productDto2.setProductDescription(productEntity.getProductDescription());
        productDto2.setProductCategoryId(Math.toIntExact(productEntity.getCategoryEntity().getId()));

        return productDto2;
    }
}
