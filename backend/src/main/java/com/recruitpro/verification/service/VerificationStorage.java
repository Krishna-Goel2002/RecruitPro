package com.recruitpro.verification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Local storage of evidence snapshots and uploaded verification documents (same model as
 * {@code LocalResumeStorage}): files live under {@code app.verification.storage-dir}, keys are random UUIDs and
 * every key is validated and resolved inside the root, so a stored key can never point elsewhere.
 */
@Component
public class VerificationStorage {

    private static final Logger log = LoggerFactory.getLogger(VerificationStorage.class);
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Path root;

    public VerificationStorage(@Value("${app.verification.storage-dir:./uploads/verification}") String storageDir) {
        this.root = Paths.get(storageDir).toAbsolutePath().normalize();
    }

    /** A new random storage key. */
    public String newKey() {
        return UUID.randomUUID().toString();
    }

    /** Stores content under a new key and returns it. */
    public String store(byte[] content) throws IOException {
        String key = newKey();
        Files.createDirectories(root);
        try {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows: provision ACLs separately.
        }
        Path target = resolve(key);
        try {
            FileAttribute<?> privateFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
            Files.createFile(target, privateFile);
        } catch (UnsupportedOperationException ignored) {
            Files.createFile(target);
        }
        Files.write(target, content, StandardOpenOption.WRITE);
        return key;
    }

    public byte[] load(String key) throws IOException {
        Path target = resolve(key);
        if (Files.isSymbolicLink(target) || !target.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("Stored file is outside the configured root");
        }
        return Files.readAllBytes(target);
    }

    public boolean exists(String key) {
        try {
            return Files.exists(resolve(key));
        } catch (IOException ex) {
            return false;
        }
    }

    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    /** Deletes files quietly (missing or invalid keys are ignored). */
    public void deleteQuietly(Collection<String> keys) {
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            try {
                delete(key);
            } catch (IOException | RuntimeException ex) {
                log.warn("Could not delete a verification file: {}", ex.getClass().getSimpleName());
            }
        }
    }

    /** Deletes the files once the surrounding transaction commits (immediately when there is none). */
    public void deleteAfterCommit(Collection<String> keys) {
        List<String> copy = keys.stream().filter(k -> k != null && !k.isBlank()).toList();
        if (copy.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteQuietly(copy);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(copy);
            }
        });
    }

    /** Deletes the files if the surrounding transaction rolls back (files written before the rows). */
    public void deleteOnRollback(Collection<String> keys) {
        List<String> copy = keys.stream().filter(k -> k != null && !k.isBlank()).toList();
        if (copy.isEmpty() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(copy);
                }
            }
        });
    }

    Path root() {
        return root;
    }

    private Path resolve(String key) throws IOException {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IOException("Invalid storage identifier");
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Storage path is outside the configured root");
        }
        return target;
    }
}
