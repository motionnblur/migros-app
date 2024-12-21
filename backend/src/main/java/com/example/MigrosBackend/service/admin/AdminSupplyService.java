package com.example.MigrosBackend.service.admin;

import com.example.MigrosBackend.dto.ItemDto;
import com.example.MigrosBackend.entity.CategoryEntity;
import com.example.MigrosBackend.entity.ItemEntity;
import com.example.MigrosBackend.entity.ItemImageEntity;
import com.example.MigrosBackend.repository.CategoryEntityRepository;
import com.example.MigrosBackend.repository.ItemEntityRepository;
import com.example.MigrosBackend.repository.ItemImageEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdminSupplyService {
    private final CategoryEntityRepository categoryEntityRepository;
    private final ItemEntityRepository itemEntityRepository;
    private final ItemImageEntityRepository itemImageEntityRepository;

    @Autowired
    public AdminSupplyService(
            CategoryEntityRepository categoryEntityRepository,
            ItemEntityRepository itemEntityRepository,
            ItemImageEntityRepository itemImageEntityRepository
    ) {
        this.categoryEntityRepository = categoryEntityRepository;
        this.itemEntityRepository = itemEntityRepository;
        this.itemImageEntityRepository = itemImageEntityRepository;
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
}
