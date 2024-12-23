package com.example.MigrosBackend.service.admin;

import com.example.MigrosBackend.dto.ItemDto;
import com.example.MigrosBackend.dto.admin.panel.AdminAddItemDto;
import com.example.MigrosBackend.entity.AdminEntity;
import com.example.MigrosBackend.entity.CategoryEntity;
import com.example.MigrosBackend.entity.ItemEntity;
import com.example.MigrosBackend.entity.ItemImageEntity;
import com.example.MigrosBackend.repository.AdminEntityRepository;
import com.example.MigrosBackend.repository.CategoryEntityRepository;
import com.example.MigrosBackend.repository.ItemEntityRepository;
import com.example.MigrosBackend.repository.ItemImageEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminSupplyService {
    private final CategoryEntityRepository categoryEntityRepository;
    private final ItemEntityRepository itemEntityRepository;
    private final ItemImageEntityRepository itemImageEntityRepository;
    private final AdminEntityRepository adminEntityRepository;

    @Autowired
    public AdminSupplyService(
            CategoryEntityRepository categoryEntityRepository,
            ItemEntityRepository itemEntityRepository,
            ItemImageEntityRepository itemImageEntityRepository,
            AdminEntityRepository adminEntityRepository
    ) {
        this.categoryEntityRepository = categoryEntityRepository;
        this.itemEntityRepository = itemEntityRepository;
        this.itemImageEntityRepository = itemImageEntityRepository;
        this.adminEntityRepository = adminEntityRepository;
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
        CategoryEntity categoryEntity = categoryEntityRepository.findByCategoryName(itemDto.getCategoryName());
        if(categoryEntity == null) throw new Exception("Category with that name: " +itemDto.getCategoryName()+ " could not be found.");

        ItemEntity itemEntity = new ItemEntity();
        itemEntity.setItemName(itemDto.getItemName());
        itemEntity.setItemCount(itemDto.getItemCount());
        itemEntity.setItemPrice(itemDto.getItemPrice());
        itemEntity.setDiscount(itemDto.getDiscount());
        itemEntity.setCategoryEntity(categoryEntity);

        itemEntityRepository.save(itemEntity);

        for(String imageName : itemDto.getItemImageNames()) {
            ItemImageEntity itemImageEntity = new ItemImageEntity();
            itemImageEntity.setImageName(imageName);
            itemImageEntity.setItemEntity(itemEntity);
            itemImageEntityRepository.save(itemImageEntity);
        }
    }

    public void uploadProduct(Long adminId, String productName, float price, int count, float discount, String description, int categoryValue) {
        CategoryEntity categoryEntity = categoryEntityRepository.findAll().get(categoryValue);
        AdminEntity adminEntity = adminEntityRepository.findById(adminId).orElseThrow(() -> new RuntimeException("Admin with that id: " + adminId + " could not be found."));

        ItemEntity itemEntity = new ItemEntity();
        itemEntity.setAdminEntity(adminEntity);
        itemEntity.setItemName(productName);
        itemEntity.setItemCount(count);
        itemEntity.setItemPrice(price);
        itemEntity.setDiscount(discount);
        itemEntity.setCategoryEntity(categoryEntity);
        itemEntity.setDescription(description);

        itemEntityRepository.save(itemEntity);
    }
}
