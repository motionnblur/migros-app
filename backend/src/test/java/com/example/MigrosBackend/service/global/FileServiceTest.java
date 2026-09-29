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

    /**
     * The boolean delete cannot express what the cleanup worker needs: that a
     * file which was already gone counts as done, while a refusal or a
     * filesystem failure is still work owed. Collapsing the three into
     * {@code false} is what would make a worker either re-delete a file
     * forever or drop a failure on the floor.
     */
    @Test
    void deleteStoredFile_DistinguishesDoneAbsentRefusedAndFailed() throws IOException {
        FileService fileService = new FileService(tempDir.toString());
        Path written = fileService.writeFileToDisk("data".getBytes(), "obsolete.png");

        assertEquals(FileService.DeletionOutcome.DELETED, fileService.deleteStoredFile("obsolete.png"));
        assertEquals(FileService.DeletionOutcome.ALREADY_ABSENT, fileService.deleteStoredFile("obsolete.png"),
                "a file removed by other means already satisfies the cleanup; it is not a failure");
        assertEquals(FileService.DeletionOutcome.REFUSED, fileService.deleteStoredFile(".."),
                "a directory reference names no file and must never be resolved to one");
        assertEquals(FileService.DeletionOutcome.REFUSED, fileService.deleteStoredFile("  "));
        assertEquals(FileService.DeletionOutcome.REFUSED, fileService.deleteStoredFile("C:obsolete.png"),
                "a drive-relative prefix is not a plain file name");
        assertEquals(FileService.DeletionOutcome.REFUSED, fileService.deleteStoredFile(null));
    }

    /**
     * A stored legacy path is a spelling, not an address. Deleting by the
     * reference has to reach the same file the image-serving path would, and
     * never anything else - whatever separators and directories the value
     * carries.
     */
    @Test
    void deleteStoredFile_ConfinesEverySpellingToTheUploadDirectory() throws IOException {
        Path outside = tempDir.resolveSibling("keep-me.png");
        Files.writeString(outside, "not an upload");
        FileService fileService = new FileService(tempDir.toString());

        try {
            for (String spelling : new String[]{
                    fileService.writeFileToDisk("data".getBytes(), "obsolete.png").toString(),
                    "..\\obsolete.png",
                    "../obsolete.png",
                    "nested/dir/obsolete.png",
                    "/some/legacy/root/obsolete.png",
                    "obsolete.png"}) {
                Files.writeString(uploadDir().resolve("obsolete.png"), spelling);

                assertEquals(FileService.DeletionOutcome.DELETED, fileService.deleteStoredFile(spelling),
                        "unexpected outcome for the spelling: " + spelling);
                assertFalse(Files.exists(uploadDir().resolve("obsolete.png")),
                        "the spelling " + spelling + " did not resolve to the file inside the upload directory");
            }
            assertTrue(Files.exists(outside),
                    "cleanup must never delete a file outside the upload directory");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void deleteStoredFile_ReportsAFailureRatherThanClaimingSuccess() throws IOException {
        // A non-empty directory where a file is expected makes the unlink fail
        // on every platform: an empty one would simply be removed, which is not
        // a failure at all.
        Path occupied = uploadDir().resolve("occupied.png");
        Files.createDirectories(occupied);
        Files.writeString(occupied.resolve("occupant.txt"), "not empty");
        FileService fileService = new FileService(tempDir.toString());

        try {
            assertEquals(FileService.DeletionOutcome.FAILED, fileService.deleteStoredFile("occupied.png"),
                    "a refused deletion is still work owed, not a completed cleanup");
            assertTrue(Files.exists(occupied));
        } finally {
            Files.deleteIfExists(occupied.resolve("occupant.txt"));
            Files.deleteIfExists(occupied);
        }
    }

    /**
     * The identity is what the cleanup queue stores and what the worker's
     * reference check compares, so it has to be the same name {@code
     * resolveImagePath} would land on. One rule, two callers: two copies of it
     * is how a live file ends up deleted.
     */
    @Test
    void canonicalFileIdentity_AgreesWithThePathServingUses() throws IOException {
        FileService fileService = new FileService(tempDir.toString());

        for (String stored : new String[]{
                "image_x.png",
                uploadDir().resolve("image_x.png").toString(),
                "..\\..\\image_x.png",
                "../image_x.png",
                "/etc/image_x.png",
                "C:\\Windows\\image_x.png",
                "nested/dir/image_x.png",
                "  image_x.png  "}) {
            assertEquals("image_x.png", fileService.canonicalFileIdentity(stored),
                    "unexpected canonical identity for: " + stored);
            assertEquals(fileService.resolveImagePath(stored).getFileName().toString(),
                    fileService.canonicalFileIdentity(stored));
        }
    }

    @Test
    void canonicalFileIdentity_IsNullForAnythingThatNamesNoFile() {
        FileService fileService = new FileService(tempDir.toString());

        assertNull(fileService.canonicalFileIdentity(null));
        assertNull(fileService.canonicalFileIdentity("   "));
        assertNull(fileService.canonicalFileIdentity(".."));
        assertNull(fileService.canonicalFileIdentity("."));
        assertNull(fileService.canonicalFileIdentity("/"));
        assertNull(fileService.canonicalFileIdentity("C:image_x.png"),
                "a drive-relative prefix must not be mistaken for a plain file name");
    }

    private Path uploadDir() {
        return tempDir.toAbsolutePath().normalize();
    }
}
