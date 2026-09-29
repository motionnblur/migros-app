package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.exception.admin.FileUploadFailedException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.service.global.FileService;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

@Component
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
     *
     * <p>The name is also the file's <em>identity</em>, and that is a load-bearing
     * property, not just an anti-collision trick. The cleanup worker deletes a
     * file once no image row resolves to its name, and it performs that check
     * and the unlink as two separate steps. The window between them is only
     * safe because a new upload can never be handed the name of a file that has
     * just been declared obsolete: the name is drawn fresh here, and nothing
     * accepts a client-supplied path. Replacing this with a derived or
     * client-supplied name would let a reference appear between the check and
     * the delete, and a live image would be removed under a product still
     * serving it.
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
