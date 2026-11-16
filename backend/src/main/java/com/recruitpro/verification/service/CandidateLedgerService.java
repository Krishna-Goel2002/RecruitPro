package com.recruitpro.verification.service;

import com.recruitpro.audit.service.AuditService;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.service.CandidateProfileService;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.service.MatchTriggerService;
import com.recruitpro.verification.dto.EvidenceItemResponse;
import com.recruitpro.verification.dto.LedgerClaim;
import com.recruitpro.verification.dto.VerdictDetail;
import com.recruitpro.verification.dto.VerdictReason;
import com.recruitpro.verification.dto.VerdictResponseRequest;
import com.recruitpro.verification.dto.VerdictSummary;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimSubjectRef;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.EvidenceItem;
import com.recruitpro.verification.entity.RunRequester;
import com.recruitpro.verification.entity.VerificationReferenceRequest;
import com.recruitpro.verification.entity.VerificationRun;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.ClaimVerdictReviewRepository;
import com.recruitpro.verification.repository.EvidenceItemRepository;
import com.recruitpro.verification.repository.VerificationReferenceRequestRepository;
import com.recruitpro.verification.repository.VerificationRunRepository;
import com.recruitpro.verification.repository.VerificationSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The candidate's Claim Ledger (plan v2 section 5): claims with their current verdicts, the full verdict detail
 * (with evidence URLs) and the candidate's Share / Hide / Re-check decision. Nothing reaches staff until the
 * candidate shares it (principle 6).
 */
@Service
public class CandidateLedgerService {

    private final CandidateProfileService profileService;
    private final VerificationSubjects subjects;
    private final ClaimVerdictRepository verdictRepository;
    private final ClaimVerdictReviewRepository reviewRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final VerificationSourceRepository sourceRepository;
    private final VerificationReferenceRequestRepository referenceRepository;
    private final VerificationRunRepository runRepository;
    private final VerificationRunService runService;
    private final VerificationViews views;
    private final AuditService auditService;
    private final MatchTriggerService matchTriggerService;

    public CandidateLedgerService(CandidateProfileService profileService, VerificationSubjects subjects,
                                  ClaimVerdictRepository verdictRepository, ClaimVerdictReviewRepository reviewRepository,
                                  EvidenceItemRepository evidenceRepository, VerificationSourceRepository sourceRepository,
                                  VerificationReferenceRequestRepository referenceRepository,
                                  VerificationRunRepository runRepository, VerificationRunService runService,
                                  VerificationViews views, AuditService auditService, MatchTriggerService matchTriggerService) {
        this.profileService = profileService;
        this.subjects = subjects;
        this.verdictRepository = verdictRepository;
        this.reviewRepository = reviewRepository;
        this.evidenceRepository = evidenceRepository;
        this.sourceRepository = sourceRepository;
        this.referenceRepository = referenceRepository;
        this.runRepository = runRepository;
        this.runService = runService;
        this.views = views;
        this.auditService = auditService;
        this.matchTriggerService = matchTriggerService;
    }

    /** GET /ledger: every current claim subject with its current verdicts (newest first). */
    @Transactional
    public List<LedgerClaim> ledger() {
        CandidateProfile me = profileService.getOrProvisionMine();
        Map<String, List<ClaimVerdict>> bySubject = new HashMap<>();
        Set<Long> sourceIds = new HashSet<>();
        Set<Long> referenceIds = new HashSet<>();
        for (ClaimVerdict verdict : verdictRepository.findAllByCandidateIdAndCurrentTrueOrderByIdAsc(me.getId())) {
            bySubject.computeIfAbsent(verdict.subjectRef(), k -> new ArrayList<>()).add(verdict);
            if (verdict.getSourceId() != null) {
                sourceIds.add(verdict.getSourceId());
            }
            if (verdict.getReferenceId() != null) {
                referenceIds.add(verdict.getReferenceId());
            }
        }
        Map<Long, String> labels = labels(sourceIds, referenceIds);
        List<LedgerClaim> ledger = new ArrayList<>();
        for (VerificationSubjects.Subject subject : subjects.currentSubjects(me.getId())) {
            List<ClaimVerdict> verdicts = new ArrayList<>(bySubject.getOrDefault(subject.ref(), List.of()));
            verdicts.sort(Comparator.comparing(ClaimVerdict::getId).reversed());
            List<VerdictSummary> summaries = new ArrayList<>();
            for (ClaimVerdict verdict : verdicts) {
                summaries.add(new VerdictSummary(verdict.getId(), verdict.getVerifier(), verdict.getVerdict().name(),
                        verdict.getCandidateStatus().name(), label(verdict, labels), verdict.getCreatedAt()));
            }
            ledger.add(new LedgerClaim(subject.subjectType().name(), subject.subjectId(), subject.claimType().name(),
                    subject.value(), subject.tier().name(), summaries));
        }
        return ledger;
    }

    /** GET /verdicts/{id}: full detail of one of the candidate's verdicts (any status). */
    @Transactional
    public VerdictDetail detail(Long verdictId) {
        CandidateProfile me = profileService.getOrProvisionMine();
        return detail(ownVerdict(me.getId(), verdictId));
    }

    /**
     * POST /verdicts/{id}/response: SHARE or HIDE a current verdict (with an optional note shown to staff), or
     * RECHECK it (a new run of its source; 409/429 as for POST /runs). Returns the verdict detail.
     */
    @Transactional
    public VerdictDetail respond(Long verdictId, VerdictResponseRequest request) {
        CandidateProfile me = profileService.getOrProvisionMine();
        ClaimVerdict verdict = ownVerdict(me.getId(), verdictId);
        if (request == null || request.action() == null || request.action().isBlank()) {
            throw new BadRequestException("action is required (SHARE, HIDE or RECHECK)");
        }
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (note != null && note.length() > 1000) {
            throw new BadRequestException("note must be at most 1000 characters");
        }
        if (!verdict.isCurrent()) {
            throw new BadRequestException("This result was replaced or withdrawn; respond to the current one");
        }
        switch (action) {
            case "SHARE" -> {
                boolean wasShared = verdict.getCandidateStatus() == CandidateVerdictStatus.SHARED;
                verdict.setCandidateStatus(CandidateVerdictStatus.SHARED);
                verdict.setCandidateNote(note);
                verdict.setCandidateRespondedAt(UtcTime.now());
                if (!wasShared) {
                    auditService.record(VerificationAuditActions.VERDICT_SHARED, "CLAIM_VERDICT", String.valueOf(verdict.getId()),
                            "Candidate shared a " + verdict.getVerdict() + " verdict from " + verdict.getVerifier());
                    // R3-B11: shared again after HIDE: an organisation's earlier CONFIRM applies again, so its
                    // matching must pick the lift back up (HIDE re-matched when it removed it).
                    if (reviewRepository.existsByVerdictIdIn(List.of(verdict.getId()))) {
                        matchTriggerService.requestMatchForCandidate(me.getId());
                    }
                }
            }
            case "HIDE" -> {
                boolean wasShared = verdict.getCandidateStatus() == CandidateVerdictStatus.SHARED;
                verdict.setCandidateStatus(CandidateVerdictStatus.HIDDEN);
                verdict.setCandidateNote(note);
                verdict.setCandidateRespondedAt(UtcTime.now());
                if (wasShared && reviewRepository.existsByVerdictIdIn(List.of(verdict.getId()))) {
                    matchTriggerService.requestMatchForCandidate(me.getId());
                }
            }
            case "RECHECK" -> {
                if (verdict.getSourceId() == null) {
                    throw new BadRequestException("A reference answer cannot be re-checked; send a new reference request");
                }
                if (note != null) {
                    verdict.setCandidateNote(note);
                }
                runService.start(me.getId(), verdict.getSourceId(), RunRequester.CANDIDATE);
            }
            default -> throw new BadRequestException("action must be SHARE, HIDE or RECHECK");
        }
        verdictRepository.save(verdict);
        return detail(verdict);
    }

    VerdictDetail detail(ClaimVerdict verdict) {
        VerificationSource source = verdict.getSourceId() == null ? null : sourceRepository.findById(verdict.getSourceId()).orElse(null);
        VerificationReferenceRequest reference = verdict.getReferenceId() == null ? null
                : referenceRepository.findById(verdict.getReferenceId()).orElse(null);
        List<VerdictReason> summary = new ArrayList<>();
        if (verdict.getRunId() != null) {
            VerificationRun run = runRepository.findById(verdict.getRunId()).orElse(null);
            if (run != null) {
                summary = views.reasons(run.getSummaryJson());
            }
        }
        List<EvidenceItemResponse> evidence = new ArrayList<>();
        List<Long> ids = views.ids(verdict.getEvidenceItemIdsJson());
        if (!ids.isEmpty()) {
            Map<Long, EvidenceItem> byId = new LinkedHashMap<>();
            for (EvidenceItem item : evidenceRepository.findAllById(ids)) {
                byId.put(item.getId(), item);
            }
            for (Long id : ids) {
                EvidenceItem item = byId.get(id);
                if (item != null) {
                    evidence.add(views.evidence(item));
                }
            }
        }
        String sourceKind = source != null ? source.getKind().name() : reference != null ? "REFERENCE" : null;
        String sourceLabel = source != null ? source.getDisplayLabel()
                : reference != null ? "Reference: " + reference.getRefereeName() : null;
        return new VerdictDetail(verdict.getId(), verdict.getSubjectType().name(), verdict.getSubjectId(),
                verdict.getClaimType().name(), verdict.getClaimValue(), verdict.getVerifier(), verdict.getVerifierVersion(),
                verdict.getVerdict().name(), verdict.isOwnershipProven(), verdict.getCandidateStatus().name(),
                verdict.getCandidateNote(), verdict.getCandidateRespondedAt(), verdict.isCurrent(), verdict.getSourceId(),
                sourceKind, sourceLabel, verdict.getReferenceId(), verdict.getRunId(), summary,
                views.reasons(verdict.getReasonsJson()), views.notes(verdict.getIntegrityNotesJson()),
                verdict.getProbeQuestion(), evidence, verdict.getCreatedAt());
    }

    private ClaimVerdict ownVerdict(Long candidateId, Long verdictId) {
        return verdictRepository.findByIdAndCandidateId(verdictId, candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("Verdict not found"));
    }

    /** Source labels by negative-free keys: source ids as is, reference ids offset into a separate map. */
    private Map<Long, String> labels(Set<Long> sourceIds, Set<Long> referenceIds) {
        Map<Long, String> labels = new HashMap<>();
        for (VerificationSource source : sourceRepository.findAllById(sourceIds)) {
            labels.put(source.getId(), source.getDisplayLabel());
        }
        for (VerificationReferenceRequest reference : referenceRepository.findAllById(referenceIds)) {
            labels.put(-reference.getId(), "Reference: " + reference.getRefereeName());
        }
        return labels;
    }

    private static String label(ClaimVerdict verdict, Map<Long, String> labels) {
        if (verdict.getSourceId() != null) {
            return labels.get(verdict.getSourceId());
        }
        if (verdict.getReferenceId() != null) {
            return labels.get(-verdict.getReferenceId());
        }
        return null;
    }

    /** Subject reference helper for callers that hold type and id. */
    static String ref(ClaimVerdict verdict) {
        return ClaimSubjectRef.of(verdict.getSubjectType(), verdict.getSubjectId());
    }
}
