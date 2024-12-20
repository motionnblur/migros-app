package com.example.MigrosBackend.service.user;

import com.example.MigrosBackend.dto.ItemPreviewDto;
import com.example.MigrosBackend.dto.ItemDto;
import com.example.MigrosBackend.entity.CategoryEntity;
import com.example.MigrosBackend.entity.ItemEntity;
import com.example.MigrosBackend.entity.ItemImageEntity;
import com.example.MigrosBackend.repository.CategoryEntityRepository;
import com.example.MigrosBackend.repository.ItemEntityRepository;
import com.example.MigrosBackend.repository.ItemImageEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserSupplyService {
    @Autowired
    private CategoryEntityRepository categoryEntityRepository;
    @Autowired
    private ItemEntityRepository itemEntityRepository;
    @Autowired
    private ItemImageEntityRepository itemImageEntityRepository;

    public List<String> getAllCategoryNames() {
        return categoryEntityRepository.findAll().stream().map(CategoryEntity::getCategoryName).toList();
    }

    public List<ItemPreviewDto> getItemsFromCategory(Long categoryId, int page, int itemRange) throws Exception {
        boolean b = categoryEntityRepository.existsById(categoryId);
        if(!b) throw new Exception("Category with that ID: " +categoryId+ " could not be found.");

        //Pageable pageable = PageRequest.of(page, itemRange, Sort.by("id").ascending());
        Pageable pageable = PageRequest.of(page, itemRange);

        Page<ItemEntity> entities =  itemEntityRepository.findByCategoryEntityId(categoryId, pageable);

        return entities.stream().map(itemEntity -> {
            ItemPreviewDto itemDto = new ItemPreviewDto();
            itemDto.setItemImageName(itemEntity.getItemImageEntities().get(0).getImageName());
            itemDto.setItemTitle(itemEntity.getItemName());
            itemDto.setItemPrice(itemEntity.getItemPrice());
            return itemDto;
        }).collect(Collectors.toList());
    }
    public List<String> getItemImageNames(Long itemId) {
        List<ItemImageEntity> itemImageEntity = itemImageEntityRepository.findByItemEntityId(itemId);
        return itemImageEntity.stream().map(ItemImageEntity::getImageName).toList();
    }
}
