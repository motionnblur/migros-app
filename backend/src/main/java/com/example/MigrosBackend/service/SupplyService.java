package com.example.MigrosBackend.service;

import com.example.MigrosBackend.dto.ItemDto;
import com.example.MigrosBackend.entity.CategoryEntity;
import com.example.MigrosBackend.entity.ItemEntity;
import com.example.MigrosBackend.repository.CategoryEntityRepository;
import com.example.MigrosBackend.repository.ItemEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SupplyService {
    @Autowired
    private CategoryEntityRepository categoryEntityRepository;
    @Autowired
    private ItemEntityRepository itemEntityRepository;

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
    }
}
