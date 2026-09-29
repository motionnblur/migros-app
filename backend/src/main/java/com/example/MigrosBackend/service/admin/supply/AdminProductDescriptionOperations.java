package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.admin.panel.DescriptionsDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.entity.product.ProductDescriptionEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.admin.ProductNotFoundException;
import com.example.MigrosBackend.repository.product.ProductDescriptionEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class AdminProductDescriptionOperations {
    private final ProductEntityRepository productEntityRepository;
    private final ProductDescriptionEntityRepository productDescriptionEntityRepository;

    AdminProductDescriptionOperations(ProductEntityRepository productEntityRepository,
                                      ProductDescriptionEntityRepository productDescriptionEntityRepository) {
        this.productEntityRepository = productEntityRepository;
        this.productDescriptionEntityRepository = productDescriptionEntityRepository;
    }

    void addProductDescription(ProductDescriptionListDto productDescriptions) {
        ProductEntity productEntity = productEntityRepository.findById(productDescriptions.getProductId())
                .orElseThrow(() -> new ProductNotFoundException(productDescriptions.getProductId().toString()));

        List<ProductDescriptionEntity> productDescriptionEntities =
                productDescriptionEntityRepository.findByProductEntityId(productEntity.getId());
        if (productDescriptionEntities.isEmpty()) {
            for (DescriptionsDto item : productDescriptions.getDescriptionList()) {
                ProductDescriptionEntity productDescriptionEntity = new ProductDescriptionEntity();
                productDescriptionEntity.setDescriptionTabName(item.getDescriptionTabName());
                productDescriptionEntity.setDescriptionTabContent(item.getDescriptionTabContent());
                productDescriptionEntity.setProductEntity(productEntity);

                productDescriptionEntityRepository.save(productDescriptionEntity);
            }
            return;
        }

        List<DescriptionsDto> descriptionsDtoList = productDescriptions.getDescriptionList();
        for (int i = 0; i < descriptionsDtoList.size(); i++) {
            Optional<ProductDescriptionEntity> pE =
                    productDescriptionEntityRepository.findById(descriptionsDtoList.get(i).getDescriptionId());
            if (pE.isPresent()) {
                ProductDescriptionEntity productDescriptionEntity = pE.get();
                productDescriptionEntity.setDescriptionTabName(descriptionsDtoList.get(i).getDescriptionTabName());
                productDescriptionEntity.setDescriptionTabContent(descriptionsDtoList.get(i).getDescriptionTabContent());

                productDescriptionEntityRepository.save(productDescriptionEntity);
            } else {
                ProductDescriptionEntity productDescriptionEntity = new ProductDescriptionEntity();
                productDescriptionEntity.setDescriptionTabName(descriptionsDtoList.get(i).getDescriptionTabName());
                productDescriptionEntity.setDescriptionTabContent(descriptionsDtoList.get(i).getDescriptionTabContent());
                productDescriptionEntity.setProductEntity(productEntity);

                productDescriptionEntityRepository.save(productDescriptionEntity);
            }
        }
    }

    ProductDescriptionListDto getProductDescription(Long productId) {
        List<ProductDescriptionEntity> productDescriptionEntities =
                productDescriptionEntityRepository.findByProductEntityId(productId);
        if (productDescriptionEntities == null || productDescriptionEntities.isEmpty())
            throw new ProductNotFoundException(productId.toString());

        ProductDescriptionListDto productDescriptionDto = new ProductDescriptionListDto();
        productDescriptionDto.setProductId(productId);
        productDescriptionDto.setDescriptionList(new ArrayList<>());

        for (ProductDescriptionEntity item : productDescriptionEntities) {
            DescriptionsDto dto = new DescriptionsDto(
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
