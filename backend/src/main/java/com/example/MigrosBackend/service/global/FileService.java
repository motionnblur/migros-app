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

    /**
     * Resolves a stored image reference to a file inside the upload directory.
     *
     * <p>The stored value is never trusted as a path. Whether the database holds
     * a bare file name, a legacy absolute path, or a value crafted to escape,
     * only its final name component is used and the result is confined to
     * {@code uploadDir} with the same parent-equality check the write path uses.
     * Refusing to return an arbitrary existing absolute path prevents a row in
     * {@code product_image_entity} from pointing image serving at any file on
     * the host.
     *
     * @throws IOException if the value cannot be reduced to a plain file name
     *                     confined to the upload directory
     */
    public Path resolveImagePath(String storedPath) throws IOException {
        if (storedPath == null || storedPath.isBlank()) {
            return uploadDir;
        }

        String fileName = extractFileName(storedPath);
        Path resolved = uploadDir.resolve(fileName).normalize();
        if (!resolved.getParent().equals(uploadDir)) {
            throw new IOException("Refusing to resolve an image path outside the upload directory: " + storedPath);
        }
        return resolved;
    }

    /**
     * Reduces a stored value to its final name component.
     *
     * <p>Both separators are normalized first so a Windows-style path cannot
     * smuggle a directory past a Unix path parser. A value whose final component
     * is empty, is a directory reference, or still carries a drive-relative
     * prefix is rejected rather than resolved.
     */
    private static String extractFileName(String storedPath) throws IOException {
        String normalized = storedPath.trim().replace('\\', '/');
        int lastSeparator = normalized.lastIndexOf('/');
        String fileName = lastSeparator >= 0 ? normalized.substring(lastSeparator + 1) : normalized;
        if (fileName.isEmpty() || ".".equals(fileName) || "..".equals(fileName) || fileName.indexOf(':') >= 0) {
            throw new IOException("Refusing to resolve an image path outside the upload directory: " + storedPath);
        }
        return fileName;
    }
}
