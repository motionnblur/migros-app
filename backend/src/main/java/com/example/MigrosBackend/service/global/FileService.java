package com.example.MigrosBackend.service.global;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Service
public class FileService {
    private final Path uploadDir;

    public FileService(@Value("${app.upload-dir:UploadFolder}") String uploadDir) {
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    /**
     * Writes a new file, refusing to overwrite an existing one.
     *
     * <p>{@code CREATE_NEW} is deliberate. Uploads are named from a random UUID
     * rather than a timestamp, but a collision must still fail loudly instead of
     * silently replacing another product's image: a plain
     * {@code Files.write} would truncate the existing file, so two uploads in
     * the same millisecond would leave both products pointing at one image.
     *
     * @throws FileAlreadyExistsException if the target name is already taken
     */
    public Path writeFileToDisk(byte[] fileDataAsBytes,
                                String fileName) throws IOException {
        Files.createDirectories(uploadDir);
        Path filePath = uploadDir.resolve(fileName).normalize();
        if (!filePath.getParent().equals(uploadDir)) {
            throw new IOException("Refusing to write outside the upload directory: " + fileName);
        }
        try (OutputStream out = Files.newOutputStream(filePath,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            out.write(fileDataAsBytes);
        }
        return filePath;
    }

    /**
     * Removes a file that was written but never committed to the database.
     *
     * <p>Used to roll back an orphaned upload when the database operation that
     * should have referenced it fails. A missing file is not an error: the
     * cleanup is best-effort and must never mask the original failure.
     *
     * @return {@code true} if a file was deleted
     */
    public boolean deleteFileIfExists(Path filePath) {
        if (filePath == null) {
            return false;
        }
        try {
            Path normalized = filePath.toAbsolutePath().normalize();
            if (!normalized.startsWith(uploadDir)) {
                return false;
            }
            return Files.deleteIfExists(normalized);
        } catch (IOException ex) {
            return false;
        }
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
