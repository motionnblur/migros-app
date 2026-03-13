package com.example.MigrosBackend.service.global;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
}
