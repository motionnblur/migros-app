package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionTabDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.entity.product.ProductDescriptionEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.admin.ProductNotFoundException;
import com.example.MigrosBackend.repository.product.ProductDescriptionEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class AdminProductDescriptionOperations {
    private final ProductEntityRepository productEntityRepository;
    private final ProductDescriptionEntityRepository productDescriptionEntityRepository;

    AdminProductDescriptionOperations(ProductEntityRepository productEntityRepository,
                                      ProductDescriptionEntityRepository productDescriptionEntityRepository) {
        this.productEntityRepository = productEntityRepository;
        this.productDescriptionEntityRepository = productDescriptionEntityRepository;
    }

    @Transactional
    public void addProductDescription(ProductDescriptionListDto productDescriptions) {
        ProductEntity productEntity = productEntityRepository.findById(productDescriptions.getProductId())
                .orElseThrow(() -> new ProductNotFoundException(productDescriptions.getProductId().toString()));

        List<ProductDescriptionEntity> existingEntities =
                productDescriptionEntityRepository.findByProductEntityId(productEntity.getId());
        boolean productHasDescriptions = !existingEntities.isEmpty();

        List<ProductDescriptionEntity> entitiesToSave = new ArrayList<>();
        for (ProductDescriptionTabDto item : productDescriptions.getDescriptionList()) {
            entitiesToSave.add(resolveDescription(item, productHasDescriptions, productEntity));
        }

        productDescriptionEntityRepository.saveAll(entitiesToSave);
    }

    private ProductDescriptionEntity resolveDescription(ProductDescriptionTabDto item,
                                                        boolean productHasDescriptions,
                                                        ProductEntity productEntity) {
        if (productHasDescriptions) {
            Optional<ProductDescriptionEntity> existingEntity =
                    productDescriptionEntityRepository.findById(item.descriptionId());
            if (existingEntity.isPresent()) {
                ProductDescriptionEntity productDescriptionEntity = existingEntity.get();
                productDescriptionEntity.setDescriptionTabName(item.tabName());
                productDescriptionEntity.setDescriptionTabContent(item.tabContent());
                return productDescriptionEntity;
            }
        }

        ProductDescriptionEntity productDescriptionEntity = new ProductDescriptionEntity();
        productDescriptionEntity.setDescriptionTabName(item.tabName());
        productDescriptionEntity.setDescriptionTabContent(item.tabContent());
        productDescriptionEntity.setProductEntity(productEntity);
        return productDescriptionEntity;
    }

    ProductDescriptionListDto getProductDescription(Long productId) {
        List<ProductDescriptionEntity> productDescriptionEntities =
                productDescriptionEntityRepository.findByProductEntityId(productId);
        if (productDescriptionEntities.isEmpty())
            throw new ProductNotFoundException(productId.toString());

        ProductDescriptionListDto productDescriptionDto = new ProductDescriptionListDto();
        productDescriptionDto.setProductId(productId);
        productDescriptionDto.setDescriptionList(new ArrayList<>());

        for (ProductDescriptionEntity item : productDescriptionEntities) {
            ProductDescriptionTabDto dto = new ProductDescriptionTabDto(
                    item.getId(),
                    item.getDescriptionTabName(),
                    item.getDescriptionTabContent()
            );

            productDescriptionDto.getDescriptionList().add(dto);
        }

        return productDescriptionDto;
    }

    void deleteProductDescription(Long descriptionId) {
        productDescriptionEntityRepository.deleteById(descriptionId);
    }
}
