package com.recruitpro.assessment.service;

import com.recruitpro.assessment.dto.WorkSampleFileResponse;
import com.recruitpro.assessment.entity.WorkSampleSubmissionFile;
import com.recruitpro.assessment.repository.WorkSampleSubmissionFileRepository;
import com.recruitpro.assessment.storage.WorkSampleFileStorage;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Files submitted with a work sample (WP-PLAT): pdf, zip, docx, pptx, png, jpg; at most
 * {@code app.work-samples.files.max-size-mb} (20) MB each and {@value #MAX_FILES} per assignment. The type comes from
 * the extension and must match the content's magic bytes (docx/pptx: a ZIP whose central directory lists
 * {@code [Content_Types].xml} and the Word/PowerPoint main part). Archives are never extracted: only the central
 * directory of a ZIP is read. Files are written after validation; if the transaction rolls back they are deleted.
 */
@Service
public class WorkSampleFileService {

    public static final int MAX_FILES = 3;
    static final Map<String, String> CONTENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "zip", "application/zip",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg");

    private final WorkSampleSubmissionFileRepository repository;
    private final WorkSampleFileStorage storage;
    private final long maxBytes;

    public WorkSampleFileService(WorkSampleSubmissionFileRepository repository, WorkSampleFileStorage storage,
                                 @Value("${app.work-samples.files.max-size-mb:20}") long maxSizeMb) {
        this.repository = repository;
        this.storage = storage;
        this.maxBytes = Math.max(1, maxSizeMb) * 1024L * 1024L;
    }

    /** A checked upload, ready to store. */
    public record ValidatedFile(String originalName, String contentType, byte[] content, String sha256) {
    }

    /** Validates count, size, name and type of the uploads (empty parts are ignored). 400 on any problem. */
    public List<ValidatedFile> validate(List<MultipartFile> files) {
        List<ValidatedFile> result = new ArrayList<>();
        if (files == null) {
            return result;
        }
        List<MultipartFile> present = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                present.add(file);
            }
        }
        if (present.size() > MAX_FILES) {
            throw new BadRequestException("Attach at most " + MAX_FILES + " files");
        }
        for (MultipartFile file : present) {
            if (file.getSize() > maxBytes) {
                throw new BadRequestException("Each file must be at most " + (maxBytes / (1024L * 1024L)) + " MB");
            }
            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (IOException ex) {
                throw new BadRequestException("A file could not be read");
            }
            result.add(validate(file.getOriginalFilename(), bytes));
        }
        return result;
    }

    /** Validates one file by name and content. */
    public ValidatedFile validate(String suppliedName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException("Files must not be empty");
        }
        if (bytes.length > maxBytes) {
            throw new BadRequestException("Each file must be at most " + (maxBytes / (1024L * 1024L)) + " MB");
        }
        String name = safeName(suppliedName);
        int dot = name.lastIndexOf('.');
        String extension = dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String contentType = CONTENT_TYPES.get(extension);
        if (contentType == null) {
            throw new BadRequestException("Only PDF, ZIP, DOCX, PPTX, PNG and JPG files can be attached");
        }
        if (!matchesContent(extension, bytes)) {
            throw new BadRequestException("The content of '" + limit(name, 80) + "' does not match a valid ." + extension + " file");
        }
        return new ValidatedFile(name, contentType, bytes, sha256(bytes));
    }

    /**
     * Stores validated files for an assignment (inside the caller's transaction); the stored files are removed again
     * if that transaction rolls back.
     */
    public List<WorkSampleSubmissionFile> store(Long assignmentId, List<ValidatedFile> files) {
        List<WorkSampleSubmissionFile> saved = new ArrayList<>();
        if (files.isEmpty()) {
            return saved;
        }
        if (repository.countByAssignmentId(assignmentId) + files.size() > MAX_FILES) {
            throw new BadRequestException("Attach at most " + MAX_FILES + " files");
        }
        List<String> written = new ArrayList<>();
        try {
            for (ValidatedFile file : files) {
                String key = UUID.randomUUID().toString();
                storage.store(key, file.content());
                written.add(key);
                WorkSampleSubmissionFile row = new WorkSampleSubmissionFile();
                row.setAssignmentId(assignmentId);
                row.setOriginalName(file.originalName());
                row.setContentType(file.contentType());
                row.setSizeBytes(file.content().length);
                row.setStoragePath(key);
                row.setSha256(file.sha256());
                saved.add(repository.save(row));
            }
        } catch (IOException | RuntimeException ex) {
            written.forEach(storage::delete);
            throw ex instanceof RuntimeException runtime ? runtime : new IllegalStateException("A file could not be stored");
        }
        deleteOnRollback(written);
        return saved;
    }

    public List<WorkSampleFileResponse> list(Long assignmentId) {
        return repository.findAllByAssignmentIdOrderByIdAsc(assignmentId).stream().map(WorkSampleFileService::view).toList();
    }

    /** Files of several assignments, keyed by assignment id (batch for list views). */
    public Map<Long, List<WorkSampleFileResponse>> listFor(Collection<Long> assignmentIds) {
        Map<Long, List<WorkSampleFileResponse>> result = new HashMap<>();
        if (assignmentIds == null || assignmentIds.isEmpty()) {
            return result;
        }
        for (WorkSampleSubmissionFile file : repository.findAllByAssignmentIdInOrderByIdAsc(new HashSet<>(assignmentIds))) {
            result.computeIfAbsent(file.getAssignmentId(), id -> new ArrayList<>()).add(view(file));
        }
        return result;
    }

    /** The file of an assignment with its bytes; 404 when unknown or missing on disk. */
    public Download load(Long assignmentId, Long fileId) {
        WorkSampleSubmissionFile file = repository.findByIdAndAssignmentId(fileId, assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));
        try {
            return new Download(file, storage.load(file.getStoragePath()));
        } catch (NoSuchFileException ex) {
            throw new ResourceNotFoundException("File not found");
        } catch (IOException ex) {
            throw new IllegalStateException("The file could not be read");
        }
    }

    /** Deletes the files of an assignment (rows now, stored files after commit); used by account deletion. */
    public int deleteAll(Long assignmentId) {
        List<WorkSampleSubmissionFile> files = repository.findAllByAssignmentIdOrderByIdAsc(assignmentId);
        if (files.isEmpty()) {
            return 0;
        }
        List<String> keys = files.stream().map(WorkSampleSubmissionFile::getStoragePath).toList();
        repository.deleteAll(files);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    keys.forEach(storage::delete);
                }
            });
        } else {
            keys.forEach(storage::delete);
        }
        return files.size();
    }

    /** A loaded file. */
    public record Download(WorkSampleSubmissionFile file, byte[] content) {
    }

    static WorkSampleFileResponse view(WorkSampleSubmissionFile file) {
        return new WorkSampleFileResponse(file.getId(), file.getOriginalName(), file.getContentType(), file.getSizeBytes(),
                file.getSha256(), file.getCreatedAt());
    }

    private void deleteOnRollback(List<String> keys) {
        if (keys.isEmpty() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        List<String> copy = List.copyOf(keys);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    copy.forEach(storage::delete);
                }
            }
        });
    }

    // ---- content checks ----

    static boolean matchesContent(String extension, byte[] bytes) {
        return switch (extension) {
            case "pdf" -> startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "png" -> startsWith(bytes, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            case "jpg", "jpeg" -> startsWith(bytes, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case "zip" -> startsWith(bytes, new byte[] {'P', 'K', 0x03, 0x04}) || startsWith(bytes, new byte[] {'P', 'K', 0x05, 0x06});
            case "docx" -> isOfficeZip(bytes, "word/document.xml");
            case "pptx" -> isOfficeZip(bytes, "ppt/presentation.xml");
            default -> false;
        };
    }

    private static boolean isOfficeZip(byte[] bytes, String mainPart) {
        if (!startsWith(bytes, new byte[] {'P', 'K', 0x03, 0x04})) {
            return false;
        }
        Set<String> names = zipEntryNames(bytes, 10_000);
        return names.contains("[Content_Types].xml") && names.contains(mainPart);
    }

    /**
     * Entry names from a ZIP's central directory (end-of-central-directory record, then the file headers), without
     * decompressing anything. Empty when the structure is not a valid ZIP.
     */
    static Set<String> zipEntryNames(byte[] bytes, int maxEntries) {
        Set<String> names = new HashSet<>();
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = -1;
        int lowest = Math.max(0, bytes.length - 22 - 65_535);
        for (int i = bytes.length - 22; i >= lowest; i--) {
            if (buffer.getInt(i) == 0x06054b50) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            return names;
        }
        int entries = Short.toUnsignedInt(buffer.getShort(eocd + 10));
        long offset = Integer.toUnsignedLong(buffer.getInt(eocd + 16));
        if (offset >= bytes.length) {
            return names;
        }
        int position = (int) offset;
        for (int i = 0; i < entries && i < maxEntries; i++) {
            if (position + 46 > bytes.length || buffer.getInt(position) != 0x02014b50) {
                break;
            }
            int nameLength = Short.toUnsignedInt(buffer.getShort(position + 28));
            int extraLength = Short.toUnsignedInt(buffer.getShort(position + 30));
            int commentLength = Short.toUnsignedInt(buffer.getShort(position + 32));
            if (position + 46 + nameLength > bytes.length) {
                break;
            }
            names.add(new String(bytes, position + 46, nameLength, StandardCharsets.UTF_8));
            position += 46 + nameLength + extraLength + commentLength;
        }
        return names;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** File name without path, control characters or quotes; 1-255 characters. */
    static String safeName(String supplied) {
        String name = supplied == null ? "" : supplied.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        StringBuilder clean = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            if (c >= 0x20 && c != 0x7F && c != '"') {
                clean.append(c);
            }
        }
        String result = clean.toString().trim();
        if (result.isEmpty() || result.length() > 255) {
            throw new BadRequestException("Every file needs a name of 1 to 255 characters");
        }
        return result;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String limit(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
