package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.admin.panel.*;
import com.example.MigrosBackend.dto.user.product.ProductDetailDto;
import com.example.MigrosBackend.dto.user.product.ProductDto;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.product.ProductImageEntity;
import com.example.MigrosBackend.exception.admin.AdminNotFoundException;
import com.example.MigrosBackend.exception.admin.ProductEditConflictException;
import com.example.MigrosBackend.exception.admin.ProductNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.helper.PageRequestPolicy;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
import com.example.MigrosBackend.service.global.FileService;
import com.example.MigrosBackend.service.user.supply.UserCatalogReadService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AdminSupplyService {
    private final CategoryEntityRepository categoryEntityRepository;
    private final ProductEntityRepository productEntityRepository;
    private final ProductImageEntityRepository productImageEntityRepository;
    private final AdminEntityRepository adminEntityRepository;
    private final AdminProductDescriptionOperations productDescriptionOperations;
    private final AdminProductImageOperations productImageOperations;
    private final ProductCreationPolicy productCreationPolicy;
    private final ProductImageCleanupQueue imageCleanupQueue;
    private final FileService fileService;
    private final UserCatalogReadService catalogReadService;
    private final EntityManager entityManager;

    @Autowired
    public AdminSupplyService(CategoryEntityRepository categoryEntityRepository, ProductEntityRepository productEntityRepository, ProductImageEntityRepository productImageEntityRepository, AdminEntityRepository adminEntityRepository, FileService fileService, AdminProductDescriptionOperations productDescriptionOperations, AdminProductImageOperations productImageOperations, ProductCreationPolicy productCreationPolicy, ProductImageCleanupQueue imageCleanupQueue, UserCatalogReadService catalogReadService, EntityManager entityManager) {
        this.categoryEntityRepository = categoryEntityRepository;
        this.productEntityRepository = productEntityRepository;
        this.productImageEntityRepository = productImageEntityRepository;
        this.adminEntityRepository = adminEntityRepository;
        this.fileService = fileService;
        this.productDescriptionOperations = productDescriptionOperations;
        this.productImageOperations = productImageOperations;
        this.productCreationPolicy = productCreationPolicy;
        this.imageCleanupQueue = imageCleanupQueue;
        this.catalogReadService = catalogReadService;
        this.entityManager = entityManager;
    }

    /**
     * Registers a product from a JSON request and links it to its administrator.
     *
     * <p>This path used to copy four fields onto a new entity and insert it, which
     * could not produce a row the schema accepts: {@code product_description} and
     * {@code product_discount} are {@code NOT NULL} and were left null, and the
     * category foreign key was never set, so a product created here had no
     * category to be listed under. It now runs through exactly the same
     * {@link ProductCreationPolicy} as the multipart upload, and the one insert
     * carries both foreign keys.
     *
     * <p>The category arrives as a name rather than an id, so it is resolved
     * before the insert and an unknown or ambiguous name is refused. There is no
     * default category: silently filing a product somewhere the administrator
     * did not choose is worse than refusing, and the request carries the name for
     * exactly this purpose.
     *
     * <p>The admin's {@code itemEntities} collection is deliberately not touched.
     * That collection is the inverse side of the product's own
     * {@code admin_entity_id} column, so appending to it and re-saving the admin
     * would be a second, cascading write whose only effect is to make the
     * association the first write already made.
     */
    @Transactional
    public void addProduct(AdminAddItemDto adminAddItemDto) {
        ProductDto productDto = adminAddItemDto.getProductDto();

        // Everything that can be judged from the request alone is judged first,
        // so a malformed body is reported as such rather than surfacing as a
        // failed lookup for a reference the request never got to specify. Only
        // then does anything touch the database.
        String categoryName = productCreationPolicy.normalizeCategoryName(productDto.getCategoryName());
        ProductDetails details = productCreationPolicy.normalize(ProductDetails.of(
                productDto.getProductName(),
                productDto.getSubCategoryName(),
                productDto.getProductPrice(),
                productDto.getProductCount(),
                productDto.getProductDiscount(),
                productDto.getProductDescription()));

        AdminEntity currentAdminEntity = adminEntityRepository.findById(adminAddItemDto.getAdminId())
                .orElseThrow(() -> new AdminNotFoundException(adminAddItemDto.getAdminId().toString()));
        CategoryEntity categoryEntity = resolveCategoryByName(categoryName);

        ProductEntity newProductEntity = new ProductEntity();
        productCreationPolicy.applyTo(newProductEntity, details, currentAdminEntity, categoryEntity);

        productEntityRepository.save(newProductEntity);
    }


    public void addCategory(String categoryName) {
        CategoryEntity ce = categoryEntityRepository.findByCategoryName(categoryName);
        if (ce != null)
            throw new GeneralException("Same category with that name: " + categoryName + " already exists.");

        CategoryEntity categoryEntity = new CategoryEntity();
        categoryEntity.setCategoryName(categoryName);

        categoryEntityRepository.save(categoryEntity);
    }

    /**
     * Stores the uploaded file and links it to a product in one transaction.
     *
     * <p>Ordering is load-bearing in both directions. Every database reference is
     * resolved before a single byte is written: the file is the one part of this
     * operation that a database rollback cannot undo, so nothing may be written
     * until the rows it belongs to are known to be viable, or an invalid category
     * or admin leaves an orphan image nothing ever references. And the new file is
     * registered for rollback cleanup the moment it exists, so a failure anywhere in
     * the transaction - including a commit-time failure, after this method has
     * already returned - deletes it again.
     */
    @Transactional
    public void uploadProduct(Long adminId, String productName,
                              String subCategoryName, BigDecimal productPrice,
                              int productCount, BigDecimal productDiscount,
                              String productDescription, int categoryValue,
                              MultipartFile selectedImage) {
        ProductDetails details = productCreationPolicy.normalize(ProductDetails.of(
                productName, subCategoryName, productPrice, productCount,
                productDiscount, productDescription));
        productImageOperations.validateProductImage(selectedImage);

        CategoryEntity categoryEntity = resolveCategoryById(categoryValue);
        AdminEntity adminEntity = adminEntityRepository.findById(adminId)
                .orElseThrow(() -> new AdminNotFoundException(adminId.toString()));

        Path savedFilePath = writeImageForRollbackCleanup(selectedImage);
        try {
            ProductEntity productEntity = new ProductEntity();
            productCreationPolicy.applyTo(productEntity, details, adminEntity, categoryEntity);
            productEntityRepository.save(productEntity);

            saveProductImage(productEntity, savedFilePath);
        } catch (RuntimeException ex) {
            // Fail fast rather than wait for the rollback: the file is the one
            // part of this operation the database cannot undo.
            fileService.deleteFileIfExists(savedFilePath);
            throw ex;
        }
    }

    /**
     * Applies an edit to a product, replacing its image when one is supplied.
     *
     * <p>Same transaction as the upload path, and for the same reason: the product
     * row, its admin/category references, and its image row either all become
     * visible together or none do. Two writes left outside a transaction could
     * commit the product edit and then fail on the image, leaving the product
     * pointing at the previous image (or at none) with no record of the intent.
     *
     * <p>A product that has no image row yet - one created before images existed,
     * or one whose image row was removed - gets one created here. Skipping the
     * update because there was nothing to update would leave the freshly uploaded
     * file on disk with nothing referencing it.
     *
     * <p>Two separate staleness guards, and both are needed:
     *
     * <ul>
     *   <li>The row lock ({@code findByIdForUpdate}) serialises this edit against
     *       a concurrent checkout reservation or restock, so the comparison below
     *       runs against a value no other stock writer can change underneath it.</li>
     *   <li>The {@code expectedVersion} the editor submitted detects a browser
     *       that has been holding the form since before that lock was taken. The
     *       lock alone does not: an edit form opened minutes ago acquires the
     *       lock instantly and would then overwrite current stock with the count
     *       it displayed when it loaded.</li>
     * </ul>
     *
     * <p>The comparison runs before any field is mutated and before any byte of
     * image is written, so a rejected edit leaves the row, the image reference
     * and the upload directory exactly as they were.
     *
     * <p>The version this edit produces is returned, and it is read off the
     * managed instance <em>after</em> everything this transaction changed has
     * been flushed. That is deliberate and it is the whole reason the method
     * returns a value instead of the controller re-reading the product. An
     * editor that has just written needs the version its own write produced so
     * its next save is not rejected against itself. Fetching that version in a
     * separate read after the commit would be a different, later version: a
     * checkout reservation landing in between would hand the editor a version
     * that vouches for a stock count its form never saw, and the next absolute
     * count write would resurrect the reserved units.
     *
     * @return the product version this transaction left behind, for the editor's
     * next {@code expectedVersion}
     */
    @Transactional
    public long updateProduct(Long adminId, Long productId, String productName,
                              String subCategoryName, BigDecimal productPrice,
                              int productCount, BigDecimal productDiscount,
                              String productDescription, int categoryValue,
                              MultipartFile selectedImage, Long expectedVersion) {
        ProductDetails details = productCreationPolicy.normalize(ProductDetails.of(
                productName, subCategoryName, productPrice, productCount,
                productDiscount, productDescription));

        CategoryEntity categoryEntity = resolveCategoryById(categoryValue);
        AdminEntity adminEntity = adminEntityRepository.findById(adminId).orElseThrow(() -> new AdminNotFoundException(adminId.toString()));

        // Every reference is resolved before this point, so a missing admin or
        // category still leaves no orphan image behind.
        ProductEntity productEntity = productEntityRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId.toString()));

        if (expectedVersion == null || !expectedVersion.equals(productEntity.getVersion())) {
            throw ProductEditConflictException.staleVersion();
        }

        productCreationPolicy.applyTo(productEntity, details, adminEntity, categoryEntity);
        productEntityRepository.save(productEntity);

        boolean imageReplaced = selectedImage != null && !selectedImage.isEmpty();
        if (imageReplaced) {
            Path savedFilePath = writeImageForRollbackCleanup(selectedImage);
            try {
                saveProductImage(productEntity, savedFilePath);
            } catch (RuntimeException ex) {
                fileService.deleteFileIfExists(savedFilePath);
                throw ex;
            }
        }

        // Both branches are flushed before the version is read, so the number
        // returned is the one the row actually carries and not the value the
        // entity still held when the method was entered.
        productEntityRepository.flush();
        if (imageReplaced) {
            // See advanceVersionForImageChange: the image row is a different
            // entity, so replacing it dirties nothing here and the version would
            // otherwise not move at all.
            advanceVersionForImageChange(productEntity);
            productEntityRepository.flush();
        }

        Long resultingVersion = productEntity.getVersion();
        if (resultingVersion == null) {
            // Only reachable if a product somehow reached this method without a
            // version, which the NOT NULL column already forbids. Failing loudly
            // beats returning a null that the client would have to interpret.
            throw new GeneralException("Product version could not be determined after the update.");
        }
        return resultingVersion;
    }

    /**
     * Advances the product's version for an edit that changed only its image.
     *
     * <p>{@code product_entity.version} is what every other writer of the row is
     * detected through, and the image is not on that row: {@link ProductImageEntity}
     * holds it. An image-only replacement therefore dirties nothing on the
     * managed product, Hibernate issues no {@code UPDATE}, and the version stays
     * where it was. Every open edit form for that product still holds the
     * pre-replacement version and is still believed to be current, so the
     * replacement is invisible to the guard that is supposed to see it - which
     * is exactly the state the {@code expectedVersion} check exists to prevent.
     *
     * <p>{@link LockModeType#PESSIMISTIC_FORCE_INCREMENT} is the JPA primitive
     * for this. It issues the increment as its own statement inside the
     * transaction and updates the in-memory version to match, so the caller can
     * read the real post-change value straight off the entity.
     *
     * <p>The pessimistic variant specifically, and not
     * {@link LockModeType#OPTIMISTIC_FORCE_INCREMENT}: the row is already held at
     * {@code PESSIMISTIC_WRITE} by {@code findByIdForUpdate}, and Hibernate's
     * lock upgrade is ordinal-ordered, so a request for a <em>lower</em> lock
     * grade than the one already held is treated as already satisfied and its
     * body is skipped. That makes the optimistic form a silent no-op here - the
     * increment is simply never issued and the version never moves, which
     * reproduces the very defect this method exists to fix. The pessimistic form
     * out-ranks the write lock already held, so it actually runs, and re-locking
     * a row this transaction already owns costs nothing.
     */
    private void advanceVersionForImageChange(ProductEntity productEntity) {
        entityManager.lock(productEntity, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }

    private void saveProductImage(ProductEntity productEntity, Path savedFilePath) {
        List<ProductImageEntity> images =
                productImageEntityRepository.findByProductEntityId(productEntity.getId());

        ProductImageEntity productImageEntity;
        if (images.isEmpty()) {
            // No row to update. Creating one is the only way the new upload is ever
            // reachable; doing nothing would strand the file on disk.
            productImageEntity = new ProductImageEntity();
            productImageEntity.setProductEntity(productEntity);
        } else {
            productImageEntity = images.get(0);
        }

        // Captured before the row changes, and enqueued in this same transaction.
        // After the write the old value is simply gone, and a rollback would take
        // the enqueue with it - which is the correct outcome, because a rolled
        // back replacement never stopped referencing the old file.
        String previousStoredPath = productImageEntity.getImagePath();

        productImageEntity.setImagePath(savedFilePath.toString());
        productImageEntityRepository.save(productImageEntity);

        enqueueObsoleteImage(previousStoredPath, savedFilePath.toString());
    }

    /**
     * Records that {@code previousStoredPath} has become unreferenced, unless
     * it names the same file as the reference that replaced it.
     *
     * <p>Only the replaced row is enqueued, and that is not an oversight. Any
     * other image row the product owns still points at its own file, so that
     * file is not obsolete - and the cleanup worker's reference check would defer
     * it anyway, which is the safety net for a product that somehow grew a
     * second row.
     *
     * <p>The new path is never enqueued. It is the one file the product is
     * being pointed at; a queue entry for it would delete the image a successful
     * edit had just committed.
     */
    private void enqueueObsoleteImage(String previousStoredPath, String newStoredPath) {
        if (previousStoredPath == null || previousStoredPath.isBlank()) {
            // Nothing was referenced, so nothing became obsolete. This is the
            // ordinary first-upload case.
            return;
        }
        String previousIdentity = fileService.canonicalFileIdentity(previousStoredPath);
        String newIdentity = fileService.canonicalFileIdentity(newStoredPath);
        if (previousIdentity == null) {
            // A reference that names no file inside the upload directory cannot
            // be deleted, so there is no obligation to record.
            return;
        }
        if (previousIdentity.equals(newIdentity)) {
            // The same file, re-pointed at itself: nothing is obsolete. A fresh
            // upload always gets a fresh UUID name, so this can only happen for a
            // pre-existing row, and enqueueing it anyway would delete a live image.
            return;
        }
        imageCleanupQueue.enqueueObsoleteReference(previousStoredPath);
    }

    /**
     * Writes the upload and arranges for it to be deleted if this transaction does
     * not commit.
     *
     * <p>Deleting inside a {@code catch} is not enough: the transaction can still
     * roll back after the method returns, on a deferred constraint or a commit-time
     * failure. A transaction synchronization observes the real outcome instead. A
     * call with no active transaction (a direct invocation in a test) has no
     * synchronization to register with, and there is no transaction to roll back
     * either.
     */
    private Path writeImageForRollbackCleanup(MultipartFile selectedImage) {
        Path savedFilePath = productImageOperations.writeProductImage(selectedImage);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return savedFilePath;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                fileService.deleteFileIfExists(savedFilePath);
            }
        });
        return savedFilePath;
    }

    public List<AdminProductPreviewDto> getAllAdminProducts(Long adminId, int page, int productRange) {
        Pageable pageable = PageRequestPolicy.ofProductIdAscending(page, productRange);
        Page<ProductEntity> entities = productEntityRepository.findByAdminEntityId(adminId, pageable);
        // An empty page is a valid result. The admin client sizes its paginator
        // from the separate product-count endpoint, so it never uses an error as
        // an end-of-pages signal; returning the empty list lets a transiently
        // empty page render instead of surfacing a 404.
        return entities.stream().map(productEntity -> {
            AdminProductPreviewDto productPreviewDto = new AdminProductPreviewDto();
            productPreviewDto.setProductId(productEntity.getId());
            productPreviewDto.setProductName(productEntity.getProductName());

            return productPreviewDto;
        }).collect(Collectors.toList());
    }

    /**
     * Deletes a product, its image rows, and its files in one transaction.
     *
     * <p>Deleting the product alone left every image it owned on disk forever,
     * with no record anywhere that they were unreferenced. The file cannot be
     * removed before the commit that stops referencing it, and it cannot be
     * removed from an after-commit callback without a crash in between losing
     * the obligation, so the obligation is what gets written: one durable row
     * per file identity, inside the same transaction as the delete.
     *
     * <p>Atomic in both directions, which is the point:
     *
     * <ul>
     *   <li>If the product delete fails - a checkout still references it, so the
     *       foreign key refuses - the whole transaction rolls back and no cleanup
     *       work commits. A queue entry for a product that still exists would
     *       delete the image of a product that is still being sold.</li>
     *   <li>If the commit succeeds, every file the product owned is durably owed,
     *       including the ones on rows other than the first. A product that
     *       somehow carries several image rows has several files, and taking only
     *       the displayed one would leak the rest.</li>
     * </ul>
     *
     * <p>Checkout and order behaviour is unchanged: the product is still removed
     * under the same foreign key, so a product with orders is still refused, and
     * nothing here cascades into or out of checkout data.
     *
     * <p>The row is taken with the same {@code PESSIMISTIC_WRITE} lock the edit
     * path uses, so a replacement in flight cannot interleave: it cannot rewrite
     * an image row between the moment this method decided which files are
     * obsolete and the moment those rows are deleted.
     *
     * <p>A product that is not there is still a no-op, as it was before this
     * method became transactional. Whether a missing product is a 404 is a
     * question about the delete-product contract, not about image cleanup, and
     * changing it here would alter a client-visible response as a side effect
     * of adding a queue.
     */
    @Transactional
    public void deleteProduct(Long productId) {
        Optional<ProductEntity> locked = productEntityRepository.findByIdForUpdate(productId);
        if (locked.isEmpty()) {
            return;
        }

        // Every row, not only the one the catalog happens to display first.
        List<ProductImageEntity> images = productImageEntityRepository.findByProductEntityId(productId);
        imageCleanupQueue.enqueueObsoleteReferences(
                images.stream().map(ProductImageEntity::getImagePath).toList());

        productImageEntityRepository.deleteAll(images);
        productEntityRepository.delete(locked.get());
    }

    public ProductDetailDto getProductData(Long productId) {
        return catalogReadService.getProductData(productId);
    }

    public void addProductDescription(ProductDescriptionListDto productDescriptions) {
        productDescriptionOperations.addProductDescription(productDescriptions);
    }

    public ProductDescriptionListDto getProductDescription(Long productId) {
        return productDescriptionOperations.getProductDescription(productId);
    }

    public void deleteProductDescription(Long descriptionId) {
        productDescriptionOperations.deleteProductDescription(descriptionId);
    }

    /**
     * Resolves the category a multipart caller named by its numeric id.
     *
     * <p>Keeps the message the multipart path has always returned, so a client
     * that shows it to an administrator does not change meaning when creation is
     * refactored.
     */
    private CategoryEntity resolveCategoryById(int categoryValue) {
        CategoryEntity categoryEntity = categoryEntityRepository.findByCategoryId(categoryValue);
        if (categoryEntity == null) {
            throw new GeneralException("Invalid category value: " + categoryValue);
        }
        return categoryEntity;
    }

    /**
     * Resolves the category a JSON caller named by name, refusing a name that
     * does not identify exactly one row.
     *
     * <p>{@code category_name} carries no unique constraint, so a name can match
     * nothing or several rows. Asking for the single-entity finder would let the
     * duplicate case surface as a persistence exception, which the client sees
     * as a 500 with a message about row counts. Both outcomes are the
     * administrator's problem to fix, so both are reported here as the 400s they
     * are - and neither invents a category to fall back on, because filing a
     * product under one the administrator did not choose is not a repair.
     */
    private CategoryEntity resolveCategoryByName(String categoryName) {
        List<CategoryEntity> matches = categoryEntityRepository.findAllByCategoryName(categoryName);
        if (matches.isEmpty()) {
            throw new GeneralException("Unknown category name: " + categoryName);
        }
        if (matches.size() > 1) {
            throw new GeneralException("Category name matches more than one category: " + categoryName);
        }
        return matches.get(0);
    }
}
