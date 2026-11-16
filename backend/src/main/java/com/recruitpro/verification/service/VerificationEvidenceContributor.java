package com.recruitpro.verification.service;

import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.application.entity.Application;
import com.recruitpro.evidence.entity.CandidateClaimVerification;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.repository.CandidateClaimVerificationRepository;
import com.recruitpro.evidence.service.EvidenceContributor;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.ClaimVerdictReview;
import com.recruitpro.verification.entity.SubjectType;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verified claims as match evidence (plan v2 3.5). For the application's organisation O, each verdict that passes the
 * tier rule ({@link VerificationStaffSupport#lifts}: VERIFIED, ownership proven, SHARED, current, O in VISIBLE mode
 * with the connector enabled, O's latest review CONFIRM) adds the fact {@code vf:<verdictId>} with the claim's type
 * and value at tier CORROBORATED. The AI service treats {@code vf:} ids as general facts (origin {@code other}),
 * provenance CORROBORATED. Other organisations never see the lift. A claim that O's recruiters rejected
 * ({@code candidate_claim_verifications} row of O, R2-H02) never lifts for O, whatever its verdicts say; another
 * organisation's rejection has no effect here (R3-B03). Read-only (EvidenceContributor contract).
 */
@Component
@Order(30)
public class VerificationEvidenceContributor implements EvidenceContributor {

    public static final String FACT_PREFIX = "vf:";
    public static final String SOURCE_SECTION = "verification";

    private final ClaimVerdictRepository verdictRepository;
    private final VerificationSettingsService settingsService;
    private final VerificationStaffSupport support;
    private final CandidateClaimVerificationRepository claimVerificationRepository;

    public VerificationEvidenceContributor(ClaimVerdictRepository verdictRepository,
                                           VerificationSettingsService settingsService, VerificationStaffSupport support,
                                           CandidateClaimVerificationRepository claimVerificationRepository) {
        this.verdictRepository = verdictRepository;
        this.settingsService = settingsService;
        this.support = support;
        this.claimVerificationRepository = claimVerificationRepository;
    }

    @Override
    public List<AiMatchRequest.Fact> contribute(Application application, List<JobRequirement> requirements) {
        if (application == null || application.getJob() == null || application.getCandidate() == null) {
            return List.of();
        }
        Long organizationId = application.getJob().getOrganizationId();
        VerificationSettingsService.Effective settings = settingsService.effective(organizationId);
        if (!settings.visible()) {
            return List.of();
        }
        List<ClaimVerdict> candidates = new ArrayList<>();
        for (ClaimVerdict verdict : verdictRepository.findAllByCandidateIdAndCurrentTrueAndCandidateStatusOrderByIdAsc(
                application.getCandidate().getId(), CandidateVerdictStatus.SHARED)) {
            // VERIFIED as it counts for this organisation (V26: a verdict capped only by an employer / issuer domain the
            // organisation lists as trusted counts too); lifts() re-checks it.
            if (verdict.isOwnershipProven() && DomainTrusts.effectiveVerdict(verdict, settings) == VerdictValue.VERIFIED) {
                candidates.add(verdict);
            }
        }
        // Verdicts whose claim subject disappeared (fact of a replaced analysis, deleted claim) never lift.
        candidates = support.withCurrentSubjects(candidates);
        // Claims this organisation rejected never lift here (per-organisation rejection, R2-H02 + R3-B03).
        Set<Long> rejectedHere = rejectedClaimIds(candidates, organizationId);
        if (!rejectedHere.isEmpty()) {
            candidates = candidates.stream().filter(verdict -> verdict.getSubjectType() != SubjectType.CANDIDATE_CLAIM
                    || !rejectedHere.contains(verdict.getSubjectId())).toList();
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, String> connectors = support.connectors(candidates);
        Map<Long, ClaimVerdictReview> reviews = support.latestReviews(candidates.stream().map(ClaimVerdict::getId).toList(),
                organizationId);
        List<AiMatchRequest.Fact> facts = new ArrayList<>();
        for (ClaimVerdict verdict : candidates) {
            if (!VerificationStaffSupport.lifts(verdict, reviews.get(verdict.getId()), settings, connectors.get(verdict.getId()))) {
                continue;
            }
            String trust = verdict.getVerdict() == VerdictValue.VERIFIED ? ""
                    : "; domain trusted by the organisation";
            facts.add(EvidenceContributor.fact(FACT_PREFIX + verdict.getId(), verdict.getClaimType().name(),
                    verdict.getClaimValue(), "Verified by " + verdict.getVerifier() + trust + "; confirmed by a recruiter",
                    SOURCE_SECTION, EvidenceTier.CORROBORATED));
        }
        return facts;
    }

    private Set<Long> rejectedClaimIds(List<ClaimVerdict> verdicts, Long organizationId) {
        Set<Long> claimIds = new HashSet<>();
        for (ClaimVerdict verdict : verdicts) {
            if (verdict.getSubjectType() == SubjectType.CANDIDATE_CLAIM && verdict.getSubjectId() != null) {
                claimIds.add(verdict.getSubjectId());
            }
        }
        Set<Long> rejected = new HashSet<>();
        if (claimIds.isEmpty() || organizationId == null) {
            return rejected;
        }
        for (CandidateClaimVerification row : claimVerificationRepository.findAllByClaimIdInAndOrganizationId(claimIds,
                organizationId)) {
            if (row.isRejected()) {
                rejected.add(row.getClaimId());
            }
        }
        return rejected;
    }
}
