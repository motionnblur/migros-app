package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.service.global.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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

    private MockMultipartFile png(String content) {
        return new MockMultipartFile("selectedImage", "image.png", "image/png", content.getBytes());
    }
}
