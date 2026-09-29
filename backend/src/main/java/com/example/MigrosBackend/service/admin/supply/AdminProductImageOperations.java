package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.exception.admin.FileUploadFailedException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.service.global.FileService;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

final class AdminProductImageOperations {
    private final FileService fileService;

    AdminProductImageOperations(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * Rejects a missing or non-PNG upload before anything else happens, so a
     * malformed request is reported as such instead of as a downstream lookup
     * failure. This is pure input validation and touches no storage.
     */
    void validateProductImage(MultipartFile selectedImage) {
        if (selectedImage == null || selectedImage.isEmpty()) {
            throw new GeneralException("Product image is required");
        }
        if (!Objects.equals(selectedImage.getContentType(), "image/png")) {
            throw new GeneralException("Only PNG files are allowed");
        }
    }

    /**
     * Persists the validated upload.
     *
     * <p>Names the file from a random UUID instead of a timestamp. A
     * millisecond-resolution timestamp collides: two admins uploading in the
     * same millisecond, or a retry landing on the same tick, produced the same
     * name and the second write silently replaced the first, so two products
     * ended up serving one image. A UUID has no such collision window, and
     * {@code FileService} additionally refuses to overwrite an existing name, so
     * a collision can no longer destroy data even in principle.
     */
    Path writeProductImage(MultipartFile selectedImage) {
        validateProductImage(selectedImage);
        String fileNameToSave = "image_" + UUID.randomUUID() + ".png";
        try {
            return fileService.writeFileToDisk(selectedImage.getBytes(), fileNameToSave);
        } catch (IOException e) {
            throw new FileUploadFailedException("Failed to read file bytes");
        }
    }
}
