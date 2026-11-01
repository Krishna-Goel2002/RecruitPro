package com.recruitpro.candidate.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalResumeStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsStorageKeysThatCouldEscapeThePrivateRoot() {
        LocalResumeStorage storage = storage();

        assertThrows(IOException.class, () -> storage.store("../escaped", new byte[]{1}));
        assertThrows(IOException.class, () -> storage.load("../escaped"));
        assertFalse(Files.exists(temporaryDirectory.resolve("escaped")));
    }

    @Test
    void storesAndLoadsFilesByOpaqueIdentifiersInsideThePrivateRoot() throws Exception {
        LocalResumeStorage storage = storage();
        String key = UUID.randomUUID().toString();
        byte[] contents = new byte[]{4, 8, 15, 16, 23, 42};

        storage.store(key, contents);

        Path storedFile = temporaryDirectory.resolve("private-resumes").resolve(key).normalize();
        assertTrue(storedFile.startsWith(temporaryDirectory.resolve("private-resumes").normalize()));
        assertArrayEquals(contents, storage.load(key));
    }

    @Test
    void validatesStorageWithAnIsolatedProbeAndLeavesExistingResumesUntouched() throws Exception {
        Path configuredPath = temporaryDirectory.resolve("nested").resolve("..").resolve("private-resumes");
        Path root = configuredPath.toAbsolutePath().normalize();
        LocalResumeStorage storage = new LocalResumeStorage(configuredPath.toString());
        byte[] existingResume = new byte[]{9, 2, 6};
        Path existingFile = Files.createDirectories(root).resolve(UUID.randomUUID().toString());
        Files.write(existingFile, existingResume);

        storage.afterPropertiesSet();

        assertEquals(root, ReflectionTestUtils.getField(storage, "root"));
        assertArrayEquals(existingResume, Files.readAllBytes(existingFile));
        try (var files = Files.list(root)) {
            assertTrue(files.allMatch(path -> !path.getFileName().toString().startsWith(".recruitpro-storage-probe-")));
        }
    }

    @Test
    void failsStartupWhenStorageCannotBeCreated() throws Exception {
        Path pathThatIsAFile = temporaryDirectory.resolve("not-a-directory");
        Files.write(pathThatIsAFile, new byte[]{1});
        LocalResumeStorage storage = new LocalResumeStorage(pathThatIsAFile.resolve("resumes").toString());

        IllegalStateException exception = assertThrows(IllegalStateException.class, storage::afterPropertiesSet);

        assertTrue(exception.getMessage().contains(pathThatIsAFile.toAbsolutePath().normalize().toString()));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(pathThatIsAFile));
    }

    @Test
    void cleansUpProbeWhenAFileOperationFails() {
        Path root = temporaryDirectory.resolve("read-failure-resumes");
        LocalResumeStorage storage = new LocalResumeStorage(root.toString()) {
            @Override
            protected byte[] readProbe(Path probe) throws IOException {
                throw new IOException("simulated probe read failure");
            }
        };

        IllegalStateException exception = assertThrows(IllegalStateException.class, storage::afterPropertiesSet);

        assertTrue(exception.getCause().getMessage().contains("simulated probe read failure"));
        try (var files = Files.list(root)) {
            assertFalse(files.findAny().isPresent());
        } catch (IOException exceptionDuringInspection) {
            throw new AssertionError(exceptionDuringInspection);
        }
    }

    private LocalResumeStorage storage() {
        return new LocalResumeStorage(temporaryDirectory.resolve("private-resumes").toString());
    }
}
