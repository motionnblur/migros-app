package com.example.MigrosBackend.controller.user;

import com.example.MigrosBackend.entity.CategoryEntity;
import com.example.MigrosBackend.service.UserSupplyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("user/supply")
public class UserSupplyController {
    @Autowired
    private UserSupplyService userSupplyService;

    @GetMapping("getCategories")
    public List<CategoryEntity> getCategories() {
        return userSupplyService.getCategories();
    }
}
