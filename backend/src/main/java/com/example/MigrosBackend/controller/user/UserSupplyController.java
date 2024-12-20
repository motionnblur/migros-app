package com.example.MigrosBackend.controller.user;

import com.example.MigrosBackend.dto.ItemPreviewDto;
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

    @GetMapping("getItemsFromCategory")
    public ResponseEntity<?> getItemsFromCategory(@RequestParam Long categoryId, @RequestParam int page, @RequestParam int itemRange) {
        try {
            List<ItemPreviewDto> itemIDs = userSupplyService.getItemsFromCategory(categoryId, page, itemRange);
            return ResponseEntity.ok(itemIDs);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    @GetMapping("getItemImageNames")
    public List<String> getItemImageNames(@RequestParam Long itemId) {
        return userSupplyService.getItemImageNames(itemId);
    }
}
