package com.example.MigrosBackend.service.user;

import com.example.MigrosBackend.entity.CategoryEntity;
import com.example.MigrosBackend.entity.ItemEntity;
import com.example.MigrosBackend.repository.CategoryEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserSupplyService {
    @Autowired
    private CategoryEntityRepository categoryEntityRepository;

    public List<String> getAllCategoryNames() {
        return categoryEntityRepository.findAll().stream().map(CategoryEntity::getCategoryName).toList();
    }

    public List<ItemEntity> getAllItemIDsFromCategory(String categoryName, Long id_start, Long id_end) throws Exception {
        CategoryEntity categoryEntity = categoryEntityRepository.findByCategoryName(categoryName);
        if(categoryEntity == null) throw new Exception("Category with that name: " +categoryName+ " could not be found.");
        List<ItemEntity> itemEntities = categoryEntity.getItemEntities();
        if(itemEntities.isEmpty()) throw new Exception("categoryName" + " has no items.");

        return categoryEntityRepository.getAllItemIDsFromCategory(categoryName, id_start, id_end);
    }
}
