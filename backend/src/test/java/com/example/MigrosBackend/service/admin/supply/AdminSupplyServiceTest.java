package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.admin.panel.AdminAddItemDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionTabDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.admin.panel.AdminProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.ProductDetailDto;
import com.example.MigrosBackend.dto.user.product.ProductDto;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductDescriptionEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.product.ProductImageEntity;
import com.example.MigrosBackend.exception.admin.AdminNotFoundException;
import com.example.MigrosBackend.exception.admin.FileUploadFailedException;
import com.example.MigrosBackend.exception.admin.ProductEditConflictException;
import com.example.MigrosBackend.exception.admin.ProductNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductDescriptionEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
import com.example.MigrosBackend.service.global.FileService;
import jakarta.persistence.EntityManager;
import com.example.MigrosBackend.service.user.supply.UserCatalogReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    private ProductImageCleanupQueue imageCleanupQueue;
    @Mock
    private FileService fileService;
    @Mock
    private EntityManager entityManager;

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

        adminSupplyService = new AdminSupplyService(
                categoryEntityRepository,
                productEntityRepository,
                productImageEntityRepository,
                adminEntityRepository,
                fileService,
                new AdminProductDescriptionOperations(productEntityRepository, productDescriptionEntityRepository),
                new AdminProductImageOperations(fileService),
                new ProductCreationPolicy(),
                imageCleanupQueue,
                new UserCatalogReadService(
                        categoryEntityRepository,
                        productEntityRepository,
                        productImageEntityRepository,
                        productDescriptionEntityRepository,
                        fileService),
                // Only the image-replacement path calls entityManager.lock, to
                // force the version advance; a mock is enough for every other
                // assertion here, and the version behaviour itself is proven
                // against real PostgreSQL in ProductImageVersionAdvancePostgresTest.
                entityManager);
    }

    /**
     * The JSON creation path used to be asserted by a mocked {@code save} that
     * accepted whatever it was handed, which is how an entity with a null
     * description, a null discount and no category was able to pass as a
     * success. The row it describes cannot be inserted - the description and the
     * discount are {@code NOT NULL} and the category key has no default - so
     * what is asserted here is the entity that is actually handed to the
     * repository, and {@code ProductCreationPostgresTest} asserts the insert.
     */
    @Test
    void addProduct_SavesAProductCarryingEveryNotNullColumnAndBothReferences() {
        givenAdminAndCategory();
        when(productEntityRepository.save(any(ProductEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        adminSupplyService.addProduct(addItemDto("Coke", "Cola", "1.50", 12, "0.10", "Iced cola",
                "Beverages"));

        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        verify(productEntityRepository).save(captor.capture());
        ProductEntity saved = captor.getValue();

        assertEquals("Coke", saved.getProductName());
        assertEquals("Cola", saved.getSubcategoryName());
        assertEquals(12, saved.getProductCount());
        assertEquals(0, new BigDecimal("1.50").compareTo(saved.getProductPrice()));
        assertEquals(0, new BigDecimal("0.10").compareTo(saved.getProductDiscount()));
        assertEquals("Iced cola", saved.getProductDescription(),
                "product_description is NOT NULL: leaving it null made this endpoint uninsertable");
        assertSame(admin, saved.getAdminEntity());
        assertSame(category, saved.getCategoryEntity(),
                "a product with no category key is a row nothing can list it under");
    }

    /**
     * The one insert already carries the admin key, so the extra cascading write
     * through the admin's inverse collection - which could persist a
     * partially-initialised product a second time - has no reason to exist.
     */
    @Test
    void addProduct_DoesNotWriteTheAdminASecondTime() {
        givenAdminAndCategory();

        adminSupplyService.addProduct(addItemDto("Coke", "Cola", "1.50", 12, "0.10", "Iced cola",
                "Beverages"));

        verify(adminEntityRepository, never()).save(any());
    }

    /**
     * An omitted description and an omitted discount both mean "none", matching
     * what the multipart path has always stored. Neither is an error, and neither
     * may reach the database as null.
     */
    @Test
    void addProduct_TreatsAnAbsentDescriptionAndDiscountAsNone() {
        givenAdminAndCategory();

        AdminAddItemDto dto = addItemDto("Coke", "Cola", "1.50", 12, null, null, "Beverages");
        adminSupplyService.addProduct(dto);

        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        verify(productEntityRepository).save(captor.capture());
        ProductEntity saved = captor.getValue();

        assertEquals("", saved.getProductDescription());
        assertEquals(0, BigDecimal.ZERO.compareTo(saved.getProductDiscount()));
    }

    /**
     * A name matching no category is the administrator's mistake, and the
     * response has to say which name failed. Inventing a category to file it
     * under would be a worse outcome than refusing.
     */
    @Test
    void addProduct_RejectsAnUnknownCategoryName() {
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(categoryEntityRepository.findAllByCategoryName("Nonexistent")).thenReturn(List.of());

        GeneralException ex = assertThrows(GeneralException.class, () -> adminSupplyService.addProduct(
                addItemDto("Coke", "Cola", "1.50", 12, "0.10", "Iced cola", "Nonexistent")));

        assertEquals("Unknown category name: Nonexistent", ex.getMessage());
        verify(productEntityRepository, never()).save(any());
    }

    /**
     * {@code category_name} has no unique constraint, so a name that matches two
     * rows is a real state this database can be in. Choosing one of them would
     * file the product under a category the administrator did not pick.
     */
    @Test
    void addProduct_RejectsACategoryNameThatMatchesMoreThanOneCategory() {
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(admin));
        CategoryEntity duplicate = new CategoryEntity();
        duplicate.setId(11L);
        when(categoryEntityRepository.findAllByCategoryName("Beverages")).thenReturn(List.of(category, duplicate));

        GeneralException ex = assertThrows(GeneralException.class, () -> adminSupplyService.addProduct(
                addItemDto("Coke", "Cola", "1.50", 12, "0.10", "Iced cola", "Beverages")));

        assertEquals("Category name matches more than one category: Beverages", ex.getMessage());
        verify(productEntityRepository, never()).save(any());
    }

    @Test
    void addProduct_RejectsABlankCategoryName() {
        assertThrows(GeneralException.class, () -> adminSupplyService.addProduct(
                addItemDto("Coke", "Cola", "1.50", 12, "0.10", "Iced cola", "   ")));

        verify(productEntityRepository, never()).save(any());
        verify(categoryEntityRepository, never()).findAllByCategoryName(any());
    }

    @Test
    void addProduct_RejectsAnUnknownAdmin() {
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(AdminNotFoundException.class, () -> adminSupplyService.addProduct(
                addItemDto("Coke", "Cola", "1.50", 12, "0.10", "Iced cola", "Beverages")));

        verify(productEntityRepository, never()).save(any());
    }

    /**
     * A value longer than the column is a 400 naming the field, not an integrity
     * error raised by the driver during the insert.
     */
    @Test
    void addProduct_RejectsAProductNameLongerThanTheColumn() {
        String tooLong = "x".repeat(256);

        GeneralException ex = assertThrows(GeneralException.class, () -> adminSupplyService.addProduct(
                addItemDto(tooLong, "Cola", "1.50", 12, "0.10", "Iced cola", "Beverages")));

        assertEquals("Product name must not exceed 255 characters", ex.getMessage());
        verify(productEntityRepository, never()).save(any());
    }

    @Test
    void addProduct_RejectsADescriptionLongerThanTheColumn() {

        GeneralException ex = assertThrows(GeneralException.class, () -> adminSupplyService.addProduct(
                addItemDto("Coke", "Cola", "1.50", 12, "0.10", "x".repeat(256), "Beverages")));

        assertEquals("Product description must not exceed 255 characters", ex.getMessage());
        verify(productEntityRepository, never()).save(any());
    }

    /**
     * {@code NUMERIC(19, 2)} holds seventeen integer digits. Beyond that the
     * insert either fails or - worse - the value is accepted and rounded.
     */
    @Test
    void addProduct_RejectsAPriceTheMoneyColumnCannotHold() {

        GeneralException ex = assertThrows(GeneralException.class, () -> adminSupplyService.addProduct(
                addItemDto("Coke", "Cola", "100000000000000000.00", 12, "0.10", "Iced cola", "Beverages")));

        assertEquals("Product price must not exceed 99999999999999999.99", ex.getMessage());
        verify(productEntityRepository, never()).save(any());
    }

    @Test
    void addProduct_RejectsANegativeCount() {

        GeneralException ex = assertThrows(GeneralException.class, () -> adminSupplyService.addProduct(
                addItemDto("Coke", "Cola", "1.50", -1, "0.10", "Iced cola", "Beverages")));

        assertEquals("Product count cannot be negative", ex.getMessage());
        verify(productEntityRepository, never()).save(any());
    }

    @Test
    void addProduct_RejectsADiscountAboveOneHundred() {

        GeneralException ex = assertThrows(GeneralException.class, () -> adminSupplyService.addProduct(
                addItemDto("Coke", "Cola", "1.50", 12, "100.01", "Iced cola", "Beverages")));

        assertEquals("Product discount must be between 0 and 100", ex.getMessage());
        verify(productEntityRepository, never()).save(any());
    }

    private void givenAdminAndCategory() {
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(categoryEntityRepository.findAllByCategoryName("Beverages")).thenReturn(List.of(category));
    }

    private AdminAddItemDto addItemDto(String productName, String subCategoryName, String price,
                                       int count, String discount, String description,
                                       String categoryName) {
        ProductDto productDto = new ProductDto();
        productDto.setProductName(productName);
        productDto.setSubCategoryName(subCategoryName);
        productDto.setProductPrice(price == null ? null : new BigDecimal(price));
        productDto.setProductCount(count);
        productDto.setProductDiscount(discount == null ? null : new BigDecimal(discount));
        productDto.setProductDescription(description);
        productDto.setCategoryName(categoryName);

        AdminAddItemDto dto = new AdminAddItemDto();
        dto.setAdminId(1L);
        dto.setProductDto(productDto);
        return dto;
    }

    @Test
    void addCategory_ThrowsException_WhenCategoryExists() {
        when(categoryEntityRepository.findByCategoryName("Beverages")).thenReturn(category);

        assertThrows(GeneralException.class, () -> adminSupplyService.addCategory("Beverages"));
        verify(categoryEntityRepository, never()).save(any());
    }

    @Test
    void uploadProduct_Success() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "some-image-data".getBytes());
        Path mockPath = Paths.get("UploadFolder/image_123.png");

        when(fileService.writeFileToDisk(any(), anyString())).thenReturn(mockPath);
        when(categoryEntityRepository.findByCategoryId(10)).thenReturn(category);
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(admin));

        adminSupplyService.uploadProduct(1L, "Water", "Still", new BigDecimal("5.0"), 100, new BigDecimal("0.1"), "Fresh water", 10, null, null, file);

        verify(productEntityRepository).save(any(ProductEntity.class));
        verify(productImageEntityRepository).save(any(ProductImageEntity.class));
    }

    @Test
    void uploadProduct_ValidatesReferencesBeforeWritingAnyFile() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "some-image-data".getBytes());

        GeneralException exception = assertThrows(GeneralException.class, () ->
                adminSupplyService.uploadProduct(1L, "Water", "Still", new BigDecimal("5.00"),
                        100, new BigDecimal("0.10"), "Fresh water", 10, null, null, file));

        assertEquals("Invalid category value: 10", exception.getMessage());
        // The file is the only part of this operation a database rollback cannot
        // undo, so nothing may reach disk before every reference is resolved.
        verify(fileService, never()).writeFileToDisk(any(), anyString());
        verify(adminEntityRepository, never()).findById(anyLong());
        verify(productEntityRepository, never()).save(any());
    }

    @Test
    void uploadProduct_ValidatesTheAdminBeforeWritingAnyFile() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "some-image-data".getBytes());
        when(categoryEntityRepository.findByCategoryId(10)).thenReturn(category);
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(AdminNotFoundException.class, () ->
                adminSupplyService.uploadProduct(1L, "Water", "Still", new BigDecimal("5.00"),
                        100, new BigDecimal("0.10"), "Fresh water", 10, null, null, file));

        verify(fileService, never()).writeFileToDisk(any(), anyString());
        verify(productEntityRepository, never()).save(any());
    }

    @Test
    void uploadProduct_DeletesTheWrittenFileWhenTheProductInsertFails() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "some-image-data".getBytes());
        Path mockPath = Paths.get("UploadFolder/image_uuid.png");

        when(categoryEntityRepository.findByCategoryId(10)).thenReturn(category);
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(fileService.writeFileToDisk(any(), anyString())).thenReturn(mockPath);
        when(productEntityRepository.save(any(ProductEntity.class)))
                .thenThrow(new DataIntegrityViolationException("product insert rejected"));

        assertThrows(DataIntegrityViolationException.class, () ->
                adminSupplyService.uploadProduct(1L, "Water", "Still", new BigDecimal("5.0"),
                        100, new BigDecimal("0.1"), "Fresh water", 10, null, null, file));

        verify(fileService).deleteFileIfExists(mockPath);
    }

    @Test
    void uploadProduct_DeletesTheWrittenFileWhenTheImageInsertFails() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "some-image-data".getBytes());
        Path mockPath = Paths.get("UploadFolder/image_uuid.png");

        when(categoryEntityRepository.findByCategoryId(10)).thenReturn(category);
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(fileService.writeFileToDisk(any(), anyString())).thenReturn(mockPath);
        when(productImageEntityRepository.save(any(ProductImageEntity.class)))
                .thenThrow(new DataIntegrityViolationException("image insert rejected"));

        assertThrows(DataIntegrityViolationException.class, () ->
                adminSupplyService.uploadProduct(1L, "Water", "Still", new BigDecimal("5.0"),
                        100, new BigDecimal("0.1"), "Fresh water", 10, null, null, file));

        verify(fileService).deleteFileIfExists(mockPath);
    }

    @Test
    void uploadProduct_UsesAUniqueFileNameForEachUpload() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "some-image-data".getBytes());
        when(fileService.writeFileToDisk(any(), anyString()))
                .thenReturn(Paths.get("UploadFolder/a.png"));
        when(categoryEntityRepository.findByCategoryId(10)).thenReturn(category);
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(admin));

        adminSupplyService.uploadProduct(1L, "Water", "Still", new BigDecimal("5.0"),
                100, new BigDecimal("0.1"), "Fresh water", 10, null, null, file);

        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileService).writeFileToDisk(any(), nameCaptor.capture());
        String fileName = nameCaptor.getValue();

        assertTrue(fileName.startsWith("image_"), fileName);
        assertTrue(fileName.endsWith(".png"), fileName);
        assertDoesNotThrow(() -> UUID.fromString(
                fileName.substring("image_".length(), fileName.length() - ".png".length())),
                "file name must embed a UUID so simultaneous uploads cannot collide: " + fileName);
    }

    @Test
    void uploadProduct_AcceptsPriceWithTrailingZeroPadding() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "some-image-data".getBytes());
        Path mockPath = Paths.get("UploadFolder/image_123.png");

        when(fileService.writeFileToDisk(any(), anyString())).thenReturn(mockPath);
        when(categoryEntityRepository.findByCategoryId(10)).thenReturn(category);
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(admin));

        adminSupplyService.uploadProduct(1L, "Water", "Still", new BigDecimal("10.000"), 100, new BigDecimal("0.10"), "Fresh water", 10, null, null, file);

        verify(productEntityRepository).save(any(ProductEntity.class));
    }

    @Test
    void uploadProduct_ThrowsException_WhenProductPriceHasMoreThanTwoMeaningfulDecimals() {
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "data".getBytes());

        GeneralException ex = assertThrows(GeneralException.class, () ->
                adminSupplyService.uploadProduct(1L, "Water", "Still", new BigDecimal("10.001"), 100, new BigDecimal("0.10"), "Fresh", 10, null, null, file)
        );

        assertEquals("Product price must not exceed two decimal places", ex.getMessage());
        verify(productEntityRepository, never()).save(any());
    }

    @Test
    void updateProduct_ThrowsException_WhenProductPriceHasMoreThanTwoMeaningfulDecimals() {
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "data".getBytes());

        GeneralException ex = assertThrows(GeneralException.class, () ->
                adminSupplyService.updateProduct(1L, 100L, "Name", "Sub", new BigDecimal("10.001"), 5, BigDecimal.ZERO, "Desc", 1, null, null, file, 0L)
        );

        assertEquals("Product price must not exceed two decimal places", ex.getMessage());
        verify(productEntityRepository, never()).save(any());
    }

    @Test
    void uploadProduct_ThrowsException_WhenImageMissing() {
        GeneralException ex = assertThrows(GeneralException.class, () ->
                adminSupplyService.uploadProduct(1L, "Water", "Still", new BigDecimal("5.0"), 100, new BigDecimal("0.1"), "Fresh", 10, null, null, null)
        );

        assertEquals("Product image is required", ex.getMessage());
    }

    @Test
    void uploadProduct_ThrowsException_WhenProductNameIsBlank() {
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "data".getBytes());

        GeneralException ex = assertThrows(GeneralException.class, () ->
                adminSupplyService.uploadProduct(1L, "   ", "Still", new BigDecimal("5.0"), 100, new BigDecimal("0.1"), "Fresh", 10, null, null, file)
        );

        assertEquals("Product name is required", ex.getMessage());
    }

    @Test
    void uploadProduct_ThrowsException_WhenNotPng() {
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.jpg", "image/jpeg", "data".getBytes());

        GeneralException ex = assertThrows(GeneralException.class, () ->
                adminSupplyService.uploadProduct(1L, "Water", "Still", new BigDecimal("5.0"), 100, new BigDecimal("0.1"), "Fresh", 10, null, null, file)
        );
        assertEquals("Only PNG files are allowed", ex.getMessage());
    }

    @Test
    void uploadProduct_ThrowsException_WhenFileUploadFails() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "data".getBytes());
        when(categoryEntityRepository.findByCategoryId(10)).thenReturn(category);
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(fileService.writeFileToDisk(any(), anyString())).thenThrow(new IOException());

        assertThrows(FileUploadFailedException.class, () ->
                adminSupplyService.uploadProduct(1L, "Water", "Still", new BigDecimal("5.0"), 100, new BigDecimal("0.1"), "Fresh", 10, null, null, file)
        );
    }

    @Test
    void updateProduct_Success() throws IOException {
        Long adminId = 1L;
        Long productId = 100L;
        int categoryId = 5;

        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "image-content".getBytes());

        AdminEntity localAdmin = new AdminEntity();
        localAdmin.setId(adminId);

        CategoryEntity localCategory = new CategoryEntity();
        localCategory.setId((long) categoryId);

        ProductEntity existingProduct = new ProductEntity();
        existingProduct.setId(productId);
        existingProduct.setVersion(4L);

        ProductImageEntity existingImage = new ProductImageEntity();
        existingImage.setId(500L);
        existingImage.setImagePath("old/path.png");

        Path mockPath = Paths.get("UploadFolder/new_image.png");
        when(fileService.writeFileToDisk(any(), anyString())).thenReturn(mockPath);
        when(categoryEntityRepository.findByCategoryId(categoryId)).thenReturn(localCategory);
        when(adminEntityRepository.findById(adminId)).thenReturn(Optional.of(localAdmin));
        when(productEntityRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(existingProduct));
        when(productImageEntityRepository.findByProductEntityId(productId)).thenReturn(List.of(existingImage));

        adminSupplyService.updateProduct(adminId, productId, "Updated Name", "SubCat",
                new BigDecimal("10.0"), 50, new BigDecimal("0.2"), "New Desc", categoryId, null, null, file, 4L);

        verify(productEntityRepository).save(existingProduct);
        assertEquals("Updated Name", existingProduct.getProductName());
        assertEquals(localAdmin, existingProduct.getAdminEntity());

        verify(productImageEntityRepository).save(existingImage);
        assertEquals(mockPath.toString(), existingImage.getImagePath());
    }

    @Test
    void updateProduct_DoesNotWriteFile_WhenImageMissing() throws IOException {
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(new AdminEntity()));
        when(categoryEntityRepository.findByCategoryId(1)).thenReturn(new CategoryEntity());
        ProductEntity product = new ProductEntity();
        product.setId(100L);
        product.setVersion(0L);
        when(productEntityRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(product));

        adminSupplyService.updateProduct(1L, 100L, "Name", "Sub", new BigDecimal("10"), 5, BigDecimal.ZERO, "Desc", 1, null, null, null, 0L);

        verify(fileService, never()).writeFileToDisk(any(), anyString());
        verify(productImageEntityRepository, never()).findByProductEntityId(anyLong());
        verify(productEntityRepository).save(product);
    }

    @Test
    void updateProduct_ThrowsException_WhenAdminNotFound() {
        MockMultipartFile file = new MockMultipartFile("selectedImage", "test.png", "image/png", "data".getBytes());
        when(categoryEntityRepository.findByCategoryId(1)).thenReturn(new CategoryEntity());
        when(adminEntityRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThrows(AdminNotFoundException.class, () ->
                adminSupplyService.updateProduct(1L, 100L, "Name", "Sub", new BigDecimal("10"), 5, BigDecimal.ZERO, "Desc", 1, null, null, file, 0L)
        );
    }

    @Test
    void updateProduct_ThrowsException_WhenProductNotFound() {
        MockMultipartFile file = new MockMultipartFile("selectedImage", "test.png", "image/png", "data".getBytes());

        when(categoryEntityRepository.findByCategoryId(1)).thenReturn(new CategoryEntity());
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(new AdminEntity()));
        when(productEntityRepository.findByIdForUpdate(100L)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () ->
                adminSupplyService.updateProduct(1L, 100L, "Name", "Sub", new BigDecimal("10"), 5, BigDecimal.ZERO, "Desc", 1, null, null, file, 0L)
        );
    }

    @Test
    void updateProduct_ThrowsFileUploadFailedException_OnIOException() throws IOException {
        MockMultipartFile file = new MockMultipartFile("selectedImage", "test.png", "image/png", "data".getBytes());

        AdminEntity updatedAdmin = new AdminEntity();
        CategoryEntity updatedCategory = new CategoryEntity();
        ProductEntity product = new ProductEntity();
        product.setId(100L);
        product.setVersion(0L);
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(updatedAdmin));
        when(categoryEntityRepository.findByCategoryId(1)).thenReturn(updatedCategory);
        when(productEntityRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(product));
        when(fileService.writeFileToDisk(any(), anyString())).thenThrow(new IOException());

        assertThrows(FileUploadFailedException.class, () ->
                adminSupplyService.updateProduct(1L, 100L, "Name", "Sub", new BigDecimal("10"), 5, BigDecimal.ZERO, "Desc", 1, null, null, file, 0L)
        );

        InOrder order = inOrder(productEntityRepository, fileService);
        order.verify(productEntityRepository).save(product);
        order.verify(fileService).writeFileToDisk(any(), anyString());
        assertEquals("Name", product.getProductName());
        assertSame(updatedAdmin, product.getAdminEntity());
        assertSame(updatedCategory, product.getCategoryEntity());
    }

    @Test
    void updateProduct_DeletesTheWrittenFileWhenTheImageUpdateFails() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "data".getBytes());
        Path mockPath = Paths.get("UploadFolder/new_image.png");
        ProductEntity product = new ProductEntity();
        product.setId(100L);
        product.setVersion(0L);
        ProductImageEntity existingImage = new ProductImageEntity();
        existingImage.setId(500L);

        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(new AdminEntity()));
        when(categoryEntityRepository.findByCategoryId(1)).thenReturn(new CategoryEntity());
        when(productEntityRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(product));
        when(productImageEntityRepository.findByProductEntityId(100L)).thenReturn(List.of(existingImage));
        when(fileService.writeFileToDisk(any(), anyString())).thenReturn(mockPath);
        when(productImageEntityRepository.save(any(ProductImageEntity.class)))
                .thenThrow(new DataIntegrityViolationException("image update rejected"));

        assertThrows(DataIntegrityViolationException.class, () ->
                adminSupplyService.updateProduct(1L, 100L, "Name", "Sub", new BigDecimal("10"),
                        5, BigDecimal.ZERO, "Desc", 1, null, null, file, 0L));

        verify(fileService).deleteFileIfExists(mockPath);
    }

    /**
     * A replacement has to record the file it stopped pointing at, in the same
     * transaction, and only that file. Enqueuing the new path would delete the
     * image the edit had just committed; enqueuing an old and new path that
     * canonicalize to the same file would do the same, which is why the
     * comparison is on the canonical identity rather than on the raw string.
     */
    @Test
    void updateProduct_shouldEnqueueOnlyTheReplacedFile() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "data".getBytes());
        Path newPath = Paths.get("UploadFolder/image_new.png");
        ProductEntity product = new ProductEntity();
        product.setId(100L);
        product.setVersion(0L);
        ProductImageEntity existingImage = new ProductImageEntity();
        existingImage.setId(500L);
        String previousStoredPath = Paths.get("UploadFolder/image_old.png").toString();
        existingImage.setImagePath(previousStoredPath);

        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(new AdminEntity()));
        when(categoryEntityRepository.findByCategoryId(1)).thenReturn(new CategoryEntity());
        when(productEntityRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(product));
        when(productImageEntityRepository.findByProductEntityId(100L)).thenReturn(List.of(existingImage));
        when(fileService.writeFileToDisk(any(), anyString())).thenReturn(newPath);
        when(fileService.canonicalFileIdentity(anyString()))
                .thenAnswer(invocation -> Paths.get(invocation.<String>getArgument(0)).getFileName().toString());

        adminSupplyService.updateProduct(1L, 100L, "Name", "Sub", new BigDecimal("10"),
                5, BigDecimal.ZERO, "Desc", 1, null, null, file, 0L);

        verify(imageCleanupQueue).enqueueObsoleteReference(previousStoredPath);
    }

    /**
     * A re-point at the same file makes nothing obsolete. Fresh uploads always
     * get a fresh UUID name, so this is only reachable for a pre-existing row,
     * and enqueueing it anyway would delete a live image.
     */
    @Test
    void updateProduct_shouldEnqueueNothingWhenTheIdentityIsUnchanged() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "selectedImage", "test.png", "image/png", "data".getBytes());
        Path newPath = Paths.get("UploadFolder/image_same.png");
        ProductEntity product = new ProductEntity();
        product.setId(100L);
        product.setVersion(0L);
        ProductImageEntity existingImage = new ProductImageEntity();
        existingImage.setId(500L);
        // Same file, spelled the way a legacy row spells it.
        existingImage.setImagePath(Paths.get("UploadFolder/nested/image_same.png").toString());

        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(new AdminEntity()));
        when(categoryEntityRepository.findByCategoryId(1)).thenReturn(new CategoryEntity());
        when(productEntityRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(product));
        when(productImageEntityRepository.findByProductEntityId(100L)).thenReturn(List.of(existingImage));
        when(fileService.writeFileToDisk(any(), anyString())).thenReturn(newPath);
        when(fileService.canonicalFileIdentity(anyString()))
                .thenReturn("image_same.png");

        adminSupplyService.updateProduct(1L, 100L, "Name", "Sub", new BigDecimal("10"),
                5, BigDecimal.ZERO, "Desc", 1, null, null, file, 0L);

        verify(imageCleanupQueue, never()).enqueueObsoleteReference(anyString());
    }

    /**
     * The browser holds a form open across a checkout reservation or a restock.
     *
     * <p>Rejecting this is the whole feature: accepting it would write the
     * editor's stale absolute count over stock that has since moved. The
     * comparison has to happen before anything is mutated, so the product still
     * holds its old name, price and count after the rejection - a check that ran
     * after {@code applyProductDetails} would be useless.
     */
    @Test
    void updateProduct_RejectsAStaleVersionBeforeMutatingAnything() {
        ProductEntity product = new ProductEntity();
        product.setId(100L);
        product.setVersion(7L);
        product.setProductName("Original");
        product.setProductCount(10);
        product.setProductPrice(new BigDecimal("10.00"));

        when(categoryEntityRepository.findByCategoryId(1)).thenReturn(new CategoryEntity());
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(new AdminEntity()));
        when(productEntityRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(product));

        assertThrows(ProductEditConflictException.class, () ->
                adminSupplyService.updateProduct(1L, 100L, "Stale Edit", "Sub",
                        new BigDecimal("99.00"), 999, BigDecimal.ZERO, "Desc", 1, null, null, null, 6L));

        assertEquals("Original", product.getProductName());
        assertEquals(10, product.getProductCount());
        assertEquals(0, new BigDecimal("10.00").compareTo(product.getProductPrice()));
        verify(productEntityRepository, never()).save(any());
        verify(productImageEntityRepository, never()).save(any());
    }

    /**
     * The file is the one part of an edit a database rollback cannot undo, so a
     * rejected edit must not reach disk at all - not even to be deleted again
     * afterwards.
     */
    @Test
    void updateProduct_RejectsAStaleVersionWithoutWritingTheUploadedFile() throws IOException {
        ProductEntity product = new ProductEntity();
        product.setId(100L);
        product.setVersion(7L);
        product.setProductName("Original");
        product.setProductCount(10);

        when(categoryEntityRepository.findByCategoryId(1)).thenReturn(new CategoryEntity());
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(new AdminEntity()));
        when(productEntityRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(product));

        assertThrows(ProductEditConflictException.class, () ->
                adminSupplyService.updateProduct(1L, 100L, "Stale Edit", "Sub",
                        new BigDecimal("10.00"), 999, BigDecimal.ZERO, "Desc", 1, null, null, png(), 6L));

        verify(fileService, never()).writeFileToDisk(any(), anyString());
        verify(productEntityRepository, never()).save(any());
        verify(productImageEntityRepository, never()).save(any());
    }

    /**
     * A version the product never had is not "close enough". Only an exact match
     * may proceed, so a client that fabricates or rounds a version is rejected.
     */
    @Test
    void updateProduct_RejectsAVersionTheProductNeverHad() {
        ProductEntity product = new ProductEntity();
        product.setId(100L);
        product.setVersion(7L);

        when(categoryEntityRepository.findByCategoryId(1)).thenReturn(new CategoryEntity());
        when(adminEntityRepository.findById(1L)).thenReturn(Optional.of(new AdminEntity()));
        when(productEntityRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(product));

        assertThrows(ProductEditConflictException.class, () ->
                adminSupplyService.updateProduct(1L, 100L, "Name", "Sub",
                        new BigDecimal("10.00"), 5, BigDecimal.ZERO, "Desc", 1, null, null, null, 8L));

        verify(productEntityRepository, never()).save(any());
    }

    /**
     * Every database reference is resolved before the version is compared, so an
     * invalid category still fails with its own error rather than being masked
     * as a conflict - and still leaves no orphan image.
     */
    @Test
    void updateProduct_ReportsAnInvalidCategoryBeforeTheVersionCheck() throws IOException {
        ProductEntity product = new ProductEntity();
        product.setId(100L);
        product.setVersion(7L);

        assertThrows(GeneralException.class, () ->
                adminSupplyService.updateProduct(1L, 100L, "Name", "Sub",
                        new BigDecimal("10.00"), 5, BigDecimal.ZERO, "Desc", 1, null, null, png(), 6L));

        verify(fileService, never()).writeFileToDisk(any(), anyString());
        verify(productEntityRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void getAllAdminProducts_shouldReturnMappedPreviews() {
        ProductEntity first = new ProductEntity();
        first.setId(1L);
        first.setProductName("Apple");
        ProductEntity second = new ProductEntity();
        second.setId(2L);
        second.setProductName("Banana");

        Page<ProductEntity> page = new PageImpl<>(List.of(first, second), PageRequest.of(0, 2), 2);
        when(productEntityRepository.findByAdminEntityId(eq(10L), any(Pageable.class))).thenReturn(page);

        List<AdminProductPreviewDto> result = adminSupplyService.getAllAdminProducts(10L, 0, 2);

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getProductId());
        assertEquals("Apple", result.get(0).getProductName());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(productEntityRepository).findByAdminEntityId(eq(10L), pageableCaptor.capture());
        assertEquals(PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "id")), pageableCaptor.getValue(),
                "an admin page with no ORDER BY can repeat or skip a row between two reads of unchanged data");
    }

    /**
     * The bounds are applied before the query, so an out-of-range window costs no
     * database access and cannot be turned into an unbounded LIMIT.
     */
    @Test
    void getAllAdminProducts_rejectsAnOutOfRangePageWindow() {
        assertThrows(GeneralException.class, () -> adminSupplyService.getAllAdminProducts(1L, -1, 10));
        assertThrows(GeneralException.class, () -> adminSupplyService.getAllAdminProducts(1L, 0, 0));
        assertThrows(GeneralException.class, () -> adminSupplyService.getAllAdminProducts(1L, 0, 101));

        verify(productEntityRepository, never()).findByAdminEntityId(anyLong(), any(Pageable.class));
    }

    @Test
    void getAllAdminProducts_shouldReturnEmptyList_whenEmpty() {
        Page<ProductEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 5), 0);
        when(productEntityRepository.findByAdminEntityId(eq(1L), any(Pageable.class))).thenReturn(emptyPage);

        assertTrue(adminSupplyService.getAllAdminProducts(1L, 0, 5).isEmpty());
    }

    /**
     * Deleting a product has to lock its row first, capture every image it owns
     * and delete it, so the queue can record the files that are about to become
     * unreferenced - all in the same transaction, so a failed delete owes
     * nothing.
     */
    @Test
    void deleteProduct_shouldEnqueueEveryImageBeforeDeletingTheProduct() {
        ProductEntity product = new ProductEntity();
        product.setId(55L);
        product.setVersion(0L);
        ProductImageEntity first = new ProductImageEntity();
        first.setId(1L);
        first.setImagePath("image_first.png");
        ProductImageEntity second = new ProductImageEntity();
        second.setId(2L);
        second.setImagePath("image_second.png");

        when(productEntityRepository.findByIdForUpdate(55L)).thenReturn(Optional.of(product));
        when(productImageEntityRepository.findByProductEntityId(55L)).thenReturn(List.of(first, second));

        adminSupplyService.deleteProduct(55L);

        verify(imageCleanupQueue).enqueueObsoleteReferences(List.of("image_first.png", "image_second.png"));
        verify(productImageEntityRepository).deleteAll(List.of(first, second));
        verify(productEntityRepository).delete(product);
        verify(productEntityRepository, never()).deleteById(anyLong());
    }

    /**
     * A product that is not there stays a no-op, exactly as it was before this
     * method became transactional. Whether a missing product should be a 404 is
     * a question about the delete contract, and changing it as a side effect of
     * adding a cleanup queue would alter a client-visible response for an
     * unrelated reason.
     */
    @Test
    void deleteProduct_shouldLeaveAnAbsentProductAlone() {
        when(productEntityRepository.findByIdForUpdate(55L)).thenReturn(Optional.empty());

        adminSupplyService.deleteProduct(55L);

        verify(imageCleanupQueue, never()).enqueueObsoleteReferences(any());
        verify(productImageEntityRepository, never()).deleteAll(any());
        verify(productEntityRepository, never()).delete(any(ProductEntity.class));
    }

    @Test
    void getProductData_shouldReturnMappedDto() {
        CategoryEntity categoryEntity = new CategoryEntity();
        categoryEntity.setId(9L);

        ProductEntity product = new ProductEntity();
        product.setId(7L);
        product.setProductName("Milk");
        product.setSubcategoryName("Dairy");
        product.setProductPrice(new BigDecimal("12.5"));
        product.setProductCount(20);
        product.setProductDiscount(new BigDecimal("0.1"));
        product.setProductDescription("Fresh milk");
        product.setCategoryEntity(categoryEntity);
        product.setVersion(3L);

        when(productEntityRepository.findById(7L)).thenReturn(Optional.of(product));

        ProductDetailDto result = adminSupplyService.getProductData(7L);

        assertEquals("Milk", result.getProductName());
        assertEquals("Dairy", result.getSubCategoryName());
        assertEquals(0, new BigDecimal("12.5").compareTo(result.getProductPrice()));
        assertEquals(20, result.getProductCount());
        assertEquals(0, new BigDecimal("0.1").compareTo(result.getProductDiscount()));
        assertEquals("Fresh milk", result.getProductDescription());
        assertEquals(9, result.getProductCategoryId());
        assertEquals(3L, result.getProductVersion(),
                "an editor must be able to learn the version it has to submit");
    }

    @Test
    void getProductData_shouldThrowProductNotFoundException_whenMissing() {
        when(productEntityRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> adminSupplyService.getProductData(99L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void addProductDescription_shouldCreateWhenNoExistingDescriptions() {
        ProductEntity product = new ProductEntity();
        product.setId(101L);

        ProductDescriptionListDto dto = new ProductDescriptionListDto();
        dto.setProductId(101L);
        dto.setDescriptionList(List.of(
                new ProductDescriptionTabDto(null, "Tab1", "Content1"),
                new ProductDescriptionTabDto(null, "Tab2", "Content2")
        ));

        when(productEntityRepository.findById(101L)).thenReturn(Optional.of(product));
        when(productDescriptionEntityRepository.findByProductEntityId(101L)).thenReturn(Collections.emptyList());

        adminSupplyService.addProductDescription(dto);

        ArgumentCaptor<List<ProductDescriptionEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(productDescriptionEntityRepository).saveAll(captor.capture());
        List<ProductDescriptionEntity> saved = captor.getValue();
        assertEquals(2, saved.size());
        assertEquals("Tab1", saved.get(0).getDescriptionTabName());
        assertEquals("Content1", saved.get(0).getDescriptionTabContent());
        assertEquals(product, saved.get(0).getProductEntity());
        assertEquals("Tab2", saved.get(1).getDescriptionTabName());
        assertEquals("Content2", saved.get(1).getDescriptionTabContent());
        assertEquals(product, saved.get(1).getProductEntity());
    }

    @Test
    @SuppressWarnings("unchecked")
    void addProductDescription_shouldUpdateExistingAndCreateMissing() {
        ProductEntity product = new ProductEntity();
        product.setId(102L);

        ProductDescriptionEntity existing = new ProductDescriptionEntity();
        existing.setId(11L);
        existing.setProductEntity(product);

        ProductDescriptionListDto dto = new ProductDescriptionListDto();
        dto.setProductId(102L);
        dto.setDescriptionList(List.of(
                new ProductDescriptionTabDto(11L, "Updated", "Updated content"),
                new ProductDescriptionTabDto(12L, "New", "New content")
        ));

        when(productEntityRepository.findById(102L)).thenReturn(Optional.of(product));
        when(productDescriptionEntityRepository.findByProductEntityId(102L)).thenReturn(List.of(existing));
        when(productDescriptionEntityRepository.findById(11L)).thenReturn(Optional.of(existing));
        when(productDescriptionEntityRepository.findById(12L)).thenReturn(Optional.empty());

        adminSupplyService.addProductDescription(dto);

        ArgumentCaptor<List<ProductDescriptionEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(productDescriptionEntityRepository).saveAll(captor.capture());
        List<ProductDescriptionEntity> saved = captor.getValue();
        assertEquals(2, saved.size());

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
        assertEquals(1L, result.getDescriptionList().get(0).descriptionId());
        assertEquals("A", result.getDescriptionList().get(0).tabName());
        assertEquals("A content", result.getDescriptionList().get(0).tabContent());
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

    private MockMultipartFile png() {
        return new MockMultipartFile("selectedImage", "image.png", "image/png", "data".getBytes());
    }
}



