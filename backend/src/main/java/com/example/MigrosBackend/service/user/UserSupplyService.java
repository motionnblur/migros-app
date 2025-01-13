package com.example.MigrosBackend.service.user;

import com.example.MigrosBackend.dto.user.ProductPreviewDto;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.product.ProductImageEntity;
import com.example.MigrosBackend.repository.CategoryEntityRepository;
import com.example.MigrosBackend.repository.ProductEntityRepository;
import com.example.MigrosBackend.repository.ProductImageEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserSupplyService {
    private final CategoryEntityRepository categoryEntityRepository;
    private final ProductEntityRepository productEntityRepository;
    private final ProductImageEntityRepository productImageEntityRepository;

    @Autowired
    public UserSupplyService(
            CategoryEntityRepository categoryEntityRepository,
            ProductEntityRepository productEntityRepository,
            ProductImageEntityRepository productImageEntityRepository
    ) {
        this.categoryEntityRepository = categoryEntityRepository;
        this.productEntityRepository = productEntityRepository;
        this.productImageEntityRepository = productImageEntityRepository;
    }

    public List<String> getAllCategoryNames() {
        return categoryEntityRepository.findAll().stream().map(CategoryEntity::getCategoryName).toList();
    }

    public List<ProductPreviewDto> getProductsFromCategory(Long categoryId, int page, int itemRange) throws Exception {
        boolean b = categoryEntityRepository.existsById(categoryId);
        if(!b) throw new Exception("Category with that ID: " +categoryId+ " could not be found.");

        //Pageable pageable = PageRequest.of(page, itemRange, Sort.by("id").ascending());
        Pageable pageable = PageRequest.of(page, itemRange);
        Page<ProductEntity> entities =  productEntityRepository.findByCategoryEntityId(categoryId, pageable);
        if(entities.isEmpty()) throw new Exception("Category with that ID: " +categoryId+ " has no products.");

        return entities.stream().map(itemEntity -> {
            ProductPreviewDto itemDto = new ProductPreviewDto();
            itemDto.setProductId(itemEntity.getId());
            itemDto.setProductName(itemEntity.getProductName());
            itemDto.setProductPrice(itemEntity.getProductPrice());

            return itemDto;
        }).collect(Collectors.toList());
    }
    public List<String> getProductImageNames(Long itemId) {
        List<ProductImageEntity> productImageEntity = productImageEntityRepository.findByProductEntityId(itemId);
        return productImageEntity.stream().map(ProductImageEntity::getImagePath).toList();
    }

    public ResponseEntity<Resource> getProductImage(Long itemId) throws Exception {
        ProductImageEntity productImageEntity = productImageEntityRepository.findByProductEntityId(itemId).get(0);
        String filename = productImageEntity.getImagePath();

        Resource resource = new UrlResource(Paths.get(filename).toUri());

        if (resource.exists() && resource.isReadable()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);
        } else {
            throw new Exception("File not found");
        }
    }

    public ResponseEntity<?> getProductCountsFromCategory(Long categoryId) {
        boolean b = categoryEntityRepository.existsById(categoryId);
        if(!b) return new ResponseEntity<>("Category with that ID: " +categoryId+ " could not be found.", HttpStatus.BAD_REQUEST);

        return ResponseEntity.ok(productEntityRepository.countByCategoryEntityId(categoryId));
    }
}
