package com.example.MigrosBackend.controller.user;

import com.example.MigrosBackend.entity.ItemEntity;
import com.example.MigrosBackend.service.user.UserSupplyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("user/supply")
public class UserSupplyController {
    @Autowired
    private UserSupplyService userSupplyService;

    @GetMapping("getAllCategoryNames")
    public List<String> getAllCategoryNames() {
        return userSupplyService.getAllCategoryNames();
    }

    @GetMapping("getAllItemsFromCategory")
    public ResponseEntity<?> getAllItemsFromCategory(@RequestParam String categoryName, @RequestParam Long id_start, @RequestParam Long id_end) {
        try {
            List<ItemEntity> itemIDs = userSupplyService.getAllItemIDsFromCategory(categoryName, id_start, id_end);
            return ResponseEntity.ok(itemIDs);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
}
