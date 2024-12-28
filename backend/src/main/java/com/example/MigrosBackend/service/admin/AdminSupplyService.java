package com.example.MigrosBackend.service.admin;

import com.example.MigrosBackend.dto.ItemDto;
import com.example.MigrosBackend.dto.ItemDto2;
import com.example.MigrosBackend.dto.ItemPreviewDto;
import com.example.MigrosBackend.dto.admin.panel.AdminAddItemDto;
import com.example.MigrosBackend.entity.AdminEntity;
import com.example.MigrosBackend.entity.CategoryEntity;
import com.example.MigrosBackend.entity.ItemEntity;
import com.example.MigrosBackend.entity.ItemImageEntity;
import com.example.MigrosBackend.repository.AdminEntityRepository;
import com.example.MigrosBackend.repository.CategoryEntityRepository;
import com.example.MigrosBackend.repository.ItemEntityRepository;
import com.example.MigrosBackend.repository.ItemImageEntityRepository;
import com.example.MigrosBackend.service.global.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class AdminSupplyService {
    private final CategoryEntityRepository categoryEntityRepository;
    private final ItemEntityRepository itemEntityRepository;
    private final ItemImageEntityRepository itemImageEntityRepository;
    private final AdminEntityRepository adminEntityRepository;
    private final FileService fileService;

    @Autowired
    public AdminSupplyService(
            CategoryEntityRepository categoryEntityRepository,
            ItemEntityRepository itemEntityRepository,
            ItemImageEntityRepository itemImageEntityRepository,
            AdminEntityRepository adminEntityRepository,
            FileService fileService
    ) {
        this.categoryEntityRepository = categoryEntityRepository;
        this.itemEntityRepository = itemEntityRepository;
        this.itemImageEntityRepository = itemImageEntityRepository;
        this.adminEntityRepository = adminEntityRepository;
        this.fileService = fileService;
    }

    public void addItem(AdminAddItemDto adminAddItemDto) throws Exception {
        AdminEntity currentAdminEntity = adminEntityRepository.findById(adminAddItemDto
                .getAdminId()).orElseThrow(() -> new Exception("Admin with that id: " + adminAddItemDto.getAdminId()+ " could not be found."));

        ItemEntity newItemEntity = new ItemEntity();
        newItemEntity.setItemName(adminAddItemDto.getItemDto().getItemName());
        newItemEntity.setItemCount(adminAddItemDto.getItemDto().getItemCount());
        newItemEntity.setItemPrice(adminAddItemDto.getItemDto().getItemPrice());
        newItemEntity.setDiscount(adminAddItemDto.getItemDto().getDiscount());

        ItemEntity s = itemEntityRepository.save(newItemEntity);
        List<ItemEntity> itemEntities = currentAdminEntity.getItemEntities();
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
    public void addItem(ItemDto itemDto) throws Exception {
//        CategoryEntity categoryEntity = categoryEntityRepository.findByCategoryName(itemDto.getCategoryName());
//        if(categoryEntity == null) throw new Exception("Category with that name: " +itemDto.getCategoryName()+ " could not be found.");
//
//        ItemEntity itemEntity = new ItemEntity();
//        itemEntity.setItemName(itemDto.getItemName());
//        itemEntity.setItemCount(itemDto.getItemCount());
//        itemEntity.setItemPrice(itemDto.getItemPrice());
//        itemEntity.setDiscount(itemDto.getDiscount());
//        itemEntity.setCategoryEntity(categoryEntity);
//
//        itemEntityRepository.save(itemEntity);
//
//        for(String imageName : itemDto.getItemImageNames()) {
//            ItemImageEntity itemImageEntity = new ItemImageEntity();
//            itemImageEntity.setImageName(imageName);
//            itemImageEntity.setItemEntity(itemEntity);
//            itemImageEntityRepository.save(itemImageEntity);
//        }
    }

    public void uploadProduct(Long adminId,
                              String productName,
                              float price,
                              int count,
                              float discount,
                              String description,
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
        System.out.println("Product data: " + productName + ", " + price + ", " + count + ", " + discount + ", " + description + "," + categoryValue);

        CategoryEntity categoryEntity = categoryEntityRepository.findByCategoryId(categoryValue);
        AdminEntity adminEntity = adminEntityRepository.findById(adminId)
                .orElseThrow(() -> new RuntimeException("Admin with that id: " + adminId + " could not be found."));

        ItemEntity itemEntity = new ItemEntity();
        itemEntity.setAdminEntity(adminEntity);
        itemEntity.setItemName(productName);
        itemEntity.setItemCount(count);
        itemEntity.setItemPrice(price);
        itemEntity.setDiscount(discount);
        itemEntity.setCategoryEntity(categoryEntity);
        itemEntity.setDescription(description);
        itemEntityRepository.save(itemEntity);

        ItemImageEntity itemImageEntity = new ItemImageEntity();
        itemImageEntity.setImagePath(savedFilePath.toString());
        itemImageEntity.setItemEntity(itemEntity);
        itemImageEntityRepository.save(itemImageEntity);
    }
    public void updateProduct(Long adminId,
                              Long productId,
                              String productName, float price,
                              int count, float discount,
                              String description, int categoryValue,
                              MultipartFile selectedImage) throws Exception {
        if (!Objects.equals(selectedImage.getContentType(), "image/png")) {
            throw new Exception("Only PNG files are allowed");
        }
        String fileNameToSave = "image_" + System.currentTimeMillis() + ".png";
        Path savedFilePath = fileService.writeFileToDisk(selectedImage.getBytes(),
                fileNameToSave,
                "UploadFolder"); // Save the file to hard coded "UploadFolder"

        // Process the product data here
        System.out.println("Product data: " + productName + ", " + price + ", " + count + ", " + discount + ", " + description + "," + categoryValue);

        CategoryEntity categoryEntity = categoryEntityRepository.findByCategoryId(categoryValue);
        AdminEntity adminEntity = adminEntityRepository.findById(adminId)
                .orElseThrow(() -> new RuntimeException("Admin with that id: " + adminId + " could not be found."));

        ItemEntity itemEntity = itemEntityRepository.findById(productId).orElseThrow(() -> new RuntimeException("Product with that id: " + productId + " could not be found."));
        itemEntity.setAdminEntity(adminEntity);
        itemEntity.setItemName(productName);
        itemEntity.setItemCount(count);
        itemEntity.setItemPrice(price);
        itemEntity.setDiscount(discount);
        itemEntity.setCategoryEntity(categoryEntity);
        itemEntity.setDescription(description);
        itemEntityRepository.save(itemEntity);

        ItemImageEntity itemImageEntity = itemImageEntityRepository.findByItemEntityId(itemEntity.getId()).get(0);
        itemImageEntity.setImagePath(savedFilePath.toString());
        itemImageEntityRepository.save(itemImageEntity);
    }

    public List<ItemPreviewDto> getAllAdminProducts(Long adminId, int page, int itemRange) throws Exception {
        Pageable pageable = PageRequest.of(page, itemRange);
        Page<ItemEntity> entities =  itemEntityRepository.findByAdminEntityId(adminId, pageable);
        if(entities.isEmpty()) throw new Exception("Admin with that ID: " +adminId+ " has no products.");

        return entities.stream().map(itemEntity -> {
            ItemPreviewDto itemDto = new ItemPreviewDto();
            itemDto.setItemId(itemEntity.getId());
            itemDto.setItemImageName(itemEntity.getItemName());
            itemDto.setItemTitle(itemEntity.getItemName());
            itemDto.setItemPrice(itemEntity.getItemPrice());
            return itemDto;
        }).collect(Collectors.toList());
    }

    public void deleteProduct(Long productId) {
        itemEntityRepository.deleteById(productId);
    }

    public ItemDto2 getItemData(Long itemId) {
        ItemEntity itemEntity = itemEntityRepository.findById(itemId).orElseThrow(() -> new RuntimeException("Item with that id: " + itemId + " could not be found."));

        ItemDto2 itemDto = new ItemDto2();
        itemDto.setProductName(itemEntity.getItemName());
        itemDto.setProductPrice(itemEntity.getItemPrice());
        itemDto.setProductCount(itemEntity.getItemCount());
        itemDto.setProductDiscount(itemEntity.getDiscount());
        itemDto.setProductDescription(itemEntity.getDescription());
        itemDto.setProductCategoryId(Math.toIntExact(itemEntity.getCategoryEntity().getId()));

        return itemDto;
    }
}
