package com.example.MigrosBackend.controller.admin;

import com.example.MigrosBackend.dto.ItemDto;
import com.example.MigrosBackend.entity.CategoryEntity;
import com.example.MigrosBackend.entity.ItemEntity;
import com.example.MigrosBackend.repository.CategoryEntityRepository;
import com.example.MigrosBackend.repository.ItemEntityRepository;
import com.example.MigrosBackend.service.SupplyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("admin/supply")
public class SupplyController {
    @Autowired
    private SupplyService supplyService;

    @GetMapping("addCategory")
    private ResponseEntity<?> addCategory(@RequestParam String categoryName) throws Exception {
        try{
            supplyService.addCategory(categoryName);
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @PostMapping("addItem")
    private ResponseEntity<?> addItem(@RequestBody ItemDto itemDto) throws Exception {
        try{
            supplyService.addItem(itemDto);
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
}
