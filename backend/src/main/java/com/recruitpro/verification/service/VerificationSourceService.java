package com.recruitpro.verification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.service.CandidateProfileService;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.DuplicateResourceException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.service.MatchTriggerService;
import com.recruitpro.verification.client.AgentModels;
import com.recruitpro.verification.client.AgentServiceClient;
import com.recruitpro.verification.client.AgentServiceException;
import com.recruitpro.verification.dto.LastRun;
import com.recruitpro.verification.dto.OwnershipCheckRequest;
import com.recruitpro.verification.dto.SourceCreateRequest;
import com.recruitpro.verification.dto.SourceResponse;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.EvidenceItem;
import com.recruitpro.verification.entity.OwnershipMethod;
import com.recruitpro.verification.entity.OwnershipStatus;
import com.recruitpro.verification.entity.VerificationRun;
import com.recruitpro.verification.entity.VerificationRunStatus;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.ClaimVerdictReviewRepository;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.EvidenceItemRepository;
import com.recruitpro.verification.repository.VerificationCandidateLockRepository;
import com.recruitpro.verification.repository.VerificationRunRepository;
import com.recruitpro.verification.repository.VerificationSourceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The candidate's verification sources (plan v2 section 5): connect with consent, upload a document, prove ownership
 * through the agent service, revoke. Revoking deletes the access token, the uploaded file, every evidence item and
 * snapshot of the source and withdraws its verdicts (principle 6); audited.
 */
@Service
public class VerificationSourceService {

    public static final long MAX_DOCUMENT_BYTES = 15L * 1024 * 1024;
    static final Pattern LOGIN = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,98}[A-Za-z0-9_])?$");
    static final Set<String> DOCUMENT_EXTENSIONS = Set.of("pdf", "docx", "pptx");
    private static final int MAX_CREDENTIAL_CHARS = 100_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CandidateProfileService profileService;
    private final VerificationSourceRepository sourceRepository;
    private final VerificationRunRepository runRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final ClaimVerdictRepository verdictRepository;
    private final ClaimVerdictReviewRepository reviewRepository;
    private final VerificationCandidateLockRepository lockRepository;
    private final VerificationStorage storage;
    private final VerificationResultWriter resultWriter;
    private final AgentServiceClient agentClient;
    private final AuditService auditService;
    private final MatchTriggerService matchTriggerService;
    private final GitHubOAuthSettings gitHubSettings;
    private final PublicEndpointRateLimiter rateLimiter;
    private final TransactionTemplate transactions;
    private final String consentVersion;

    public VerificationSourceService(CandidateProfileService profileService, VerificationSourceRepository sourceRepository,
                                     VerificationRunRepository runRepository, EvidenceItemRepository evidenceRepository,
                                     ClaimVerdictRepository verdictRepository, ClaimVerdictReviewRepository reviewRepository,
                                     VerificationCandidateLockRepository lockRepository, VerificationStorage storage,
                                     VerificationResultWriter resultWriter, AgentServiceClient agentClient,
                                     AuditService auditService, MatchTriggerService matchTriggerService,
                                     GitHubOAuthSettings gitHubSettings, PublicEndpointRateLimiter rateLimiter,
                                     PlatformTransactionManager transactionManager,
                                     @Value("${app.verification.consent-version:2026-11-v1}") String consentVersion) {
        this.profileService = profileService;
        this.sourceRepository = sourceRepository;
        this.runRepository = runRepository;
        this.evidenceRepository = evidenceRepository;
        this.verdictRepository = verdictRepository;
        this.reviewRepository = reviewRepository;
        this.lockRepository = lockRepository;
        this.storage = storage;
        this.resultWriter = resultWriter;
        this.agentClient = agentClient;
        this.auditService = auditService;
        this.matchTriggerService = matchTriggerService;
        this.gitHubSettings = gitHubSettings;
        this.rateLimiter = rateLimiter;
        this.transactions = new TransactionTemplate(transactionManager);
        this.consentVersion = consentVersion;
    }

    public String consentVersion() {
        return consentVersion;
    }

    // ------------------------------------------------------------------------------------------------- read

    @Transactional
    public List<SourceResponse> listMine() {
        CandidateProfile me = profileService.getOrProvisionMine();
        List<SourceResponse> responses = new ArrayList<>();
        for (VerificationSource source : sourceRepository.findAllByCandidateIdAndRevokedAtIsNullOrderByCreatedAtDescIdDesc(me.getId())) {
            responses.add(response(source));
        }
        return responses;
    }

    // ----------------------------------------------------------------------------------------------- create

    /** POST /sources: GITHUB, GITLAB, WEBSITE or OPEN_BADGE with consent; 409 for a duplicate active source. */
    @Transactional
    public SourceResponse create(SourceCreateRequest request) {
        CandidateProfile me = profileService.getOrProvisionMine();
        if (request == null) {
            throw new BadRequestException("A request body is required");
        }
        requireConsent(request.consent());
        VerificationSourceKind kind = parseKind(request.kind());
        if (kind == VerificationSourceKind.DOCUMENT) {
            throw new BadRequestException("Upload documents with POST /api/v1/candidates/me/verification/sources/documents");
        }
        VerificationSource source = new VerificationSource();
        source.setKind(kind);
        switch (kind) {
            case GITHUB, GITLAB -> {
                String login = normaliseLogin(request.identifier());
                source.setIdentifier(login);
                source.setDisplayLabel((kind == VerificationSourceKind.GITHUB ? "GitHub: " : "GitLab: ") + login);
            }
            case WEBSITE -> {
                URI url = parseUrl(request.identifier(), "identifier must be an http(s) URL of your website");
                source.setIdentifier(url.toString());
                source.setDisplayLabel("Website: " + url.getHost());
            }
            case OPEN_BADGE -> badge(source, request.identifier(), request.credential());
            default -> throw new BadRequestException("Unsupported source kind");
        }
        lock(me.getId());
        requireNoDuplicate(me.getId(), kind, source.getIdentifier());
        LocalDateTime now = UtcTime.now();
        source.setCandidateId(me.getId());
        source.setOwnershipStatus(OwnershipStatus.UNPROVEN);
        source.setChallengeCode(newChallengeCode());
        source.setConsentTextVersion(consentVersion);
        source.setConsentedAt(now);
        VerificationSource saved = sourceRepository.save(source);
        auditService.record(VerificationAuditActions.SOURCE_CONNECTED, "VERIFICATION_SOURCE", String.valueOf(saved.getId()),
                "Connected a " + kind + " verification source (consent " + consentVersion + ")");
        return response(saved);
    }

    /** POST /sources/documents: PDF, DOCX or PPTX of at most 15 MB, checked by magic bytes. */
    @Transactional
    public SourceResponse createDocument(MultipartFile file, Boolean consent) {
        CandidateProfile me = profileService.getOrProvisionMine();
        requireConsent(consent);
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("A non-empty file is required");
        }
        if (file.getSize() > MAX_DOCUMENT_BYTES) {
            throw new BadRequestException("Documents are limited to 15 MB");
        }
        String name = safeFileName(file.getOriginalFilename());
        String extension = extension(name);
        if (!DOCUMENT_EXTENSIONS.contains(extension)) {
            throw new BadRequestException("Only PDF, DOCX and PPTX documents are accepted");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new BadRequestException("The file could not be read");
        }
        if (bytes.length == 0 || bytes.length > MAX_DOCUMENT_BYTES) {
            throw new BadRequestException("Documents are limited to 15 MB");
        }
        if (!matchesFormat(extension, bytes)) {
            throw new BadRequestException("The file content does not match its ." + extension + " extension");
        }
        lock(me.getId());
        requireNoDuplicate(me.getId(), VerificationSourceKind.DOCUMENT, name);
        String key;
        try {
            key = storage.store(bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("The document could not be stored");
        }
        storage.deleteOnRollback(List.of(key));
        LocalDateTime now = UtcTime.now();
        VerificationSource source = new VerificationSource();
        source.setCandidateId(me.getId());
        source.setKind(VerificationSourceKind.DOCUMENT);
        source.setIdentifier(name);
        source.setDisplayLabel("Document: " + name);
        source.setOwnershipStatus(OwnershipStatus.UNPROVEN);
        source.setChallengeCode(newChallengeCode());
        source.setFilePath(key);
        source.setConsentTextVersion(consentVersion);
        source.setConsentedAt(now);
        VerificationSource saved = sourceRepository.save(source);
        auditService.record(VerificationAuditActions.SOURCE_CONNECTED, "VERIFICATION_SOURCE", String.valueOf(saved.getId()),
                "Uploaded a verification document (" + extension + ", " + bytes.length + " bytes)");
        return response(saved);
    }

    // ---------------------------------------------------------------------------------------- ownership check

    private record OwnershipTarget(Long sourceId, Long candidateId, OwnershipMethod method, String target, String code) {
    }

    /**
     * POST /sources/{id}/ownership-check. Calls the agent outside any transaction. A failed check of a source that
     * is already PROVEN keeps it PROVEN (the code may have been removed after a successful check).
     */
    public SourceResponse checkOwnership(Long sourceId, OwnershipCheckRequest request) {
        CandidateProfile me = profileService.getOrProvisionMine();
        OwnershipTarget target = transactions.execute(status -> prepareOwnershipCheck(me.getId(), sourceId, request));
        rateLimiter.check(PublicEndpointRateLimiter.OWNERSHIP_CHECK, "candidate:" + me.getId());
        AgentModels.OwnershipCheckResponse result;
        try {
            result = agentClient.checkOwnership(new AgentModels.OwnershipCheckRequest(target.method().name(),
                    target.target(), target.code()));
        } catch (AgentServiceException ex) {
            if (ex.isTransient() || ex.code() == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The verification service is unavailable");
            }
            throw new BadRequestException("The ownership check could not run (" + ex.code() + ")");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The verification service is unavailable");
        } catch (IllegalStateException ex) {
            if (AgentServiceClient.NOT_CONFIGURED_MESSAGE.equals(ex.getMessage())) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The verification service is not configured");
            }
            throw ex;
        }
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The verification service is unavailable");
        }
        AgentModels.OwnershipCheckResponse outcome = result;
        return transactions.execute(status -> applyOwnershipResult(target, outcome));
    }

    private OwnershipTarget prepareOwnershipCheck(Long candidateId, Long sourceId, OwnershipCheckRequest request) {
        VerificationSource source = activeSource(candidateId, sourceId);
        if (request == null || request.method() == null || request.method().isBlank()) {
            throw new BadRequestException("method is required");
        }
        OwnershipMethod method;
        try {
            method = OwnershipMethod.valueOf(request.method().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown ownership method");
        }
        String target = switch (source.getKind()) {
            case GITHUB, GITLAB -> {
                if (method != OwnershipMethod.PROFILE_BIO_CODE) {
                    throw new BadRequestException("Code-host accounts are proven with PROFILE_BIO_CODE (or OAuth for GitHub)");
                }
                yield (source.getKind() == VerificationSourceKind.GITHUB ? "github:" : "gitlab:") + source.getIdentifier();
            }
            case WEBSITE -> {
                if (method == OwnershipMethod.DNS_TXT || method == OwnershipMethod.WELL_KNOWN_FILE) {
                    yield URI.create(source.getIdentifier()).getHost();
                }
                if (method == OwnershipMethod.PAGE_CODE) {
                    yield source.getIdentifier();
                }
                throw new BadRequestException("Websites are proven with DNS_TXT, WELL_KNOWN_FILE or PAGE_CODE");
            }
            case DOCUMENT -> throw new BadRequestException("Ownership of an uploaded document cannot be proven");
            case OPEN_BADGE -> throw new BadRequestException("Badge ownership is proven by the issuer's signature during a run");
        };
        return new OwnershipTarget(source.getId(), candidateId, method, target, source.getChallengeCode());
    }

    private SourceResponse applyOwnershipResult(OwnershipTarget target, AgentModels.OwnershipCheckResponse result) {
        VerificationSource source = activeSource(target.candidateId(), target.sourceId());
        LocalDateTime now = UtcTime.now();
        source.setOwnershipCheckedAt(now);
        source.setOwnershipDetail(VerificationResultWriter.limit(result.detail(), 500));
        if (result.proven()) {
            boolean newlyProven = !source.isOwnershipProven();
            source.setOwnershipStatus(OwnershipStatus.PROVEN);
            source.setOwnershipMethod(target.method());
            resultWriter.storeOwnershipProof(result.evidence(), source.getCandidateId(), source.getId());
            if (newlyProven) {
                auditService.record(VerificationAuditActions.OWNERSHIP_PROVEN, "VERIFICATION_SOURCE",
                        String.valueOf(source.getId()), "Ownership of a " + source.getKind() + " source proven by " + target.method());
            }
        } else if (!source.isOwnershipProven()) {
            source.setOwnershipStatus(OwnershipStatus.FAILED);
        }
        return response(source);
    }

    // ---------------------------------------------------------------------------------------------- revoke

    /**
     * DELETE /sources/{id}: token, file, evidence and snapshots deleted, verdicts WITHDRAWN, unfinished runs FAILED.
     * Files are removed after the commit. Re-matches the candidate when a withdrawn verdict had been shared.
     */
    @Transactional
    public void revoke(Long sourceId) {
        CandidateProfile me = profileService.getOrProvisionMine();
        VerificationSource source = activeSource(me.getId(), sourceId);
        List<String> files = new ArrayList<>();
        if (source.getFilePath() != null) {
            files.add(source.getFilePath());
        }
        List<EvidenceItem> evidence = evidenceRepository.findAllBySourceId(source.getId());
        for (EvidenceItem item : evidence) {
            files.add(item.getSnapshotPath());
        }
        LocalDateTime now = UtcTime.now();
        boolean wasShared = false;
        List<Long> verdictIds = new ArrayList<>();
        for (ClaimVerdict verdict : verdictRepository.findAllBySourceId(source.getId())) {
            verdictIds.add(verdict.getId());
            if (verdict.isCurrent() && verdict.getCandidateStatus() == CandidateVerdictStatus.SHARED) {
                wasShared = true;
            }
            if (verdict.isCurrent()) {
                verdict.setCandidateStatus(CandidateVerdictStatus.WITHDRAWN);
                verdict.setCurrent(false);
            }
            verdict.setEvidenceItemIdsJson("[]");
        }
        for (VerificationRun run : runRepository.findAllBySourceIdAndStatusIn(source.getId(),
                EnumSet.of(VerificationRunStatus.PENDING, VerificationRunStatus.RUNNING))) {
            run.setStatus(VerificationRunStatus.FAILED);
            run.setErrorCode(VerificationResultWriter.SOURCE_REVOKED);
            run.setFinishedAt(now);
        }
        source.setRevokedAt(now);
        source.setAccessTokenEnc(null);
        source.setTokenScopes(null);
        source.setFilePath(null);
        source.setCredentialText(null);
        sourceRepository.saveAndFlush(source);
        verdictRepository.flush();
        int deleted = evidenceRepository.deleteAllBySource(source.getId());
        storage.deleteAfterCommit(files);
        auditService.record(VerificationAuditActions.SOURCE_REVOKED, "VERIFICATION_SOURCE", String.valueOf(source.getId()),
                "Revoked a " + source.getKind() + " source: " + deleted + " evidence item(s) deleted, "
                        + verdictIds.size() + " verdict(s) withdrawn");
        if (wasShared || (!verdictIds.isEmpty() && reviewRepository.existsByVerdictIdIn(verdictIds))) {
            matchTriggerService.requestMatchForCandidate(me.getId());
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    /** The candidate's non-revoked source; 404 otherwise (another candidate's source behaves as missing). */
    VerificationSource activeSource(Long candidateId, Long sourceId) {
        VerificationSource source = sourceId == null ? null : sourceRepository.findByIdAndCandidateId(sourceId, candidateId).orElse(null);
        if (source == null || source.isRevoked()) {
            throw new ResourceNotFoundException("Verification source not found");
        }
        return source;
    }

    void lock(Long candidateId) {
        lockRepository.lockById(candidateId).orElseThrow(() -> new ResourceNotFoundException("Candidate profile not found"));
    }

    void requireNoDuplicate(Long candidateId, VerificationSourceKind kind, String identifier) {
        for (VerificationSource existing : sourceRepository.findAllByCandidateIdAndKindAndRevokedAtIsNull(candidateId, kind)) {
            if (existing.getIdentifier().equalsIgnoreCase(identifier)) {
                throw new DuplicateResourceException("This source is already connected");
            }
        }
    }

    public SourceResponse response(VerificationSource source) {
        VerificationRun last = runRepository.findFirstBySourceIdOrderByIdDesc(source.getId()).orElse(null);
        LastRun lastRun = last == null ? null : new LastRun(last.getId(), last.getStatus().name(), last.getFinishedAt(),
                last.getErrorCode());
        return new SourceResponse(source.getId(), source.getKind().name(), source.getIdentifier(), source.getDisplayLabel(),
                source.getOwnershipStatus().name(), source.getOwnershipMethod() == null ? null : source.getOwnershipMethod().name(),
                source.getChallengeCode(), ownershipInstructions(source, gitHubSettings.isConfigured()), source.getConsentedAt(),
                lastRun, source.getOwnershipCheckedAt(), source.getOwnershipDetail(), source.getCreatedAt());
    }

    /** Human-readable steps to prove ownership; empty once proven. */
    static List<String> ownershipInstructions(VerificationSource source, boolean gitHubOAuth) {
        if (source.isOwnershipProven()) {
            return List.of();
        }
        String code = source.getChallengeCode();
        return switch (source.getKind()) {
            case GITHUB -> {
                List<String> steps = new ArrayList<>();
                if (gitHubOAuth) {
                    steps.add("Fastest: choose \"Connect with GitHub\" and approve read-only access (scope read:user).");
                }
                steps.add("Or add the code " + code + " anywhere in your GitHub profile bio (github.com/settings/profile), then choose \"Check now\" with method PROFILE_BIO_CODE.");
                steps.add("You can remove the code once the check has succeeded.");
                yield steps;
            }
            case GITLAB -> List.of(
                    "Add the code " + code + " anywhere in your GitLab profile bio (gitlab.com/-/user_settings/profile), then choose \"Check now\" with method PROFILE_BIO_CODE.",
                    "You can remove the code once the check has succeeded.");
            case WEBSITE -> {
                String host = hostOf(source.getIdentifier());
                yield List.of(
                        "Option 1 (DNS_TXT): add a DNS TXT record on " + host + " with the value recruitpro-verification=" + code + ".",
                        "Option 2 (WELL_KNOWN_FILE): publish https://" + host + "/.well-known/recruitpro-verification.txt containing " + code + ".",
                        "Option 3 (PAGE_CODE): add " + code + " to the page " + source.getIdentifier() + " (for example in a <meta name=\"recruitpro-verification\"> tag).",
                        "Then choose \"Check now\" with the method you used.");
            }
            case DOCUMENT -> List.of(
                    "Ownership of an uploaded document cannot be proven, so its results are at most PARTLY_VERIFIED.");
            case OPEN_BADGE -> List.of(
                    "No action needed: ownership is proven during the check when the badge is issued to your account e-mail or name and its signature is valid.");
        };
    }

    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (IllegalArgumentException ex) {
            return url;
        }
    }

    static void requireConsent(Boolean consent) {
        if (!Boolean.TRUE.equals(consent)) {
            throw new BadRequestException("Consent is required to connect a verification source");
        }
    }

    static VerificationSourceKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new BadRequestException("kind is required (GITHUB, GITLAB, WEBSITE or OPEN_BADGE)");
        }
        try {
            return VerificationSourceKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("kind must be GITHUB, GITLAB, WEBSITE or OPEN_BADGE");
        }
    }

    /** A login, an @login or a profile URL ({@code https://github.com/login}); lower-cased. */
    static String normaliseLogin(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new BadRequestException("identifier (your username) is required");
        }
        String value = identifier.trim();
        if (value.contains("/")) {
            String[] parts = value.replaceAll("/+$", "").split("/");
            value = parts[parts.length - 1];
        }
        if (value.startsWith("@")) {
            value = value.substring(1);
        }
        if (!LOGIN.matcher(value).matches()) {
            throw new BadRequestException("identifier is not a valid username");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /** An absolute http(s) URL without user info, at most 500 characters; scheme and host lower-cased. */
    static URI parseUrl(String value, String message) {
        if (value == null || value.isBlank() || value.trim().length() > 500) {
            throw new BadRequestException(message);
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null) {
                throw new BadRequestException(message);
            }
            return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), uri.getPath(),
                    uri.getQuery(), null);
        } catch (URISyntaxException ex) {
            throw new BadRequestException(message);
        }
    }

    private void badge(VerificationSource source, String identifier, JsonNode credential) {
        if (credential != null && !credential.isNull()) {
            String text;
            if (credential.isTextual()) {
                text = credential.asText().trim();
            } else if (credential.isObject()) {
                text = credential.toString();
            } else {
                throw new BadRequestException("credential must be a JWT string or a JSON object");
            }
            if (text.isEmpty() || text.length() > MAX_CREDENTIAL_CHARS) {
                throw new BadRequestException("credential must be 1 to " + MAX_CREDENTIAL_CHARS + " characters");
            }
            String hash = VerificationResultWriter.sha256(text).substring("sha256:".length(), "sha256:".length() + 32);
            source.setIdentifier("credential:" + hash);
            source.setDisplayLabel("Open Badge (pasted credential)");
            source.setCredentialText(text);
        } else {
            URI url = parseUrl(identifier, "identifier must be the http(s) URL of the badge assertion, or paste the credential");
            source.setIdentifier(url.toString());
            source.setDisplayLabel("Open Badge (" + url.getHost() + ")");
        }
        source.setOwnershipMethod(OwnershipMethod.ISSUER_SIGNATURE);
    }

    static String newChallengeCode() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return "rp-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String safeFileName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|:*?]", "_").trim();
        if (name.isEmpty()) {
            name = "document";
        }
        if (name.length() > 200) {
            String extension = extension(name);
            name = name.substring(0, 190) + (extension.isEmpty() ? "" : "." + extension);
        }
        return name;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Magic-byte check: {@code %PDF-} for PDF; a ZIP with {@code word/} or {@code ppt/} entries for DOCX/PPTX. */
    static boolean matchesFormat(String extension, byte[] bytes) {
        if ("pdf".equals(extension)) {
            return startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII));
        }
        if (!startsWith(bytes, new byte[]{0x50, 0x4B, 0x03, 0x04})) {
            return false;
        }
        String folder = "docx".equals(extension) ? "word/" : "ppt/";
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            int seen = 0;
            while ((entry = zip.getNextEntry()) != null && seen++ < 2000) {
                if (entry.getName().startsWith(folder)) {
                    return true;
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            return false;
        }
        return false;
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
}
