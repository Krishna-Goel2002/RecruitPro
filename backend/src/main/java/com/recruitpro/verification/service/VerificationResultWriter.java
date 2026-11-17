package com.recruitpro.verification.service;

import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.common.web.PortalUrls;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.service.MatchTriggerService;
import com.recruitpro.notification.entity.NotificationTemplate;
import com.recruitpro.notification.service.NotificationRecipient;
import com.recruitpro.notification.service.NotificationService;
import com.recruitpro.user.entity.User;
import com.recruitpro.verification.client.AgentModels;
import com.recruitpro.verification.dto.IntegrityNoteView;
import com.recruitpro.verification.dto.ReferenceClaim;
import com.recruitpro.verification.dto.VerdictReason;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimSubjectRef;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.EvidenceItem;
import com.recruitpro.verification.entity.EvidenceItemKind;
import com.recruitpro.verification.entity.OwnershipMethod;
import com.recruitpro.verification.entity.OwnershipStatus;
import com.recruitpro.verification.entity.ReferenceStatus;
import com.recruitpro.verification.entity.SubjectType;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationReferenceRequest;
import com.recruitpro.verification.entity.VerificationRun;
import com.recruitpro.verification.entity.VerificationRunStatus;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.EvidenceItemRepository;
import com.recruitpro.verification.repository.VerificationCandidateLockRepository;
import com.recruitpro.verification.repository.VerificationReferenceRequestRepository;
import com.recruitpro.verification.repository.VerificationRunRepository;
import com.recruitpro.verification.repository.VerificationSourceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stores the outcome of an agent verification (principles 1, 2, 5): the backend, not the agent, decides what is
 * recorded.
 *
 * <ul>
 *   <li>Every evidence item's {@code contentHash} must equal {@code sha256:} + hex SHA-256 of its UTF-8
 *       {@code snapshotText}; a single mismatch (or an unknown kind, a missing or duplicate key) rejects the whole
 *       response and the run fails with {@code EVIDENCE_HASH_MISMATCH} / {@code INVALID_EVIDENCE}. Nothing is
 *       stored in that case.</li>
 *   <li>Snapshots are written to {@link VerificationStorage} (deleted again if the transaction rolls back).</li>
 *   <li>Verdicts are appended: the current verdict for the same subject and source (or reference) becomes
 *       SUPERSEDED; the new one is AWAITING_CANDIDATE. Only verdicts for subjects that were sent (and still exist)
 *       are kept; claim type and value come from the backend's subject, not the agent's echo.</li>
 *   <li>Ownership cap, enforced here as well as in the agent: a verdict whose ownership is not proven is at most
 *       PARTLY_VERIFIED. Code and website sources need {@code ownership_status = PROVEN}; documents never prove
 *       ownership; badges prove it when the recipient matched (the source then becomes PROVEN by
 *       ISSUER_SIGNATURE); references use the agent's independent-referee rule.</li>
 * </ul>
 */
@Service
public class VerificationResultWriter {

    public static final String HASH_MISMATCH = "EVIDENCE_HASH_MISMATCH";
    public static final String INVALID_EVIDENCE = "INVALID_EVIDENCE";
    public static final String EMPTY_RESPONSE = "EMPTY_RESPONSE";
    public static final String SOURCE_REVOKED = "SOURCE_REVOKED";
    static final int MAX_SNAPSHOT_CHARS = 262_144;
    static final int MAX_DOMAIN_TRUST_CHARS = 20_000;

    private final VerificationRunRepository runRepository;
    private final VerificationSourceRepository sourceRepository;
    private final VerificationReferenceRequestRepository referenceRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final ClaimVerdictRepository verdictRepository;
    private final CandidateProfileRepository profileRepository;
    private final VerificationSubjects subjects;
    private final VerificationStorage storage;
    private final VerificationViews views;
    private final NotificationService notificationService;
    private final MatchTriggerService matchTriggerService;
    private final VerificationCandidateLockRepository lockRepository;
    private final String candidateUrl;

    public VerificationResultWriter(VerificationRunRepository runRepository, VerificationSourceRepository sourceRepository,
                                    VerificationReferenceRequestRepository referenceRepository,
                                    EvidenceItemRepository evidenceRepository, ClaimVerdictRepository verdictRepository,
                                    CandidateProfileRepository profileRepository, VerificationSubjects subjects,
                                    VerificationStorage storage, VerificationViews views,
                                    NotificationService notificationService, MatchTriggerService matchTriggerService,
                                    VerificationCandidateLockRepository lockRepository,
                                    @Value("${app.frontend.candidate-url:http://localhost:5173}") String candidateUrl) {
        this.runRepository = runRepository;
        this.sourceRepository = sourceRepository;
        this.referenceRepository = referenceRepository;
        this.evidenceRepository = evidenceRepository;
        this.verdictRepository = verdictRepository;
        this.profileRepository = profileRepository;
        this.subjects = subjects;
        this.storage = storage;
        this.views = views;
        this.notificationService = notificationService;
        this.matchTriggerService = matchTriggerService;
        this.lockRepository = lockRepository;
        this.candidateUrl = trimSlash(candidateUrl);
    }

    /** Outcome of storing a result: COMPLETED with the number of verdicts, or the run's error code. */
    public record Outcome(boolean completed, int verdicts, String errorCode) {
    }

    // ------------------------------------------------------------------------------------------- source runs

    @Transactional
    public Outcome storeRunResult(long runId, AgentModels.VerifyResponse response) {
        Long candidateId = runRepository.findCandidateIdById(runId).orElse(null);
        if (candidateId == null) {
            return new Outcome(false, 0, "RUN_NOT_ACTIVE");
        }
        // R3-B01: serialised with revoke (which takes the same lock), so a source revoked while the agent was working
        // is seen as revoked here and nothing is stored for it; read the run and source only after the lock.
        lockRepository.lockById(candidateId);
        VerificationRun run = runRepository.findById(runId).orElse(null);
        if (run == null || !run.isActive()) {
            return new Outcome(false, 0, "RUN_NOT_ACTIVE");
        }
        VerificationSource source = sourceRepository.findById(run.getSourceId()).orElse(null);
        if (source == null || source.isRevoked()) {
            return fail(run, SOURCE_REVOKED);
        }
        if (response == null) {
            return fail(run, EMPTY_RESPONSE);
        }
        String invalid = validateEvidence(response.evidence());
        if (invalid != null) {
            return fail(run, invalid);
        }
        Map<String, Long> evidenceIds = storeEvidence(response.evidence(), run.getCandidateId(), run.getId(),
                source.getId(), null);
        Map<String, VerificationSubjects.Subject> current = subjects.currentSubjectsByRef(run.getCandidateId());
        String verifier = limit(nonBlank(response.verifier(), "unknown"), 40);
        String verifierVersion = limit(response.verifierVersion(), 40);

        boolean badgeOwnership = false;
        boolean rematch = false;
        int stored = 0;
        Set<String> seen = new HashSet<>();
        boolean codeHost = source.getKind() == VerificationSourceKind.GITHUB || source.getKind() == VerificationSourceKind.GITLAB;
        for (AgentModels.Verdict draft : response.verdicts() == null ? List.<AgentModels.Verdict>of() : response.verdicts()) {
            // R3-B15: one verdict per subject (ids compared trimmed, as they are looked up).
            if (draft == null || draft.claimId() == null || !seen.add(draft.claimId().trim())) {
                continue;
            }
            VerificationSubjects.Subject subject = current.get(draft.claimId().trim());
            VerdictValue value = VerdictValue.parse(draft.verdict());
            if (subject == null || value == null) {
                continue;
            }
            // Code hosts are only sent SKILL subjects (VerificationRunService.claim): anything else was never asked.
            if (codeHost && subject.claimType() != ClaimType.SKILL) {
                continue;
            }
            boolean ownershipProven = switch (source.getKind()) {
                case DOCUMENT -> false;
                case OPEN_BADGE -> draft.ownershipProven();
                case GITHUB, GITLAB, WEBSITE -> draft.ownershipProven() && source.isOwnershipProven();
            };
            if (source.getKind() == VerificationSourceKind.OPEN_BADGE && draft.ownershipProven()) {
                badgeOwnership = true;
            }
            for (ClaimVerdict previous : verdictRepository.findAllByCandidateIdAndSubjectTypeAndSubjectIdAndSourceIdAndCurrentTrue(
                    run.getCandidateId(), subject.subjectType(), subject.subjectId(), source.getId())) {
                rematch |= supersede(previous);
            }
            ClaimVerdict verdict = newVerdict(run.getCandidateId(), subject.subjectType(), subject.subjectId(),
                    subject.claimType(), subject.value(), verifier, verifierVersion, value.capped(ownershipProven),
                    ownershipProven, draft, evidenceIds);
            verdict.setSourceId(source.getId());
            verdict.setRunId(run.getId());
            verdictRepository.save(verdict);
            stored++;
        }
        if (badgeOwnership && !source.isOwnershipProven()) {
            source.setOwnershipStatus(OwnershipStatus.PROVEN);
            source.setOwnershipMethod(OwnershipMethod.ISSUER_SIGNATURE);
            source.setOwnershipCheckedAt(UtcTime.now());
            source.setOwnershipDetail("The badge recipient matches the candidate");
        }
        LocalDateTime now = UtcTime.now();
        run.setStatus(VerificationRunStatus.COMPLETED);
        run.setFinishedAt(now);
        if (run.getStartedAt() == null) {
            run.setStartedAt(now);
        }
        run.setErrorCode(null);
        run.setVerifier(verifier);
        run.setVerifierVersion(verifierVersion);
        run.setUsageJson(response.usage() == null ? null : views.write(response.usage()));
        run.setSummaryJson(views.write(reasons(response.summary())));
        run.setWarningsJson(response.warnings() == null ? null : views.write(response.warnings()));
        if (rematch) {
            matchTriggerService.requestMatchForCandidate(run.getCandidateId());
        }
        notifyCandidate(run.getCandidateId(), NotificationTemplate.VERIFICATION_READY, Map.of(
                "sourceLabel", source.getDisplayLabel(),
                "count", String.valueOf(stored),
                "link", candidateUrl + "/verification"));
        return new Outcome(true, stored, null);
    }

    /** Marks an unfinished run FAILED with an error code (no retry). */
    @Transactional
    public Outcome failRun(long runId, String errorCode) {
        VerificationRun run = runRepository.findById(runId).orElse(null);
        if (run == null || !run.isActive()) {
            return new Outcome(false, 0, "RUN_NOT_ACTIVE");
        }
        return fail(run, errorCode);
    }

    private Outcome fail(VerificationRun run, String errorCode) {
        LocalDateTime now = UtcTime.now();
        run.setStatus(VerificationRunStatus.FAILED);
        run.setErrorCode(limit(errorCode, 60));
        run.setFinishedAt(now);
        if (run.getStartedAt() == null) {
            run.setStartedAt(now);
        }
        return new Outcome(false, 0, run.getErrorCode());
    }

    // -------------------------------------------------------------------------------------------- references

    /**
     * Stores the verdicts of an answered reference. Claim subjects come from the request's stored claim
     * references (not the current ledger), so a later resume change does not lose the answer.
     */
    @Transactional
    public Outcome storeReferenceResult(long referenceId, AgentModels.VerifyResponse response) {
        VerificationReferenceRequest reference = referenceRepository.findById(referenceId).orElse(null);
        if (reference == null || reference.getStatus() != ReferenceStatus.ANSWERED || reference.getVerifiedAt() != null) {
            return new Outcome(false, 0, "REFERENCE_NOT_PENDING");
        }
        if (response == null) {
            reference.setErrorCode(EMPTY_RESPONSE);
            return new Outcome(false, 0, EMPTY_RESPONSE);
        }
        String invalid = validateEvidence(response.evidence());
        if (invalid != null) {
            reference.setErrorCode(invalid);
            return new Outcome(false, 0, invalid);
        }
        Map<String, Long> evidenceIds = storeEvidence(response.evidence(), reference.getCandidateId(), null, null,
                reference.getId());
        Map<String, ReferenceClaim> asked = new HashMap<>();
        for (ReferenceClaim claim : views.referenceClaims(reference.getClaimRefsJson())) {
            SubjectType type = parseSubjectType(claim.subjectType());
            if (type != null && claim.subjectId() != null) {
                asked.put(ClaimSubjectRef.of(type, claim.subjectId()), claim);
            }
        }
        String verifier = limit(nonBlank(response.verifier(), "reference"), 40);
        String verifierVersion = limit(response.verifierVersion(), 40);
        boolean rematch = false;
        int stored = 0;
        Set<String> seen = new HashSet<>();
        for (AgentModels.Verdict draft : response.verdicts() == null ? List.<AgentModels.Verdict>of() : response.verdicts()) {
            if (draft == null || draft.claimId() == null || !seen.add(draft.claimId().trim())) {
                continue;
            }
            ReferenceClaim claim = asked.get(draft.claimId().trim());
            VerdictValue value = VerdictValue.parse(draft.verdict());
            ClaimSubjectRef ref = ClaimSubjectRef.parse(draft.claimId());
            ClaimType claimType = claim == null ? null : parseClaimType(claim.claimType());
            if (claim == null || value == null || ref == null || claimType == null || claim.value() == null) {
                continue;
            }
            for (ClaimVerdict previous : verdictRepository.findAllByCandidateIdAndSubjectTypeAndSubjectIdAndReferenceIdAndCurrentTrue(
                    reference.getCandidateId(), ref.type(), ref.id(), reference.getId())) {
                rematch |= supersede(previous);
            }
            ClaimVerdict verdict = newVerdict(reference.getCandidateId(), ref.type(), ref.id(), claimType,
                    limit(claim.value(), 1000), verifier, verifierVersion, value.capped(draft.ownershipProven()),
                    draft.ownershipProven(), draft, evidenceIds);
            verdict.setReferenceId(reference.getId());
            verdictRepository.save(verdict);
            stored++;
        }
        reference.setVerifiedAt(UtcTime.now());
        reference.setErrorCode(null);
        if (rematch) {
            matchTriggerService.requestMatchForCandidate(reference.getCandidateId());
        }
        notifyCandidate(reference.getCandidateId(), NotificationTemplate.REFERENCE_ANSWERED, Map.of(
                "refereeName", reference.getRefereeName(),
                "message", "Their answers were checked: " + stored + " result(s) are ready for you to review before "
                        + "anything is shared.",
                "link", candidateUrl + "/verification"));
        return new Outcome(true, stored, null);
    }

    /** Records a reference failure (no retry). */
    @Transactional
    public void failReference(long referenceId, String errorCode) {
        referenceRepository.findById(referenceId).ifPresent(reference -> {
            if (reference.getVerifiedAt() == null) {
                reference.setErrorCode(limit(errorCode, 60));
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ shared

    /** Null when every item is acceptable, otherwise the error code that rejects the whole response. */
    public static String validateEvidence(List<AgentModels.EvidenceItem> items) {
        if (items == null) {
            return null;
        }
        Set<String> keys = new HashSet<>();
        for (AgentModels.EvidenceItem item : items) {
            if (item == null || item.key() == null || item.key().isBlank() || item.key().length() > 40
                    || !keys.add(item.key()) || parseKind(item.kind()) == null || item.retrievedAt() == null) {
                return INVALID_EVIDENCE;
            }
            String snapshot = item.snapshotText() == null ? "" : item.snapshotText();
            if (snapshot.length() > MAX_SNAPSHOT_CHARS) {
                return INVALID_EVIDENCE;
            }
            if (!sha256(snapshot).equals(item.contentHash() == null ? null : item.contentHash().trim().toLowerCase(Locale.ROOT))) {
                return HASH_MISMATCH;
            }
        }
        return null;
    }

    /** {@code sha256:} + lowercase hex of the UTF-8 bytes. */
    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** Writes snapshots and evidence rows; returns item key -> evidence id. */
    private Map<String, Long> storeEvidence(List<AgentModels.EvidenceItem> items, Long candidateId, Long runId,
                                            Long sourceId, Long referenceId) {
        Map<String, Long> ids = new HashMap<>();
        if (items == null || items.isEmpty()) {
            return ids;
        }
        List<String> written = new ArrayList<>();
        try {
            for (AgentModels.EvidenceItem item : items) {
                String snapshot = item.snapshotText() == null ? "" : item.snapshotText();
                String key = storage.store(snapshot.getBytes(StandardCharsets.UTF_8));
                written.add(key);
                EvidenceItem entity = new EvidenceItem();
                entity.setRunId(runId);
                entity.setSourceId(sourceId);
                entity.setReferenceId(referenceId);
                entity.setCandidateId(candidateId);
                entity.setItemKey(item.key());
                entity.setKind(parseKind(item.kind()));
                entity.setUrl(limit(item.url(), 1000));
                entity.setTitle(limit(item.title(), 300));
                entity.setRetrievedAt(parseInstant(item.retrievedAt()));
                entity.setContentHash(item.contentHash().trim().toLowerCase(Locale.ROOT));
                entity.setSnapshotPath(key);
                entity.setExcerpt(limit(item.excerpt(), 1000));
                entity.setAttributesJson(item.attributes() == null || item.attributes().isNull() ? null : views.write(item.attributes()));
                ids.put(item.key(), evidenceRepository.save(entity).getId());
            }
        } catch (IOException ex) {
            storage.deleteQuietly(written);
            throw new UncheckedIOException("Evidence snapshot could not be stored", ex);
        } catch (RuntimeException ex) {
            // R3-B10: a failing insert must not leave the snapshots already written behind (the rollback hook below
            // is only registered once every item is stored).
            storage.deleteQuietly(written);
            throw ex;
        }
        storage.deleteOnRollback(written);
        return ids;
    }

    /** Stores an ownership proof returned by POST /ownership/check (no run). */
    public Long storeOwnershipProof(AgentModels.EvidenceItem item, Long candidateId, Long sourceId) {
        if (item == null || validateEvidence(List.of(item)) != null) {
            return null;
        }
        if (parseKind(item.kind()) != EvidenceItemKind.OWNERSHIP_PROOF) {
            return null;
        }
        return storeEvidence(List.of(item), candidateId, null, sourceId, null).get(item.key());
    }

    private ClaimVerdict newVerdict(Long candidateId, SubjectType subjectType, Long subjectId, ClaimType claimType,
                                    String claimValue, String verifier, String verifierVersion, VerdictValue value,
                                    boolean ownershipProven, AgentModels.Verdict draft, Map<String, Long> evidenceIds) {
        List<VerdictReason> reasons = reasons(draft.reasons());
        List<IntegrityNoteView> notes = notes(draft.integrityNotes());
        Set<Long> cited = new LinkedHashSet<>();
        for (VerdictReason reason : reasons) {
            for (String key : reason.evidenceKeys()) {
                Long id = evidenceIds.get(key);
                if (id != null) {
                    cited.add(id);
                }
            }
        }
        for (IntegrityNoteView note : notes) {
            for (String key : note.evidenceKeys()) {
                Long id = evidenceIds.get(key);
                if (id != null) {
                    cited.add(id);
                }
            }
        }
        ClaimVerdict verdict = new ClaimVerdict();
        verdict.setCandidateId(candidateId);
        verdict.setSubjectType(subjectType);
        verdict.setSubjectId(subjectId);
        verdict.setClaimType(claimType);
        verdict.setClaimValue(claimValue);
        verdict.setVerifier(verifier);
        verdict.setVerifierVersion(verifierVersion);
        verdict.setVerdict(value);
        verdict.setOwnershipProven(ownershipProven);
        verdict.setReasonsJson(views.write(reasons));
        verdict.setIntegrityNotesJson(views.write(notes));
        verdict.setEvidenceItemIdsJson(views.write(new ArrayList<>(cited)));
        verdict.setProbeQuestion(limit(draft.probeQuestion(), 500));
        verdict.setDomainTrustJson(domainTrust(draft.domainTrust()));
        verdict.setCandidateStatus(CandidateVerdictStatus.AWAITING_CANDIDATE);
        verdict.setCurrent(true);
        return verdict;
    }

    /** The agent's domain-trust object as stored JSON (V26); null when absent, not an object or oversized. */
    String domainTrust(com.fasterxml.jackson.databind.JsonNode trust) {
        if (trust == null || !trust.isObject()) {
            return null;
        }
        String json = views.write(trust);
        return json == null || json.length() > MAX_DOMAIN_TRUST_CHARS ? null : json;
    }

    /** Supersedes a current verdict; true when it was shared (matching may have used it). */
    private static boolean supersede(ClaimVerdict previous) {
        boolean wasShared = previous.getCandidateStatus() == CandidateVerdictStatus.SHARED;
        previous.setCurrent(false);
        previous.setCandidateStatus(CandidateVerdictStatus.SUPERSEDED);
        return wasShared;
    }

    static List<VerdictReason> reasons(List<AgentModels.Reason> reasons) {
        List<VerdictReason> out = new ArrayList<>();
        if (reasons == null) {
            return out;
        }
        for (AgentModels.Reason reason : reasons) {
            if (reason == null || reason.text() == null || reason.text().isBlank()) {
                continue;
            }
            out.add(new VerdictReason(limit(reason.text(), 2000), keys(reason.evidenceKeys()), limit(reason.quote(), 2000)));
        }
        return out;
    }

    static List<IntegrityNoteView> notes(List<AgentModels.IntegrityNote> notes) {
        List<IntegrityNoteView> out = new ArrayList<>();
        if (notes == null) {
            return out;
        }
        for (AgentModels.IntegrityNote note : notes) {
            if (note == null || note.code() == null) {
                continue;
            }
            out.add(new IntegrityNoteView(limit(note.code(), 60), limit(note.text(), 2000), keys(note.evidenceKeys())));
        }
        return out;
    }

    private static List<String> keys(List<String> keys) {
        List<String> out = new ArrayList<>();
        if (keys != null) {
            for (String key : keys) {
                if (key != null && !key.isBlank() && key.length() <= 40) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    private void notifyCandidate(Long candidateId, NotificationTemplate template, Map<String, String> extra) {
        CandidateProfile profile = profileRepository.findById(candidateId).orElse(null);
        if (profile == null || profile.getUser() == null) {
            return;
        }
        User user = profile.getUser();
        if (user.getDeletedAt() != null || user.getEmail() == null) {
            return;
        }
        Map<String, String> params = new HashMap<>(extra);
        params.put("firstName", user.getFirstName());
        notificationService.notify(NotificationRecipient.of(user), template, params);
    }

    static EvidenceItemKind parseKind(String kind) {
        if (kind == null) {
            return null;
        }
        try {
            return EvidenceItemKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static SubjectType parseSubjectType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return SubjectType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static ClaimType parseClaimType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ClaimType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** ISO-8601 instant (agent: {@code ...Z}) as UTC; now when unreadable. */
    static LocalDateTime parseInstant(String value) {
        try {
            return UtcTime.parse(value);
        } catch (RuntimeException ex) {
            return UtcTime.now();
        }
    }

    static String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String trimSlash(String url) {
        return PortalUrls.base(url);
    }
}
