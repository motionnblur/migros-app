package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.service.global.FileService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Producer half of the product-image cleanup queue.
 *
 * <p>It writes a durable row and nothing else. It never touches the filesystem:
 * the file it is recording as obsolete is, by definition, the one live
 * transaction that has just stopped pointing at it, and deleting it from here
 * would mean deleting it before the database had committed the change that
 * stopped referencing it. A rollback would then leave a product whose image row
 * survived and whose file did not.
 *
 * <p>It must therefore be called from inside the transaction that makes the
 * change, and the change must not be reported to a caller until that
 * transaction commits. That is also why the queue exists at all rather than an
 * in-memory after-commit callback: a process that dies between the commit and
 * the callback would lose the only record that the file is obsolete.
 */
@Component
public class ProductImageCleanupQueue {

    private final ProductImageCleanupStore store;
    private final FileService fileService;
    private final Clock clock;

    public ProductImageCleanupQueue(ProductImageCleanupStore store, FileService fileService, Clock clock) {
        this.store = store;
        this.fileService = fileService;
        this.clock = clock;
    }

    /**
     * Records that a file the current transaction stopped referencing has to be
     * removed.
     *
     * <p>The stored reference is canonicalized first, so the queue holds the
     * file's identity rather than however it happened to be spelled. Two rows
     * pointing at the same file through a legacy absolute path and a bare name
     * therefore produce one obligation rather than two, and the worker's later
     * reference check compares like with like.
     *
     * <p>A reference that cannot be reduced to a confined file name is not
     * enqueued. There is nothing to delete: such a value names no file inside
     * the upload directory, so an obligation for it could only ever be a
     * permanent failure nobody could act on.
     */
    public void enqueueObsoleteReference(String storedPath) {
        String identity = fileService.canonicalFileIdentity(storedPath);
        if (identity == null) {
            return;
        }
        store.enqueue(identity, LocalDateTime.now(clock));
    }

    /**
     * Convenience for a product that owns several image rows. Duplicates inside
     * one call are collapsed by the store's partial unique index, so a product
     * whose rows happened to share a legacy file records that file once.
     */
    public void enqueueObsoleteReferences(Iterable<String> storedPaths) {
        for (String storedPath : storedPaths) {
            enqueueObsoleteReference(storedPath);
        }
    }
}
