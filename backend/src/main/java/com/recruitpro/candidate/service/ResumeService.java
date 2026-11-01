package com.recruitpro.candidate.service;

import com.recruitpro.candidate.dto.ResumeResponse;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.candidate.storage.ResumeStorage;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipInputStream;
import java.nio.file.NoSuchFileException;

@Service
@RequiredArgsConstructor
@Transactional
public class ResumeService {
    private final CandidateProfileService profileService;
    private final ResumeRepository resumeRepository;
    private final ResumeStorage storage;
    private final ResumeIntelligenceService intelligenceService;
    @Value("${app.file-storage.max-size-mb:10}") private long maxSizeMb;
    @Value("${app.file-storage.allowed-types:pdf,doc,docx}") private String allowedTypes;

    public ResumeResponse upload(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BadRequestException("Resume file must not be empty");
        long maxBytes = maxSizeMb * 1024L * 1024L;
        if (file.getSize() > maxBytes) throw new BadRequestException("Resume exceeds the maximum allowed size");
        String filename = safeFilename(file.getOriginalFilename());
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) throw new BadRequestException("Resume must have a supported file extension");
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        Set<String> allowed = new HashSet<>();
        Arrays.stream(allowedTypes.split(",")).map(String::trim).map(s -> s.toLowerCase(Locale.ROOT)).forEach(allowed::add);
        if (!allowed.contains(extension) || !(extension.equals("pdf") || extension.equals("docx") || extension.equals("doc")))
            throw new BadRequestException("Only PDF, DOCX, and DOC resumes are supported");
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (IOException ex) { throw new BadRequestException("Resume could not be read"); }
        validateContent(extension, bytes);
        CandidateProfile candidate = profileService.getOrProvisionMine();
        String key = UUID.randomUUID().toString();
        try {
            storage.store(key, bytes);
            Resume resume = new Resume();
            resume.setCandidate(candidate); resume.setStorageKey(key); resume.setOriginalFilename(filename);
            resume.setFileExtension(extension); resume.setContentType(mediaType(extension));
            resume.setFileSize(bytes.length); resume.setUploadedAt(LocalDateTime.now());
            Resume saved = resumeRepository.save(resume);
            intelligenceService.createFor(saved);
            registerRollbackCleanup(key);
            return ResumeResponse.from(saved);
        } catch (RuntimeException | IOException ex) {
            deleteQuietly(key);
            throw ex instanceof RuntimeException runtime ? runtime : new IllegalStateException("Resume could not be stored");
        }
    }

    public List<ResumeResponse> listMine() {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        return resumeRepository.findAllByCandidateIdOrderByUploadedAtDescIdDesc(candidate.getId()).stream().map(ResumeResponse::from).toList();
    }

    public ResumeResponse getMine(Long id) { return ResumeResponse.from(findOwned(id)); }

    public Download getDownload(Long id) {
        Resume resume = findOwned(id);
        try { return new Download(resume, storage.load(resume.getStorageKey())); }
        catch (NoSuchFileException ex) { throw new ResourceNotFoundException("Resume file not found"); }
        catch (IOException ex) { throw new IllegalStateException("Resume file could not be read"); }
    }

    private Resume findOwned(Long id) {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        return resumeRepository.findByIdAndCandidateId(id, candidate.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Resume not found"));
    }
    private String safeFilename(String supplied) {
        if (supplied == null) return "resume";
        String name = supplied.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isBlank() || name.length() > 255 || name.contains("\r") || name.contains("\n"))
            throw new BadRequestException("Invalid resume filename");
        return name;
    }
    private String mediaType(String extension) {
        return switch (extension) { case "pdf" -> "application/pdf"; case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; default -> "application/msword"; };
    }
    private void validateContent(String extension, byte[] bytes) {
        boolean valid = switch (extension) {
            case "pdf" -> bytes.length >= 8 && new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-") &&
                    new String(bytes, Math.max(0, bytes.length - 2048), Math.min(2048, bytes.length), java.nio.charset.StandardCharsets.ISO_8859_1).contains("%%EOF");
            case "doc" -> bytes.length >= 8 && (bytes[0] & 255) == 0xD0 && (bytes[1] & 255) == 0xCF && (bytes[2] & 255) == 0x11 && (bytes[3] & 255) == 0xE0;
            case "docx" -> validDocx(bytes);
            default -> false;
        };
        if (!valid) throw new BadRequestException("Resume content does not match a valid supported document");
    }
    private boolean validDocx(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') return false;
        byte[] contentTypes = null, document = null;
        long expandedBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                boolean isContentTypes = entry.getName().equals("[Content_Types].xml");
                boolean isDocument = entry.getName().equals("word/document.xml");
                ByteArrayOutputStream xml = isContentTypes || isDocument ? new ByteArrayOutputStream() : null;
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expandedBytes += count;
                    if (expandedBytes > 100L * 1024 * 1024) return false;
                    if (xml != null) {
                        if (xml.size() + count > 1024 * 1024) return false;
                        xml.write(buffer, 0, count);
                    }
                }
                if (isContentTypes) contentTypes = xml.toByteArray();
                if (isDocument) document = xml.toByteArray();
            }
            return validXml(contentTypes) && validXml(document);
        } catch (IOException ex) { return false; }
    }
    private boolean validXml(byte[] contents) {
        if (contents == null || contents.length == 0) return false;
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            factory.newDocumentBuilder().parse(new ByteArrayInputStream(contents));
            return true;
        } catch (Exception ex) { return false; }
    }
    private void registerRollbackCleanup(String key) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { if (status != STATUS_COMMITTED) deleteQuietly(key); }
            });
        }
    }
    private void deleteQuietly(String key) { try { storage.delete(key); } catch (IOException ignored) { } }
    public record Download(Resume resume, byte[] bytes) { }
}
