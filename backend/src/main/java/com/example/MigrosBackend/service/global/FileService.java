package com.example.MigrosBackend.service.global;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class FileService {
    private final Path uploadDir;

    public FileService(@Value("${app.upload-dir:UploadFolder}") String uploadDir) {
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    public Path writeFileToDisk(byte[] fileDataAsBytes,
                                String fileName) throws IOException {
        Files.createDirectories(uploadDir);
        Path filePath = uploadDir.resolve(fileName).normalize();
        Files.write(filePath, fileDataAsBytes);
        return filePath;
    }

    public Path resolveImagePath(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return uploadDir;
        }

        String normalized = storedPath.trim().replace("\\", "/");
        Path rawPath = Paths.get(normalized);
        if (rawPath.isAbsolute() && Files.exists(rawPath)) {
            return rawPath.normalize();
        }

        // Backward compatibility for older relative paths like UploadFolder/image_x.png.
        Path relativeCandidate = Paths.get(normalized);
        if (Files.exists(relativeCandidate)) {
            return relativeCandidate.toAbsolutePath().normalize();
        }

        String fileName = rawPath.getFileName() != null ? rawPath.getFileName().toString() : normalized;
        return uploadDir.resolve(fileName).normalize();
    }
}
