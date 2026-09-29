package com.example.MigrosBackend.service.global;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileServiceTest {
    // JUnit 5 creates a clean temporary directory for this test automatically
    @TempDir
    Path tempDir;

    @Test
    void writeFileToDisk_ShouldSuccessfullyCreateFile() throws IOException {
        // Arrange
        byte[] content = "Hello, World!".getBytes();
        String fileName = "testFile.txt";
        FileService fileService = new FileService(tempDir.toString());

        // Act
        Path resultPath = fileService.writeFileToDisk(content, fileName);

        // Assert
        // 1. Verify the returned path is correct
        assertNotNull(resultPath);
        assertTrue(resultPath.toString().contains(fileName));

        // 2. Verify the file actually exists on the "disk" (temp folder)
        assertTrue(Files.exists(resultPath), "File should exist on disk");

        // 3. Verify the content is correct
        byte[] actualContent = Files.readAllBytes(resultPath);
        assertArrayEquals(content, actualContent);
    }

    @Test
    void writeFileToDisk_ShouldThrowIOException_WhenDirectoryIsInvalid() {
        // Arrange
        byte[] content = "data".getBytes();
        String fileName = "test.txt";
        Path invalidDirectory = tempDir.resolve("not-a-directory");
        assertDoesNotThrow(() -> Files.createFile(invalidDirectory));
        FileService fileService = new FileService(invalidDirectory.toString());

        // Act & Assert
        assertThrows(IOException.class, () ->
                fileService.writeFileToDisk(content, fileName)
        );
    }

    @Test
    void writeFileToDisk_MustNotOverwriteAnExistingFile() throws IOException {
        FileService fileService = new FileService(tempDir.toString());
        fileService.writeFileToDisk("original".getBytes(), "image_x.png");

        assertThrows(FileAlreadyExistsException.class,
                () -> fileService.writeFileToDisk("replacement".getBytes(), "image_x.png"));

        assertArrayEquals("original".getBytes(),
                Files.readAllBytes(tempDir.resolve("image_x.png")),
                "a colliding name must fail loudly rather than truncate another product's image");
    }

    @Test
    void writeFileToDisk_SupportsTwoWritesMadeAtTheSameInstant() throws IOException {
        FileService fileService = new FileService(tempDir.toString());

        Path first = fileService.writeFileToDisk("one".getBytes(), "image_a.png");
        Path second = fileService.writeFileToDisk("two".getBytes(), "image_b.png");

        assertArrayEquals("one".getBytes(), Files.readAllBytes(first));
        assertArrayEquals("two".getBytes(), Files.readAllBytes(second));
        assertEquals(2, Files.list(tempDir).count());
    }

    @Test
    void writeFileToDisk_RejectsNamesThatEscapeTheUploadDirectory() {
        FileService fileService = new FileService(tempDir.toString());

        assertThrows(IOException.class,
                () -> fileService.writeFileToDisk("data".getBytes(), "../escaped.txt"));
        assertThrows(IOException.class,
                () -> fileService.writeFileToDisk("data".getBytes(), "nested/escaped.txt"));
    }

    @Test
    void deleteFileIfExists_RemovesTheFile() throws IOException {
        FileService fileService = new FileService(tempDir.toString());
        Path written = fileService.writeFileToDisk("data".getBytes(), "orphan.png");

        assertTrue(fileService.deleteFileIfExists(written));
        assertFalse(Files.exists(written));
    }

    @Test
    void deleteFileIfExists_IsANoOpForAnAlreadyRemovedFile() throws IOException {
        FileService fileService = new FileService(tempDir.toString());
        Path written = fileService.writeFileToDisk("data".getBytes(), "orphan.png");
        fileService.deleteFileIfExists(written);

        assertFalse(fileService.deleteFileIfExists(written));
        assertFalse(fileService.deleteFileIfExists(null));
    }

    @Test
    void deleteFileIfExists_RefusesPathsOutsideTheUploadDirectory() throws IOException {
        Path outside = tempDir.resolveSibling("outside.txt");
        Files.writeString(outside, "keep me");
        FileService fileService = new FileService(tempDir.toString());

        try {
            assertFalse(fileService.deleteFileIfExists(outside));
            assertTrue(Files.exists(outside), "cleanup must never delete a file outside the upload directory");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void resolveImagePath_ResolvesAPlainStoredNameUnderUploadDir() throws IOException {
        FileService fileService = new FileService(tempDir.toString());

        assertEquals(uploadDir().resolve("image_x.png"),
                fileService.resolveImagePath("image_x.png"));
    }

    @Test
    void resolveImagePath_ExtractsTheNameFromAnAbsoluteStoredPathUnderUploadDir() throws IOException {
        FileService fileService = new FileService(tempDir.toString());
        String storedLegacyPath = uploadDir().resolve("image_legacy.png").toString();

        assertEquals(uploadDir().resolve("image_legacy.png"),
                fileService.resolveImagePath(storedLegacyPath));
    }

    @Test
    void resolveImagePath_ConfinesAnAbsolutePathThatEscapesUploadDir() throws IOException {
        Path outside = tempDir.resolveSibling("secret.png");
        Files.writeString(outside, "do not serve me");
        FileService fileService = new FileService(tempDir.toString());

        try {
            Path resolved = fileService.resolveImagePath(outside.toString());

            assertEquals(uploadDir().resolve("secret.png"), resolved);
            assertTrue(resolved.startsWith(uploadDir()),
                    "an absolute stored path must never be returned verbatim");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void resolveImagePath_ConfinesTraversalAndSeparatorVariants() throws IOException {
        FileService fileService = new FileService(tempDir.toString());

        assertEquals(uploadDir().resolve("escaped.png"),
                fileService.resolveImagePath("../escaped.png"));
        assertEquals(uploadDir().resolve("evil.png"),
                fileService.resolveImagePath("..\\..\\evil.png"));
        assertEquals(uploadDir().resolve("passwd"),
                fileService.resolveImagePath("/etc/passwd"));
        assertEquals(uploadDir().resolve("system.ini"),
                fileService.resolveImagePath("C:\\Windows\\system.ini"));
        assertEquals(uploadDir().resolve("image.png"),
                fileService.resolveImagePath("nested/dir/image.png"));
    }

    @Test
    void resolveImagePath_RejectsDirectoryReferences() {
        FileService fileService = new FileService(tempDir.toString());

        assertThrows(IOException.class, () -> fileService.resolveImagePath(".."));
        assertThrows(IOException.class, () -> fileService.resolveImagePath("."));
        assertThrows(IOException.class, () -> fileService.resolveImagePath("/"));
    }

    private Path uploadDir() {
        return tempDir.toAbsolutePath().normalize();
    }
}
