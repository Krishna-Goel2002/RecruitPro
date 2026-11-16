package com.recruitpro.verification.service;

import com.recruitpro.application.entity.Application;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.CandidateClaimVerification;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.evidence.repository.CandidateClaimVerificationRepository;
import com.recruitpro.evidence.service.ClaimEvidence;
import com.recruitpro.evidence.service.MatchTriggerService;
import com.recruitpro.hr.service.ApplicantQueryService;
import com.recruitpro.hr.service.HrJobAccessService;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.verification.dto.StaffClaim;
import com.recruitpro.verification.dto.StaffVerdict;
import com.recruitpro.verification.dto.StaffVerificationResponse;
import com.recruitpro.verification.dto.VerdictReviewRequest;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.ClaimVerdictReview;
import com.recruitpro.verification.entity.ReviewDecision;
import com.recruitpro.verification.entity.SubjectType;
import com.recruitpro.verification.entity.VerificationMode;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import com.recruitpro.verification.repository.ClaimVerdictReviewRepository;
import com.recruitpro.verification.repository.VerificationLookupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Staff side of the Claim Ledger (plan v2 section 5): the verification view of an application and the per-organisation
 * review of a shared verdict. Only SHARED, current verdicts are ever shown; in SHADOW mode nothing is (principle 9).
 * Blind-first (principle 7): evidence items and the candidate's note only after the identity is revealed.
 */
@Service
public class StaffVerificationService {

    private final HrJobAccessService jobAccess;
    private final ApplicationRepository applicationRepository;
    private final OrganizationRepository organizationRepository;
    private final ClaimVerdictRepository verdictRepository;
    private final ClaimVerdictReviewRepository reviewRepository;
    private final CandidateClaimRepository claimRepository;
    private final CandidateClaimVerificationRepository claimVerificationRepository;
    private final VerificationLookupRepository lookupRepository;
    private final VerificationSettingsService settingsService;
    private final VerificationStaffSupport support;
    private final AuditService auditService;
    private final MatchTriggerService matchTriggerService;

    public StaffVerificationService(HrJobAccessService jobAccess, ApplicationRepository applicationRepository,
                                    OrganizationRepository organizationRepository, ClaimVerdictRepository verdictRepository,
                                    ClaimVerdictReviewRepository reviewRepository, CandidateClaimRepository claimRepository,
                                    CandidateClaimVerificationRepository claimVerificationRepository,
                                    VerificationLookupRepository lookupRepository,
                                    VerificationSettingsService settingsService, VerificationStaffSupport support,
                                    AuditService auditService, MatchTriggerService matchTriggerService) {
        this.jobAccess = jobAccess;
        this.applicationRepository = applicationRepository;
        this.organizationRepository = organizationRepository;
        this.verdictRepository = verdictRepository;
        this.reviewRepository = reviewRepository;
        this.claimRepository = claimRepository;
        this.claimVerificationRepository = claimVerificationRepository;
        this.lookupRepository = lookupRepository;
        this.settingsService = settingsService;
        this.support = support;
        this.auditService = auditService;
        this.matchTriggerService = matchTriggerService;
    }

    /** GET /api/v1/hr/applications/{applicationId}/verification (ADMIN, HR_MANAGER, RECRUITER of the job's organisation). */
    @Transactional(readOnly = true)
    public StaffVerificationResponse view(Long applicationId) {
        CurrentUser caller = jobAccess.requireJobReader();
        Application application = applicationId == null ? null : applicationRepository.findById(applicationId).orElse(null);
        if (application == null) {
            throw new ResourceNotFoundException("Application not found");
        }
        // 404 for an application of another organisation's job.
        jobAccess.requireApplicantReadAccess(application.getJob().getId());
        Long organizationId = caller.organizationId();
        boolean revealed = ApplicantQueryService.isRevealed(application.getIdentityRevealedAt(), blindReview(organizationId));
        VerificationSettingsService.Effective settings = settingsService.effective(organizationId);
        if (!settings.visible()) {
            return new StaffVerificationResponse(VerificationMode.SHADOW.name(), revealed, List.of());
        }
        Long candidateId = application.getCandidate().getId();
        List<ClaimVerdict> verdicts = new ArrayList<>();
        List<ClaimVerdict> shared = support.withCurrentSubjects(verdictRepository
                .findAllByCandidateIdAndCurrentTrueAndCandidateStatusOrderByIdAsc(candidateId, CandidateVerdictStatus.SHARED));
        Map<Long, String> connectors = support.connectors(shared);
        for (ClaimVerdict verdict : shared) {
            if (settings.enabled(connectors.get(verdict.getId()))) {
                verdicts.add(verdict);
            }
        }
        List<Long> ids = verdicts.stream().map(ClaimVerdict::getId).toList();
        Map<Long, ClaimVerdictReview> reviews = support.latestReviews(ids, organizationId);
        Map<Long, String> reviewerNames = support.reviewerNames(reviews.values());
        Map<Long, EvidenceTier> claimTiers = claimTiers(candidateId, organizationId);

        Map<String, List<ClaimVerdict>> bySubject = new LinkedHashMap<>();
        for (ClaimVerdict verdict : verdicts) {
            bySubject.computeIfAbsent(verdict.subjectRef(), k -> new ArrayList<>()).add(verdict);
        }
        List<StaffClaim> claims = new ArrayList<>();
        for (List<ClaimVerdict> group : bySubject.values()) {
            ClaimVerdict first = group.get(0);
            EvidenceTier tier = first.getSubjectType() == SubjectType.RESUME_FACT ? EvidenceTier.RESUME_STATED
                    : claimTiers.getOrDefault(first.getSubjectId(), EvidenceTier.SELF_CLAIMED);
            List<StaffVerdict> staffVerdicts = new ArrayList<>();
            for (ClaimVerdict verdict : group) {
                String connector = connectors.get(verdict.getId());
                ClaimVerdictReview review = reviews.get(verdict.getId());
                if (VerificationStaffSupport.lifts(verdict, review, settings, connector)
                        && tier.level() < EvidenceTier.CORROBORATED.level()) {
                    tier = EvidenceTier.CORROBORATED;
                }
                staffVerdicts.add(support.staffVerdict(verdict, review, reviewerNames, connector, revealed, settings));
            }
            claims.add(new StaffClaim(first.getSubjectType().name(), first.getSubjectId(), first.getClaimType().name(),
                    first.getClaimValue(), tier.name(), staffVerdicts));
        }
        return new StaffVerificationResponse(VerificationMode.VISIBLE.name(), revealed, claims);
    }

    /**
     * POST /api/v1/hr/verdicts/{id}/review (ADMIN, HR_MANAGER, RECRUITER with an application of the candidate in
     * their organisation): CONFIRM or OVERRULE (note required). VISIBLE mode only. Audited (VERDICT_REVIEWED);
     * re-matches the candidate's applications to the organisation's jobs.
     */
    @Transactional
    public StaffVerdict review(Long verdictId, VerdictReviewRequest request) {
        CurrentUser caller = jobAccess.requireJobReader();
        Long organizationId = caller.organizationId();
        ClaimVerdict verdict = verdictId == null ? null : verdictRepository.findById(verdictId).orElse(null);
        if (verdict == null || !verdict.isCurrent() || verdict.getCandidateStatus() != CandidateVerdictStatus.SHARED
                || support.withCurrentSubjects(List.of(verdict)).isEmpty()) {
            throw new ResourceNotFoundException("Verdict not found");
        }
        List<Application> applications = lookupRepository.findByCandidateAndOrganization(verdict.getCandidateId(), organizationId);
        if (applications.isEmpty()) {
            throw new ResourceNotFoundException("Verdict not found");
        }
        VerificationSettingsService.Effective settings = settingsService.effective(organizationId);
        Map<Long, String> connectors = support.connectors(List.of(verdict));
        String connector = connectors.get(verdict.getId());
        if (!settings.enabled(connector)) {
            throw new ResourceNotFoundException("Verdict not found");
        }
        if (!settings.visible()) {
            throw new BadRequestException("Verification is in shadow mode for your organisation; reviews are available once an administrator switches it to VISIBLE");
        }
        if (request == null || request.decision() == null || request.decision().isBlank()) {
            throw new BadRequestException("decision is required (CONFIRM or OVERRULE)");
        }
        ReviewDecision decision;
        try {
            decision = ReviewDecision.valueOf(request.decision().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("decision must be CONFIRM or OVERRULE");
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (note != null && note.length() > 1000) {
            throw new BadRequestException("note must be at most 1000 characters");
        }
        if (decision == ReviewDecision.OVERRULE && note == null) {
            throw new BadRequestException("A note is required to overrule a verdict");
        }
        ClaimVerdictReview review = new ClaimVerdictReview();
        review.setVerdictId(verdict.getId());
        review.setOrganizationId(organizationId);
        review.setDecision(decision);
        review.setNote(note);
        review.setReviewerUserId(caller.userId());
        ClaimVerdictReview saved = reviewRepository.saveAndFlush(review);
        auditService.record(VerificationAuditActions.VERDICT_REVIEWED, "CLAIM_VERDICT", String.valueOf(verdict.getId()),
                decision + " of a " + verdict.getVerdict() + " " + verdict.getVerifier() + " verdict while reviewing application #"
                        + applications.get(0).getId());
        matchTriggerService.requestMatchForCandidateInOrganization(verdict.getCandidateId(), organizationId);
        boolean blind = blindReview(organizationId);
        boolean revealed = false;
        for (Application application : applications) {
            revealed |= ApplicantQueryService.isRevealed(application.getIdentityRevealedAt(), blind);
        }
        return support.staffVerdict(verdict, saved, support.reviewerNames(List.of(saved)), connector, revealed, settings);
    }

    private boolean blindReview(Long organizationId) {
        return organizationRepository.findById(organizationId).map(Organization::isBlindReview).orElse(true);
    }

    /**
     * Tier of each of the candidate's claims for this organisation, from the organisation's own verification row
     * (R3-B03 / R2-H02): another organisation's check neither raises nor removes it here, and the claim's own
     * (candidate-facing summary) columns are never read.
     */
    private Map<Long, EvidenceTier> claimTiers(Long candidateId, Long organizationId) {
        List<CandidateClaim> claims = claimRepository.findAllByCandidateIdOrderByIdAsc(candidateId);
        Map<Long, CandidateClaimVerification> own = new HashMap<>();
        if (!claims.isEmpty() && organizationId != null) {
            List<Long> claimIds = claims.stream().map(CandidateClaim::getId).toList();
            for (CandidateClaimVerification row : claimVerificationRepository.findAllByClaimIdInAndOrganizationId(claimIds,
                    organizationId)) {
                own.put(row.getClaimId(), row);
            }
        }
        Map<Long, EvidenceTier> tiers = new HashMap<>();
        for (CandidateClaim claim : claims) {
            ClaimEvidence evidence = ClaimEvidence.forOrganization(claim, own.get(claim.getId()));
            tiers.put(claim.getId(), evidence.tier() == null ? EvidenceTier.SELF_CLAIMED : evidence.tier());
        }
        return tiers;
    }
}
