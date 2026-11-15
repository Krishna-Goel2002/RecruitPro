package com.recruitpro.verification.service;

import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.application.entity.Application;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.service.EvidenceContributor;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.ClaimVerdictReview;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Verified claims as match evidence (plan v2 3.5). For the application's organisation O, each verdict that passes the
 * tier rule ({@link VerificationStaffSupport#lifts}: VERIFIED, ownership proven, SHARED, current, O in VISIBLE mode
 * with the connector enabled, O's latest review CONFIRM) adds the fact {@code vf:<verdictId>} with the claim's type
 * and value at tier CORROBORATED. The AI service treats {@code vf:} ids as general facts (origin {@code other}),
 * provenance CORROBORATED. Other organisations never see the lift. Read-only (EvidenceContributor contract).
 */
@Component
@Order(30)
public class VerificationEvidenceContributor implements EvidenceContributor {

    public static final String FACT_PREFIX = "vf:";
    public static final String SOURCE_SECTION = "verification";

    private final ClaimVerdictRepository verdictRepository;
    private final VerificationSettingsService settingsService;
    private final VerificationStaffSupport support;

    public VerificationEvidenceContributor(ClaimVerdictRepository verdictRepository,
                                           VerificationSettingsService settingsService, VerificationStaffSupport support) {
        this.verdictRepository = verdictRepository;
        this.settingsService = settingsService;
        this.support = support;
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
            if (verdict.getVerdict() == VerdictValue.VERIFIED && verdict.isOwnershipProven()) {
                candidates.add(verdict);
            }
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
            facts.add(EvidenceContributor.fact(FACT_PREFIX + verdict.getId(), verdict.getClaimType().name(),
                    verdict.getClaimValue(), "Verified by " + verdict.getVerifier() + "; confirmed by a recruiter",
                    SOURCE_SECTION, EvidenceTier.CORROBORATED));
        }
        return facts;
    }
}
