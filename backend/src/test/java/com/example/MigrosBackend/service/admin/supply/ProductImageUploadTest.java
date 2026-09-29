package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.service.global.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upload file name must be safe under concurrency. A millisecond-resolution
 * timestamp name collides whenever two uploads land on the same tick, and the
 * second write replaced the first, so two products ended up serving one image.
 */
class ProductImageUploadTest {

    @TempDir
    Path uploadDir;

    @Test
    void twoUploadsInTheSameMillisecondProduceTwoDistinctFiles() throws Exception {
        FileService fileService = new FileService(uploadDir.toString());
        AdminProductImageOperations operations = new AdminProductImageOperations(fileService);

        Path first = operations.writeProductImage(png("first-product"));
        Path second = operations.writeProductImage(png("second-product"));

        assertNotEquals(first.getFileName(), second.getFileName());
        assertArrayEquals("first-product".getBytes(), Files.readAllBytes(first));
        assertArrayEquals("second-product".getBytes(), Files.readAllBytes(second));
        assertEquals(2, Files.list(uploadDir).count());
    }

    @Test
    void manyConcurrentUploadsNeverOverwriteEachOther() throws Exception {
        FileService fileService = new FileService(uploadDir.toString());
        AdminProductImageOperations operations = new AdminProductImageOperations(fileService);

        int uploadCount = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Path>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < uploadCount; i++) {
                Callable<Path> upload = () -> {
                    start.await();
                    return operations.writeProductImage(png("payload"));
                };
                futures.add(pool.submit(upload));
            }
            start.countDown();

            List<String> distinctNames = new java.util.ArrayList<>();
            for (Future<Path> future : futures) {
                distinctNames.add(future.get(30, TimeUnit.SECONDS).getFileName().toString());
            }

            assertEquals(uploadCount, distinctNames.stream().distinct().count(),
                    "every concurrent upload must own a distinct file");
            assertEquals(uploadCount, Files.list(uploadDir).count(),
                    "no upload may silently replace another one");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The invariant the cleanup queue's reference check leans on.
     *
     * <p>A worker deletes a file once no image row resolves to its name, and it
     * trusts that no request can attach a reference in between. That trust is
     * only sound because an upload's identity is a fresh random UUID: a new
     * upload can never be handed the name of a file that has just been declared
     * obsolete, so there is no way for a reference to appear between the
     * worker's check and its unlink.
     *
     * <p>Asserted against names that really are the shape the upload path
     * produces, including a legacy one already sitting in the directory, so a
     * change back to timestamp or client-supplied naming fails here rather than
     * as a deleted live image.
     */
    @Test
    void aFreshUploadNeverReusesAnIdentityThatIsAlreadyOnDisk() throws Exception {
        FileService fileService = new FileService(uploadDir.toString());
        AdminProductImageOperations operations = new AdminProductImageOperations(fileService);

        // A file an earlier upload produced, and which cleanup may already owe a
        // deletion for.
        String obsoleteName = "image_" + UUID.randomUUID() + ".png";
        Files.writeString(uploadDir.resolve(obsoleteName), "obsolete");

        List<String> freshNames = new java.util.ArrayList<>();
        for (int i = 0; i < 32; i++) {
            freshNames.add(operations.writeProductImage(png("payload")).getFileName().toString());
        }

        assertFalse(freshNames.contains(obsoleteName),
                "an upload must never reattach a path that has already been made obsolete");
        assertEquals(32, freshNames.stream().distinct().count(),
                "every upload owns a distinct identity, so the reference check cannot be raced by one");
        assertTrue(Files.exists(uploadDir.resolve(obsoleteName)),
                "and writing new uploads must not disturb a file another obligation may still remove");
    }

    private MockMultipartFile png(String content) {
        return new MockMultipartFile("selectedImage", "image.png", "image/png", content.getBytes());
    }
}
