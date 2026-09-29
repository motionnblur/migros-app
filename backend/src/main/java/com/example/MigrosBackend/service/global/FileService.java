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
     * What happened when a stored image reference was deleted.
     *
     * <p>{@link #deleteFileIfExists} answers a boolean, which collapses two very
     * different outcomes into {@code false}: a file that was not there any more
     * (the work is done) and a deletion that failed (the work is still owed).
     * The rollback path does not care - it is best-effort and must never mask
     * the original failure - but the durable cleanup worker does. It may
     * acknowledge a deletion that is already done and must retry one that is
     * not, and it cannot tell those apart from a boolean.
     */
    public enum DeletionOutcome {
        /** The file existed and was removed. */
        DELETED,
        /** The file was already absent, so the desired state already held. */
        ALREADY_ABSENT,
        /** The reference could not be reduced to a confined file; nothing was touched. */
        REFUSED,
        /** The filesystem call failed; the file may or may not still be there. */
        FAILED
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
     * The one canonical form of a stored image reference: its final name
     * component, confined to the upload directory.
     *
     * <p>Deliberately the same rule {@link #resolveImagePath} applies, exposed
     * separately so the cleanup queue and the cleanup worker can compare
     * identities without materializing paths. Everything that touches a
     * reference - serving an image, deciding whether two references name the
     * same file, deciding whether a file may be deleted - has to agree on what
     * that name is, and a second copy of the rule is how two of them would come
     * to disagree and delete a file something else still points at.
     *
     * @return the bare file name, or {@code null} when the value cannot be
     *         reduced to one (blank, a directory reference, or carrying a
     *         drive-relative prefix)
     */
    public String canonicalFileIdentity(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return null;
        }
        try {
            return extractFileName(storedPath);
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * Deletes the file a stored reference names, reporting which of the
     * distinguishable outcomes occurred.
     *
     * <p>The reference is treated exactly as untrusted as everywhere else: it is
     * reduced to its final name component and resolved inside the upload
     * directory, so a legacy absolute path or a traversal value can only ever
     * address a file that is already inside the upload directory, and a value
     * that cannot be reduced at all is refused rather than resolved. Nothing
     * outside {@code uploadDir} is ever deleted, whatever the stored value says.
     *
     * <p>{@link DeletionOutcome#ALREADY_ABSENT} is a success, not a failure:
     * the desired state is "this file is not on disk", and a file removed by
     * hand in the meantime already satisfies it. That distinction is the whole
     * reason this method exists next to {@link #deleteFileIfExists}.
     */
    public DeletionOutcome deleteStoredFile(String storedPath) {
        String fileName = canonicalFileIdentity(storedPath);
        if (fileName == null) {
            return DeletionOutcome.REFUSED;
        }
        Path resolved = uploadDir.resolve(fileName).normalize();
        if (!resolved.getParent().equals(uploadDir)) {
            return DeletionOutcome.REFUSED;
        }
        try {
            return Files.deleteIfExists(resolved)
                    ? DeletionOutcome.DELETED
                    : DeletionOutcome.ALREADY_ABSENT;
        } catch (IOException | RuntimeException ex) {
            return DeletionOutcome.FAILED;
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
