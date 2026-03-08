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
import static org.mockito.ArgumentMatchers.anyLong;
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
    void updateProduct_DoesNotWriteFile_WhenImageMissing() throws IOException {
        // Arrange
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(new AdminEntity()));
        when(categoryEntityRepository.findByCategoryId(1)).thenReturn(new CategoryEntity());
        ProductEntity product = new ProductEntity();
        product.setId(100L);
        when(productEntityRepository.findById(100L)).thenReturn(Optional.of(product));

        // Act
        adminSupplyService.updateProduct(1L, 100L, "Name", "Sub", 10f, 5, 0f, "Desc", 1, null);

        // Assert
        verify(fileService, never()).writeFileToDisk(any(), anyString(), anyString());
        verify(productImageEntityRepository, never()).findByProductEntityId(anyLong());
        verify(productEntityRepository).save(product);
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

        // Ensure admin/product lookups pass so we reach the file write path
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(new AdminEntity()));
        when(categoryEntityRepository.findByCategoryId(1)).thenReturn(new CategoryEntity());
        when(productEntityRepository.findById(100L)).thenReturn(Optional.of(new ProductEntity()));

        // Simulate disk failure
        when(fileService.writeFileToDisk(any(), anyString(), anyString())).thenThrow(new IOException());

        // Act & Assert
        assertThrows(FileUploadFailedException.class, () ->
                adminSupplyService.updateProduct(1L, 100L, "Name", "Sub", 10f, 5, 0f, "Desc", 1, file)
        );
    }
}







