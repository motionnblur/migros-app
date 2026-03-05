package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.admin.panel.*;
import com.example.MigrosBackend.dto.user.product.ProductDto;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductDescriptionEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.product.ProductImageEntity;
import com.example.MigrosBackend.exception.admin.AdminHasNoProductException;
import com.example.MigrosBackend.exception.admin.FileUploadFailedException;
import com.example.MigrosBackend.exception.admin.ProductNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductDescriptionEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
import com.example.MigrosBackend.service.global.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminSupplyServiceTest {
    @Mock
    private CategoryEntityRepository categoryEntityRepository;
    @Mock
    private ProductEntityRepository productEntityRepository;
    @Mock
    private ProductImageEntityRepository productImageEntityRepository;
    @Mock
    private AdminEntityRepository adminEntityRepository;
    @Mock
    private ProductDescriptionEntityRepository productDescriptionEntityRepository;
    @Mock
    private FileService fileService;

    @InjectMocks
    private AdminSupplyService adminSupplyService;

    private AdminEntity admin;
    private CategoryEntity category;

    @BeforeEach
    void setUp() {
        admin = new AdminEntity();
        admin.setId(1L);
        admin.setItemEntities(new ArrayList<>());

        category = new CategoryEntity();
        category.setId(10L);
        category.setCategoryName("Beverages");
    }

    @Test
    void addProduct_Success() {
        // Arrange
        AdminAddItemDto dto = new AdminAddItemDto();
        dto.setAdminId(1L);
        ProductDto pDto = new ProductDto();
        pDto.setProductName("Coke");
        dto.setProductDto(pDto);

        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(productEntityRepository.save(any(ProductEntity.class))).thenAnswer(i -> i.getArguments()[0]);

        // Act
        adminSupplyService.addProduct(dto);

        // Assert
        verify(adminEntityRepository, times(1)).save(admin);
        assertEquals(1, admin.getItemEntities().size());
        assertEquals("Coke", admin.getItemEntities().get(0).getProductName());
    }

    @Test
    void addCategory_ThrowsException_WhenCategoryExists() {
        // Arrange
        when(categoryEntityRepository.findByCategoryName("Beverages")).thenReturn(category);

        // Act & Assert
        assertThrows(GeneralException.class, () -> adminSupplyService.addCategory("Beverages"));
        verify(categoryEntityRepository, never()).save(any());
    }

    @Test
    void uploadProduct_Success() throws IOException {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "some-image-data".getBytes());

        Path mockPath = Paths.get("UploadFolder/image_123.png");

        when(fileService.writeFileToDisk(any(), anyString(), anyString())).thenReturn(mockPath);
        when(categoryEntityRepository.findByCategoryId(10)).thenReturn(category);
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(admin));

        // Act
        adminSupplyService.uploadProduct(1L, "Water", "Still", 5.0f, 100, 0.1f, "Fresh water", 10, file);

        // Assert
        verify(productEntityRepository, times(1)).save(any(ProductEntity.class));
        verify(productImageEntityRepository, times(1)).save(any(ProductImageEntity.class));
    }

    @Test
    void uploadProduct_ThrowsException_WhenNotPng() {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.jpg", "image/jpeg", "data".getBytes());

        // Act & Assert
        GeneralException ex = assertThrows(GeneralException.class, () ->
                adminSupplyService.uploadProduct(1L, "Water", "Still", 5.0f, 100, 0.1f, "Fresh", 10, file)
        );
        assertEquals("Only PNG files are allowed", ex.getMessage());
    }

    @Test
    void uploadProduct_ThrowsException_WhenFileUploadFails() throws IOException {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "data".getBytes());

        when(fileService.writeFileToDisk(any(), anyString(), anyString())).thenThrow(new IOException());

        // Act & Assert
        assertThrows(FileUploadFailedException.class, () ->
                adminSupplyService.uploadProduct(1L, "Water", "Still", 5.0f, 100, 0.1f, "Fresh", 10, file)
        );
    }

    @Test
    void updateProduct_Success() throws IOException {
        // Arrange
        Long adminId = 1L;
        Long productId = 100L;
        int categoryId = 5;

        // Mock MultipartFile
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "image-content".getBytes());

        // Mock Entities
        AdminEntity admin = new AdminEntity();
        admin.setId(adminId);

        CategoryEntity category = new CategoryEntity();
        category.setId((long) categoryId);

        ProductEntity existingProduct = new ProductEntity();
        existingProduct.setId(productId);

        ProductImageEntity existingImage = new ProductImageEntity();
        existingImage.setId(500L);
        existingImage.setImagePath("old/path.png");

        // Stubbing
        Path mockPath = Paths.get("UploadFolder/new_image.png");
        when(fileService.writeFileToDisk(any(), anyString(), anyString())).thenReturn(mockPath);
        when(categoryEntityRepository.findByCategoryId(categoryId)).thenReturn(category);
        when(adminEntityRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(productEntityRepository.findById(productId)).thenReturn(Optional.of(existingProduct));

        // The service calls .get(0) on the list returned by this repository
        when(productImageEntityRepository.findByProductEntityId(productId)).thenReturn(List.of(existingImage));

        // Act
        adminSupplyService.updateProduct(adminId, productId, "Updated Name", "SubCat",
                10.0f, 50, 0.2f, "New Desc", categoryId, file);

        // Assert
        // 1. Verify product updates
        verify(productEntityRepository).save(existingProduct);
        assertEquals("Updated Name", existingProduct.getProductName());
        assertEquals(admin, existingProduct.getAdminEntity());

        // 2. Verify image update
        verify(productImageEntityRepository).save(existingImage);
        assertEquals(mockPath.toString(), existingImage.getImagePath());
    }

    @Test
    void updateProduct_ThrowsException_WhenAdminNotFound() {
        // Arrange
        MockMultipartFile file = new MockMultipartFile("selectedImage", "test.png", "image/png", "data".getBytes());
        when(adminEntityRepository.findById(anyLong())).thenReturn(Optional.empty());

        // Act & Assert
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                adminSupplyService.updateProduct(1L, 100L, "Name", "Sub", 10f, 5, 0f, "Desc", 1, file)
        );
        assertTrue(ex.getMessage().contains("Admin with that id"));
    }

    @Test
    void updateProduct_ThrowsException_WhenProductNotFound() {
        // Arrange
        MockMultipartFile file = new MockMultipartFile("selectedImage", "test.png", "image/png", "data".getBytes());

        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(new AdminEntity()));
        when(productEntityRepository.findById(100L)).thenReturn(Optional.empty());

        // Act & Assert
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                adminSupplyService.updateProduct(1L, 100L, "Name", "Sub", 10f, 5, 0f, "Desc", 1, file)
        );
        assertTrue(ex.getMessage().contains("Product with that id"));
    }

    @Test
    void updateProduct_ThrowsFileUploadFailedException_OnIOException() throws IOException {
        // Arrange
        MockMultipartFile file = new MockMultipartFile("selectedImage", "test.png", "image/png", "data".getBytes());

        // Simulate disk failure
        when(fileService.writeFileToDisk(any(), anyString(), anyString())).thenThrow(new IOException());

        // Act & Assert
        assertThrows(FileUploadFailedException.class, () ->
                adminSupplyService.updateProduct(1L, 100L, "Name", "Sub", 10f, 5, 0f, "Desc", 1, file)
        );
    }

    @Test
    void getProductData_Success() {
        // Arrange
        ProductEntity product = new ProductEntity();
        product.setId(50L);
        product.setProductName("Apple");
        product.setCategoryEntity(category);

        when(productEntityRepository.findById(50L)).thenReturn(Optional.of(product));

        // Act
        ProductDto2 result = adminSupplyService.getProductData(50L);

        // Assert
        assertNotNull(result);
        assertEquals("Apple", result.getProductName());
        verify(productEntityRepository, times(1)).findById(50L);
    }

    @Test
    void deleteProduct_CallsRepository() {
        // Act
        adminSupplyService.deleteProduct(1L);

        // Assert
        verify(productEntityRepository, times(1)).deleteById(1L);
    }

    @Test
    void addProductDescription_ThrowsException_WhenProductNotFound() {
        // Arrange
        ProductDescriptionListDto dto = new ProductDescriptionListDto();
        dto.setProductId(1L);

        when(productEntityRepository.findById(1L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(ProductNotFoundException.class, () -> adminSupplyService.addProductDescription(dto));
    }

    @Test
    void addProductDescription_CreatesNew_WhenNoDescriptionsExist() {
        // Arrange
        Long productId = 1L;
        ProductEntity product = new ProductEntity();
        product.setId(productId);

        ProductDescriptionListDto dto = new ProductDescriptionListDto();
        dto.setProductId(productId);

        DescriptionsDto desc1 = new DescriptionsDto();
        desc1.setDescriptionTabName("Specs");
        desc1.setDescriptionTabContent("High quality");
        dto.setDescriptionList(List.of(desc1));

        when(productEntityRepository.findById(productId)).thenReturn(Optional.of(product));
        // Branch A: Existing descriptions list is empty
        when(productDescriptionEntityRepository.findByProductEntityId(productId)).thenReturn(new ArrayList<>());

        // Act
        adminSupplyService.addProductDescription(dto);

        // Assert
        // Verify save was called for the new description
        verify(productDescriptionEntityRepository, times(1)).save(any(ProductDescriptionEntity.class));
    }

    @Test
    void addProductDescription_UpdatesAndCreates_WhenDescriptionsAlreadyExist() {
        // Arrange
        Long productId = 1L;
        ProductEntity product = new ProductEntity();
        product.setId(productId);

        // 1. Existing entity in DB (ID: 100)
        ProductDescriptionEntity existingEntity = new ProductDescriptionEntity();
        existingEntity.setId(100L);
        existingEntity.setDescriptionTabName("Old Name");

        // 2. Incoming DTO with one UPDATE (ID: 100) and one NEW (ID: 200/null)
        ProductDescriptionListDto dto = new ProductDescriptionListDto();
        dto.setProductId(productId);

        DescriptionsDto updateDto = new DescriptionsDto();
        updateDto.setDescriptionId(100L);
        updateDto.setDescriptionTabName("Updated Name");

        DescriptionsDto newDto = new DescriptionsDto();
        newDto.setDescriptionId(200L); // ID doesn't exist in DB
        newDto.setDescriptionTabName("New Tab");

        dto.setDescriptionList(List.of(updateDto, newDto));

        when(productEntityRepository.findById(productId)).thenReturn(Optional.of(product));
        // Branch B: Existing list is NOT empty
        when(productDescriptionEntityRepository.findByProductEntityId(productId)).thenReturn(List.of(existingEntity));

        // Mocking findById for the loop logic
        when(productDescriptionEntityRepository.findById(100L)).thenReturn(Optional.of(existingEntity));
        when(productDescriptionEntityRepository.findById(200L)).thenReturn(Optional.empty());

        // Act
        adminSupplyService.addProductDescription(dto);

        // Assert
        // Should be called twice: once for the update, once for the new creation
        verify(productDescriptionEntityRepository, times(2)).save(any(ProductDescriptionEntity.class));
        assertEquals("Updated Name", existingEntity.getDescriptionTabName());
    }

    @Test
    void getAllAdminProducts_Success() {
        // Arrange
        Long adminId = 1L;
        int page = 0;
        int range = 5;
        Pageable pageable = PageRequest.of(page, range);

        ProductEntity product = new ProductEntity();
        product.setId(101L);
        product.setProductName("Organic Apples");

        Page<ProductEntity> productPage = new PageImpl<>(List.of(product));

        when(productEntityRepository.findByAdminEntityId(adminId, pageable))
                .thenReturn(productPage);

        // Act
        List<AdminProductPreviewDto> result = adminSupplyService.getAllAdminProducts(adminId, page, range);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(101L, result.get(0).getProductId());
        assertEquals("Organic Apples", result.get(0).getProductName());

        verify(productEntityRepository, times(1)).findByAdminEntityId(adminId, pageable);
    }

    @Test
    void getAllAdminProducts_ThrowsException_WhenPageIsEmpty() {
        // Arrange
        Long adminId = 99L;
        int page = 0;
        int range = 5;
        Pageable pageable = PageRequest.of(page, range);

        when(productEntityRepository.findByAdminEntityId(adminId, pageable))
                .thenReturn(Page.empty());

        // Act & Assert
        AdminHasNoProductException exception = assertThrows(AdminHasNoProductException.class, () -> {
            adminSupplyService.getAllAdminProducts(adminId, 0, 5);
        });

        assertEquals("Admin with id 99 has no products.", exception.getMessage());
        verify(productEntityRepository, times(1)).findByAdminEntityId(adminId, pageable);
    }

    @Test
    void getProductDescription_Success() {
        // Arrange
        Long productId = 10L;

        ProductDescriptionEntity desc = new ProductDescriptionEntity();
        desc.setId(101L);
        desc.setDescriptionTabName("Specs");
        desc.setDescriptionTabContent("100% Cotton");

        when(productDescriptionEntityRepository.findByProductEntityId(productId))
                .thenReturn(List.of(desc));

        // Act
        ProductDescriptionListDto result = adminSupplyService.getProductDescription(productId);

        // Assert
        assertNotNull(result);
        assertEquals(productId, result.getProductId());
        assertEquals(1, result.getDescriptionList().size());
        assertEquals("Specs", result.getDescriptionList().get(0).getDescriptionTabName());
    }

    @Test
    void getProductDescription_ThrowsException_WhenNoDescriptionsFound() {
        // Arrange
        Long productId = 99L;

        // Mocking an EMPTY list, which is what actually happens when no data is found
        when(productDescriptionEntityRepository.findByProductEntityId(productId))
                .thenReturn(Collections.emptyList());

        // Act & Assert
        assertThrows(ProductNotFoundException.class, () -> {
            adminSupplyService.getProductDescription(productId);
        });
    }

    @Test
    void deleteProductDescription_ShouldInvokeRepositoryDelete() {
        // Arrange
        Long descriptionId = 500L;

        // Act
        adminSupplyService.deleteProductDescription(descriptionId);

        verify(productDescriptionEntityRepository, times(1)).deleteById(descriptionId);
    }
}