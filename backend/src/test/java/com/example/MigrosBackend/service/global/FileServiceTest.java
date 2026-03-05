package com.example.MigrosBackend.service.global;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileServiceTest {
    private final FileService fileService = new FileService();

    // JUnit 5 creates a clean temporary directory for this test automatically
    @TempDir
    Path tempDir;

    @Test
    void writeFileToDisk_ShouldSuccessfullyCreateFile() throws IOException {
        // Arrange
        byte[] content = "Hello, World!".getBytes();
        String fileName = "testFile.txt";
        String directory = tempDir.toString(); // Use the temp directory path

        // Act
        Path resultPath = fileService.writeFileToDisk(content, fileName, directory);

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
        // A path that likely doesn't exist or is inaccessible
        String invalidDirectory = "/invalid_path_that_does_not_exist/secret_folder";

        // Act & Assert
        assertThrows(IOException.class, () ->
                fileService.writeFileToDisk(content, fileName, invalidDirectory)
        );
    }
}