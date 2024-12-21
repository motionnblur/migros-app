package com.example.MigrosBackend.controller.admin;

import com.example.MigrosBackend.dto.ItemDto;
import com.example.MigrosBackend.service.admin.AdminSupplyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("admin/supply")
public class AdminSupplyController {
    private final AdminSupplyService adminSupplyService;

    @Autowired
    public AdminSupplyController(AdminSupplyService adminSupplyService) {
        this.adminSupplyService = adminSupplyService;
    }

    @GetMapping("addCategory")
    private ResponseEntity<?> addCategory(@RequestParam String categoryName) throws Exception {
        try{
            adminSupplyService.addCategory(categoryName);
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @PostMapping("addItem")
    private ResponseEntity<?> addItem(@RequestBody ItemDto itemDto) throws Exception {
        try{
            adminSupplyService.addItem(itemDto);
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
}
