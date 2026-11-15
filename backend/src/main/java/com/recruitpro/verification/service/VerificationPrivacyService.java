package com.recruitpro.verification.service;

import com.recruitpro.verification.dto.VerificationExport;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.EvidenceItem;
import com.recruitpro.verification.entity.VerificationReferenceRequest;
import com.recruitpro.verification.entity.VerificationRun;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.repository.ClaimVerdictLabelRepository;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.ClaimVerdictReviewRepository;
import com.recruitpro.verification.repository.EvidenceItemRepository;
import com.recruitpro.verification.repository.VerificationOAuthStateRepository;
import com.recruitpro.verification.repository.VerificationReferenceRequestRepository;
import com.recruitpro.verification.repository.VerificationRunRepository;
import com.recruitpro.verification.repository.VerificationSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Verification data in the candidate's privacy rights (plan v2 section 5): the data export and account deletion.
 * Deletion removes every verification row of the candidate (reviews and labels of their verdicts included) and,
 * after the commit, every uploaded document and evidence snapshot.
 */
@Service
public class VerificationPrivacyService {

    private final VerificationSourceRepository sourceRepository;
    private final VerificationRunRepository runRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final ClaimVerdictRepository verdictRepository;
    private final ClaimVerdictReviewRepository reviewRepository;
    private final ClaimVerdictLabelRepository labelRepository;
    private final VerificationReferenceRequestRepository referenceRepository;
    private final VerificationOAuthStateRepository stateRepository;
    private final VerificationStorage storage;
    private final VerificationViews views;

    public VerificationPrivacyService(VerificationSourceRepository sourceRepository, VerificationRunRepository runRepository,
                                      EvidenceItemRepository evidenceRepository, ClaimVerdictRepository verdictRepository,
                                      ClaimVerdictReviewRepository reviewRepository, ClaimVerdictLabelRepository labelRepository,
                                      VerificationReferenceRequestRepository referenceRepository,
                                      VerificationOAuthStateRepository stateRepository, VerificationStorage storage,
                                      VerificationViews views) {
        this.sourceRepository = sourceRepository;
        this.runRepository = runRepository;
        this.evidenceRepository = evidenceRepository;
        this.verdictRepository = verdictRepository;
        this.reviewRepository = reviewRepository;
        this.labelRepository = labelRepository;
        this.referenceRepository = referenceRepository;
        this.stateRepository = stateRepository;
        this.storage = storage;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public VerificationExport export(Long candidateId) {
        if (candidateId == null) {
            return VerificationExport.empty();
        }
        List<VerificationExport.Source> sources = new ArrayList<>();
        for (VerificationSource source : sourceRepository.findAllByCandidateIdOrderByIdAsc(candidateId)) {
            sources.add(new VerificationExport.Source(source.getId(), source.getKind().name(), source.getIdentifier(),
                    source.getDisplayLabel(), source.getOwnershipStatus().name(),
                    source.getOwnershipMethod() == null ? null : source.getOwnershipMethod().name(),
                    source.getOwnershipCheckedAt(), source.getAccessTokenEnc() != null, source.getTokenScopes(),
                    source.getConsentTextVersion(), source.getConsentedAt(), source.getRevokedAt(), source.getCreatedAt()));
        }
        List<VerificationExport.Run> runs = new ArrayList<>();
        for (VerificationRun run : runRepository.findAllByCandidateIdOrderByIdAsc(candidateId)) {
            runs.add(new VerificationExport.Run(run.getId(), run.getSourceId(), run.getStatus().name(), run.getVerifier(),
                    run.getVerifierVersion(), run.getRequestedBy() == null ? null : run.getRequestedBy().name(),
                    run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt(), run.getErrorCode()));
        }
        List<VerificationExport.Evidence> evidence = new ArrayList<>();
        for (EvidenceItem item : evidenceRepository.findAllByCandidateIdOrderByIdAsc(candidateId)) {
            evidence.add(new VerificationExport.Evidence(item.getId(), item.getRunId(), item.getSourceId(), item.getReferenceId(),
                    item.getItemKey(), item.getKind().name(), item.getUrl(), item.getTitle(), item.getRetrievedAt(),
                    item.getContentHash(), item.getExcerpt()));
        }
        List<VerificationExport.Verdict> verdicts = new ArrayList<>();
        for (ClaimVerdict verdict : verdictRepository.findAllByCandidateIdOrderByIdAsc(candidateId)) {
            verdicts.add(new VerificationExport.Verdict(verdict.getId(), verdict.getSubjectType().name(), verdict.getSubjectId(),
                    verdict.getClaimType().name(), verdict.getClaimValue(), verdict.getSourceId(), verdict.getReferenceId(),
                    verdict.getRunId(), verdict.getVerifier(), verdict.getVerifierVersion(), verdict.getVerdict().name(),
                    verdict.isOwnershipProven(), views.json(verdict.getReasonsJson()), views.json(verdict.getIntegrityNotesJson()),
                    verdict.getProbeQuestion(), verdict.getCandidateStatus().name(), verdict.getCandidateNote(),
                    verdict.isCurrent(), verdict.getCreatedAt()));
        }
        List<VerificationExport.Reference> references = new ArrayList<>();
        for (VerificationReferenceRequest reference : referenceRepository.findAllByCandidateIdOrderByCreatedAtDescIdDesc(candidateId)) {
            references.add(new VerificationExport.Reference(reference.getId(), reference.getRefereeName(),
                    reference.getRefereeEmail(), reference.getRelationship().name(), reference.getEmployerName(),
                    reference.getEmployerDomain(), views.json(reference.getClaimRefsJson()), reference.getStatus().name(),
                    views.json(reference.getAnswersJson()), reference.getRefereeComment(), reference.getExpiresAt(),
                    reference.getAnsweredAt(), reference.getConsentedAt(), reference.getCreatedAt()));
        }
        return new VerificationExport(sources, runs, evidence, verdicts, references);
    }

    /** Counts of what {@link #deleteAllForCandidate} removed. */
    public record DeletionCounts(int sources, int verdicts, int evidenceItems, int references, int files) {
    }

    /**
     * Deletes every verification row of the candidate inside the caller's transaction; documents and snapshots are
     * deleted from storage after the commit.
     */
    @Transactional
    public DeletionCounts deleteAllForCandidate(Long candidateId) {
        if (candidateId == null) {
            return new DeletionCounts(0, 0, 0, 0, 0);
        }
        List<String> files = new ArrayList<>();
        for (VerificationSource source : sourceRepository.findAllByCandidateIdOrderByIdAsc(candidateId)) {
            if (source.getFilePath() != null) {
                files.add(source.getFilePath());
            }
        }
        for (EvidenceItem item : evidenceRepository.findAllByCandidateIdOrderByIdAsc(candidateId)) {
            if (item.getSnapshotPath() != null) {
                files.add(item.getSnapshotPath());
            }
        }
        List<Long> verdictIds = verdictRepository.findIdsByCandidateId(candidateId);
        if (!verdictIds.isEmpty()) {
            labelRepository.deleteAllByVerdictIds(verdictIds);
            reviewRepository.deleteAllByVerdictIds(verdictIds);
        }
        int verdicts = verdictRepository.deleteAllByCandidate(candidateId);
        int evidence = evidenceRepository.deleteAllByCandidate(candidateId);
        runRepository.deleteAllByCandidate(candidateId);
        int sources = sourceRepository.deleteAllByCandidate(candidateId);
        int references = referenceRepository.deleteAllByCandidate(candidateId);
        stateRepository.deleteAllByCandidate(candidateId);
        storage.deleteAfterCommit(files);
        return new DeletionCounts(sources, verdicts, evidence, references, files.size());
    }
}
