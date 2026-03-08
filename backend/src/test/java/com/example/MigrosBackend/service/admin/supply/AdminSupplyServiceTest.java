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
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void getAllAdminProducts_shouldReturnMappedPreviews() {
        // Arrange
        ProductEntity first = new ProductEntity();
        first.setId(1L);
        first.setProductName("Apple");
        ProductEntity second = new ProductEntity();
        second.setId(2L);
        second.setProductName("Banana");

        Page<ProductEntity> page = new PageImpl<>(List.of(first, second), PageRequest.of(0, 2), 2);
        when(productEntityRepository.findByAdminEntityId(eq(10L), any(Pageable.class))).thenReturn(page);

        // Act
        List<AdminProductPreviewDto> result = adminSupplyService.getAllAdminProducts(10L, 0, 2);

        // Assert
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getProductId());
        assertEquals("Apple", result.get(0).getProductName());
        assertEquals(2L, result.get(1).getProductId());
        assertEquals("Banana", result.get(1).getProductName());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(productEntityRepository).findByAdminEntityId(eq(10L), pageableCaptor.capture());
        assertEquals(PageRequest.of(0, 2), pageableCaptor.getValue());
    }

    @Test
    void getAllAdminProducts_shouldThrowAdminHasNoProductException_whenEmpty() {
        Page<ProductEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 5), 0);
        when(productEntityRepository.findByAdminEntityId(eq(1L), any(Pageable.class))).thenReturn(emptyPage);

        assertThrows(AdminHasNoProductException.class, () -> adminSupplyService.getAllAdminProducts(1L, 0, 5));
    }

    @Test
    void deleteProduct_shouldDeleteById() {
        adminSupplyService.deleteProduct(55L);

        verify(productEntityRepository).deleteById(55L);
    }

    @Test
    void getProductData_shouldReturnMappedDto() {
        CategoryEntity categoryEntity = new CategoryEntity();
        categoryEntity.setId(9L);

        ProductEntity product = new ProductEntity();
        product.setId(7L);
        product.setProductName("Milk");
        product.setSubcategoryName("Dairy");
        product.setProductPrice(12.5f);
        product.setProductCount(20);
        product.setProductDiscount(0.1f);
        product.setProductDescription("Fresh milk");
        product.setCategoryEntity(categoryEntity);

        when(productEntityRepository.findById(7L)).thenReturn(Optional.of(product));

        ProductDto2 result = adminSupplyService.getProductData(7L);

        assertEquals("Milk", result.getProductName());
        assertEquals("Dairy", result.getSubCategoryName());
        assertEquals(12.5f, result.getProductPrice());
        assertEquals(20, result.getProductCount());
        assertEquals(0.1f, result.getProductDiscount());
        assertEquals("Fresh milk", result.getProductDescription());
        assertEquals(9, result.getProductCategoryId());
    }

    @Test
    void getProductData_shouldThrowProductNotFoundException_whenMissing() {
        when(productEntityRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> adminSupplyService.getProductData(99L));
    }

    @Test
    void addProductDescription_shouldCreateWhenNoExistingDescriptions() {
        ProductEntity product = new ProductEntity();
        product.setId(101L);

        ProductDescriptionListDto dto = new ProductDescriptionListDto();
        dto.setProductId(101L);
        dto.setDescriptionList(List.of(
                new DescriptionsDto(null, "Tab1", "Content1"),
                new DescriptionsDto(null, "Tab2", "Content2")
        ));

        when(productEntityRepository.findById(101L)).thenReturn(Optional.of(product));
        when(productDescriptionEntityRepository.findByProductEntityId(101L)).thenReturn(Collections.emptyList());

        adminSupplyService.addProductDescription(dto);

        ArgumentCaptor<ProductDescriptionEntity> captor = ArgumentCaptor.forClass(ProductDescriptionEntity.class);
        verify(productDescriptionEntityRepository, times(2)).save(captor.capture());
        List<ProductDescriptionEntity> saved = captor.getAllValues();
        assertEquals("Tab1", saved.get(0).getDescriptionTabName());
        assertEquals("Content1", saved.get(0).getDescriptionTabContent());
        assertEquals(product, saved.get(0).getProductEntity());
        assertEquals("Tab2", saved.get(1).getDescriptionTabName());
        assertEquals("Content2", saved.get(1).getDescriptionTabContent());
        assertEquals(product, saved.get(1).getProductEntity());
    }

    @Test
    void addProductDescription_shouldUpdateExistingAndCreateMissing() {
        ProductEntity product = new ProductEntity();
        product.setId(102L);

        ProductDescriptionEntity existing = new ProductDescriptionEntity();
        existing.setId(11L);
        existing.setProductEntity(product);

        ProductDescriptionListDto dto = new ProductDescriptionListDto();
        dto.setProductId(102L);
        dto.setDescriptionList(List.of(
                new DescriptionsDto(11L, "Updated", "Updated content"),
                new DescriptionsDto(12L, "New", "New content")
        ));

        when(productEntityRepository.findById(102L)).thenReturn(Optional.of(product));
        when(productDescriptionEntityRepository.findByProductEntityId(102L)).thenReturn(List.of(existing));
        when(productDescriptionEntityRepository.findById(11L)).thenReturn(Optional.of(existing));
        when(productDescriptionEntityRepository.findById(12L)).thenReturn(Optional.empty());

        adminSupplyService.addProductDescription(dto);

        ArgumentCaptor<ProductDescriptionEntity> captor = ArgumentCaptor.forClass(ProductDescriptionEntity.class);
        verify(productDescriptionEntityRepository, times(2)).save(captor.capture());
        List<ProductDescriptionEntity> saved = captor.getAllValues();

        ProductDescriptionEntity updated = saved.stream()
                .filter(item -> item.getId() != null && item.getId().equals(11L))
                .findFirst()
                .orElse(null);
        assertNotNull(updated);
        assertEquals("Updated", updated.getDescriptionTabName());
        assertEquals("Updated content", updated.getDescriptionTabContent());

        ProductDescriptionEntity created = saved.stream()
                .filter(item -> item.getId() == null || !item.getId().equals(11L))
                .findFirst()
                .orElse(null);
        assertNotNull(created);
        assertEquals("New", created.getDescriptionTabName());
        assertEquals("New content", created.getDescriptionTabContent());
        assertEquals(product, created.getProductEntity());
    }

    @Test
    void getProductDescription_shouldReturnDescriptionList() {
        ProductDescriptionEntity first = new ProductDescriptionEntity();
        first.setId(1L);
        first.setDescriptionTabName("A");
        first.setDescriptionTabContent("A content");
        ProductDescriptionEntity second = new ProductDescriptionEntity();
        second.setId(2L);
        second.setDescriptionTabName("B");
        second.setDescriptionTabContent("B content");

        when(productDescriptionEntityRepository.findByProductEntityId(200L)).thenReturn(List.of(first, second));

        ProductDescriptionListDto result = adminSupplyService.getProductDescription(200L);

        assertEquals(200L, result.getProductId());
        assertEquals(2, result.getDescriptionList().size());
        assertEquals(1L, result.getDescriptionList().get(0).getDescriptionId());
        assertEquals("A", result.getDescriptionList().get(0).getDescriptionTabName());
        assertEquals("A content", result.getDescriptionList().get(0).getDescriptionTabContent());
    }

    @Test
    void getProductDescription_shouldThrowProductNotFoundException_whenEmpty() {
        when(productDescriptionEntityRepository.findByProductEntityId(300L)).thenReturn(Collections.emptyList());

        assertThrows(ProductNotFoundException.class, () -> adminSupplyService.getProductDescription(300L));
    }

    @Test
    void deleteProductDescription_shouldDeleteById() {
        adminSupplyService.deleteProductDescription(88L);

        verify(productDescriptionEntityRepository).deleteById(88L);
    }
}




