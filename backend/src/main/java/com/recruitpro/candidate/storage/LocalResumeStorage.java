package com.recruitpro.candidate.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.UUID;

@Service
public class LocalResumeStorage implements ResumeStorage, InitializingBean {
    private static final Logger logger = LoggerFactory.getLogger(LocalResumeStorage.class);
    private final Path root;

    public LocalResumeStorage(@Value("${app.file-storage.upload-dir:./uploads}") String uploadDir) {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @Override
    public void afterPropertiesSet() {
        logger.info("Validating resume storage at {}", root);
        try {
            validateStorageAvailability();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Resume storage is unavailable at " + root, exception);
        }
    }

    void validateStorageAvailability() throws IOException {
        Files.createDirectories(root);
        Path probe = null;
        Throwable validationFailure = null;
        try {
            probe = Files.createTempFile(root, ".recruitpro-storage-probe-", ".tmp");
            byte[] expected = UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeProbe(probe, expected);
            byte[] actual = readProbe(probe);
            if (!Arrays.equals(expected, actual)) {
                throw new IOException("Resume storage probe contents did not match");
            }
            deleteProbe(probe);
            probe = null;
        } catch (IOException | RuntimeException exception) {
            validationFailure = exception;
            throw exception;
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException | RuntimeException cleanupFailure) {
                    if (validationFailure != null) {
                        validationFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    protected void writeProbe(Path probe, byte[] content) throws IOException {
        Files.write(probe, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    protected byte[] readProbe(Path probe) throws IOException {
        return Files.readAllBytes(probe);
    }

    protected void deleteProbe(Path probe) throws IOException {
        Files.delete(probe);
    }

    private Path resolve(String key) throws IOException {
        if (key == null || !key.matches("[0-9a-fA-F-]{36}")) throw new IOException("Invalid storage identifier");
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw new IOException("Storage path is outside configured root");
        return target;
    }
    @Override public void store(String key, byte[] content) throws IOException {
        Files.createDirectories(root);
        // POSIX permissions may be unsupported or ineffective on Windows; provision and verify NTFS ACLs separately.
        try { Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) { }
        Path target = resolve(key);
        try {
            FileAttribute<?> privateFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
            Files.createFile(target, privateFile);
        } catch (UnsupportedOperationException ignored) {
            Files.createFile(target);
        }
        Files.write(target, content, StandardOpenOption.WRITE);
    }
    @Override public byte[] load(String key) throws IOException {
        Path target = resolve(key);
        if (Files.isSymbolicLink(target) || !target.toRealPath().startsWith(root.toRealPath()))
            throw new IOException("Stored file is outside configured root");
        return Files.readAllBytes(target);
    }
    @Override public void delete(String key) throws IOException { Files.deleteIfExists(resolve(key)); }
}
