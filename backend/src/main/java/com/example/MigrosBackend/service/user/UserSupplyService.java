package com.example.MigrosBackend.service.user;

import com.example.MigrosBackend.dto.ItemDto;
import com.example.MigrosBackend.entity.CategoryEntity;
import com.example.MigrosBackend.entity.ItemEntity;
import com.example.MigrosBackend.repository.CategoryEntityRepository;
import com.example.MigrosBackend.repository.ItemEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserSupplyService {
    @Autowired
    private CategoryEntityRepository categoryEntityRepository;
    @Autowired
    private ItemEntityRepository itemEntityRepository;

    public List<String> getAllCategoryNames() {
        return categoryEntityRepository.findAll().stream().map(CategoryEntity::getCategoryName).toList();
    }

    public List<ItemDto> getItemsFromCategory(Long categoryId, int page, int itemRange) throws Exception {
        boolean b = categoryEntityRepository.existsById(categoryId);
        if(!b) throw new Exception("Category with that ID: " +categoryId+ " could not be found.");

        //Pageable pageable = PageRequest.of(page, itemRange, Sort.by("id").ascending());
        Pageable pageable = PageRequest.of(page, itemRange);

        Page<ItemEntity> entities =  itemEntityRepository.findByCategoryEntityId(categoryId, pageable);

        return entities.stream().map(itemEntity -> {
            ItemDto itemDto = new ItemDto();
            itemDto.setItemName(itemEntity.getItemName());
            itemDto.setItemCount(itemEntity.getItemCount());
            itemDto.setItemPrice(itemEntity.getItemPrice());
            itemDto.setDiscount(itemEntity.getDiscount());
            itemDto.setCategoryName(itemEntity.getCategoryEntity().getCategoryName());
            return itemDto;
        }).collect(Collectors.toList());
    }
}
