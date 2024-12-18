package com.example.MigrosBackend.service;

import com.example.MigrosBackend.entity.CategoryEntity;
import com.example.MigrosBackend.repository.CategoryEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserSupplyService {
    @Autowired
    private CategoryEntityRepository categoryEntityRepository;

    public List<CategoryEntity> getCategories() {
        return categoryEntityRepository.findAll();
    }
}
