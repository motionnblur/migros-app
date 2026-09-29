package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.exception.admin.FileUploadFailedException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.service.global.FileService;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

final class AdminProductImageOperations {
    private final FileService fileService;

    AdminProductImageOperations(FileService fileService) {
        this.fileService = fileService;
    }

    Path saveRequiredProductImage(MultipartFile selectedImage) {
        if (selectedImage == null || selectedImage.isEmpty()) {
            throw new GeneralException("Product image is required");
        }
        validatePngImage(selectedImage);
        return writeProductImage(selectedImage);
    }

    Path saveProductImage(MultipartFile selectedImage) {
        validatePngImage(selectedImage);
        return writeProductImage(selectedImage);
    }

    private Path writeProductImage(MultipartFile selectedImage) {
        String fileNameToSave = "image_" + System.currentTimeMillis() + ".png";
        try {
            return fileService.writeFileToDisk(selectedImage.getBytes(), fileNameToSave);
        } catch (IOException e) {
            throw new FileUploadFailedException("Failed to read file bytes");
        }
    }

    private void validatePngImage(MultipartFile selectedImage) {
        if (!Objects.equals(selectedImage.getContentType(), "image/png")) {
            throw new GeneralException("Only PNG files are allowed");
        }
    }
}
