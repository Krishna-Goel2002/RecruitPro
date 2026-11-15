package com.recruitpro.assessment.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Local storage of work-sample submission files (WP-PLAT), in its own directory
 * ({@code app.file-storage.work-sample-dir}, default {@code <upload-dir>/work-samples}), separate from resumes. Keys
 * are random UUIDs; paths are resolved and checked to stay inside the root; files are owner-only where POSIX
 * permissions exist.
 */
@Component
public class WorkSampleFileStorage {

    private static final Logger log = LoggerFactory.getLogger(WorkSampleFileStorage.class);
    private final Path root;

    public WorkSampleFileStorage(@Value("${app.file-storage.work-sample-dir:${app.file-storage.upload-dir:./uploads}/work-samples}")
                                 String directory) {
        this.root = Paths.get(directory).toAbsolutePath().normalize();
    }

    public void store(String key, byte[] content) throws IOException {
        Files.createDirectories(root);
        try {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows: rely on the directory's ACLs.
        }
        Path target = resolve(key);
        try {
            FileAttribute<?> privateFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
            Files.createFile(target, privateFile);
        } catch (UnsupportedOperationException ignored) {
            Files.createFile(target);
        }
        Files.write(target, content, StandardOpenOption.WRITE);
    }

    public byte[] load(String key) throws IOException {
        Path target = resolve(key);
        if (Files.isSymbolicLink(target) || !target.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("Stored file is outside the configured root");
        }
        return Files.readAllBytes(target);
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException | RuntimeException ex) {
            log.warn("Could not delete a work-sample file: {}", ex.getClass().getSimpleName());
        }
    }

    private Path resolve(String key) throws IOException {
        if (key == null || !key.matches("[0-9a-fA-F-]{36}")) {
            throw new IOException("Invalid storage identifier");
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Storage path is outside the configured root");
        }
        return target;
    }
}
