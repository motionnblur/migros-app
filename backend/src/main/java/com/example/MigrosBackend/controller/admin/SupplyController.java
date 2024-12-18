package com.example.MigrosBackend.controller.admin;

import com.example.MigrosBackend.dto.ItemDto;
import com.example.MigrosBackend.entity.CategoryEntity;
import com.example.MigrosBackend.entity.ItemEntity;
import com.example.MigrosBackend.repository.CategoryEntityRepository;
import com.example.MigrosBackend.repository.ItemEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("admin/supply")
public class SupplyController {
    @Autowired
    private CategoryEntityRepository categoryEntityRepository;
    @Autowired
    private ItemEntityRepository itemEntityRepository;

    @GetMapping("addCategory")
    private ResponseEntity<?> addCategory(@RequestParam String categoryName) {
        CategoryEntity categoryEntity = new CategoryEntity();
        categoryEntity.setCategoryName(categoryName);

        categoryEntityRepository.save(categoryEntity);

        return new ResponseEntity<>(HttpStatus.OK);
    }
    @PostMapping("addItem")
    private ResponseEntity<?> addItem(@RequestBody ItemDto itemDto) {
        CategoryEntity categoryEntity = categoryEntityRepository.findByCategoryName(itemDto.getCategoryName());

        ItemEntity itemEntity = new ItemEntity();
        itemEntity.setItemName(itemDto.getItemName());
        itemEntity.setItemCount(itemDto.getItemCount());
        itemEntity.setItemPrice(itemDto.getItemPrice());
        itemEntity.setDiscount(itemDto.getDiscount());
        itemEntity.setCategoryEntity(categoryEntity);

        itemEntityRepository.save(itemEntity);

        return new ResponseEntity<>(HttpStatus.OK);
    }
}
