package com.recruitpro.evidence.service;

import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeFactReviewStatus;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.service.CandidateProfileService;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.evidence.dto.CandidateClaimCreateRequest;
import com.recruitpro.evidence.dto.CandidateClaimResponse;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.verification.service.ClaimVerdictWithdrawal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Candidate claims (D5, C1): free-standing claims (/api/v1/candidates/me/claims) and corrections of resume
 * facts (fact review). Every change queues re-matching of the candidate's applications; deleting a claim or
 * changing its value withdraws the claim's current verification verdicts.
 */
@Service
@Transactional
public class CandidateClaimService {

    public static final int MAX_CLAIMS_PER_CANDIDATE = 100;

    private final CandidateClaimRepository claimRepository;
    private final CandidateProfileService profileService;
    private final ResumeAnalysisFactRepository factRepository;
    private final MatchTriggerService matchTriggerService;
    private final ClaimVerdictWithdrawal verdictWithdrawal;

    public CandidateClaimService(CandidateClaimRepository claimRepository, CandidateProfileService profileService,
                                 ResumeAnalysisFactRepository factRepository, MatchTriggerService matchTriggerService,
                                 ClaimVerdictWithdrawal verdictWithdrawal) {
        this.claimRepository = claimRepository;
        this.profileService = profileService;
        this.factRepository = factRepository;
        this.matchTriggerService = matchTriggerService;
        this.verdictWithdrawal = verdictWithdrawal;
    }

    /** Not read-only: {@code getOrProvisionMine()} may create a missing candidate profile. */
    public List<CandidateClaimResponse> listMine() {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        return claimRepository.findAllByCandidateIdOrderByCreatedAtDescIdDesc(candidate.getId()).stream()
                .map(CandidateClaimResponse::forCandidate).toList();
    }

    public CandidateClaimResponse createMine(CandidateClaimCreateRequest request) {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        if (claimRepository.countByCandidateId(candidate.getId()) >= MAX_CLAIMS_PER_CANDIDATE) {
            throw new BadRequestException("You can keep at most " + MAX_CLAIMS_PER_CANDIDATE + " claims; delete one first");
        }
        CandidateClaim claim = new CandidateClaim();
        claim.setCandidate(candidate);
        claim.setClaimType(request.claimType());
        claim.setClaimValue(request.value().trim());
        claim.setEvidenceUrl(blankToNull(request.evidenceUrl()));
        claim.setTier(EvidenceTier.SELF_CLAIMED);
        CandidateClaim saved = claimRepository.save(claim);
        matchTriggerService.requestMatchForCandidate(candidate.getId());
        return CandidateClaimResponse.forCandidate(saved);
    }

    /** Deletes a claim; a claim that corrected a resume fact also resets that fact's review. */
    public void deleteMine(Long claimId) {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        CandidateClaim claim = claimRepository.findByIdAndCandidateId(claimId, candidate.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Claim not found"));
        if (claim.getSourceFactId() != null) {
            factRepository.findById(claim.getSourceFactId())
                    .filter(fact -> belongsTo(fact, candidate))
                    .ifPresent(fact -> {
                        fact.setReviewedValue(null);
                        fact.setReviewStatus(ResumeFactReviewStatus.UNREVIEWED);
                        fact.setReviewer(null);
                        fact.setReviewedAt(null);
                    });
        }
        claimRepository.delete(claim);
        verdictWithdrawal.withdrawForClaim(candidate.getId(), claim.getId());
        matchTriggerService.requestMatchForCandidate(candidate.getId());
    }

    /**
     * Fact review with a changed value: creates or updates the SELF_CLAIMED claim linked to the fact. A changed
     * value clears any earlier recruiter verification. Returns null for contact facts (no claim).
     */
    public CandidateClaim upsertForFact(CandidateProfile candidate, ResumeAnalysisFact fact, String value) {
        ClaimType type = ClaimType.fromFactType(fact.getType());
        if (type == null) {
            return null;
        }
        List<CandidateClaim> linked = claimRepository.findAllByCandidateIdAndSourceFactId(candidate.getId(), fact.getId());
        CandidateClaim claim = linked.isEmpty() ? new CandidateClaim() : linked.get(0);
        for (int i = 1; i < linked.size(); i++) {
            claimRepository.delete(linked.get(i));
            verdictWithdrawal.withdrawForClaim(candidate.getId(), linked.get(i).getId());
        }
        if (claim.getId() != null && value.equals(claim.getClaimValue()) && claim.getClaimType() == type) {
            return claim;
        }
        if (claim.getId() != null) {
            // The claim's value changes: verdicts about the old value no longer apply.
            verdictWithdrawal.withdrawForClaim(candidate.getId(), claim.getId());
        }
        claim.setCandidate(candidate);
        claim.setClaimType(type);
        claim.setClaimValue(value);
        claim.setSourceFactId(fact.getId());
        claim.resetVerification();
        CandidateClaim saved = claimRepository.save(claim);
        matchTriggerService.requestMatchForCandidate(candidate.getId());
        return saved;
    }

    /** Fact review confirming the extracted value: removes the correction claim, if any. */
    public void removeForFact(CandidateProfile candidate, Long factId) {
        List<CandidateClaim> linked = claimRepository.findAllByCandidateIdAndSourceFactId(candidate.getId(), factId);
        if (linked.isEmpty()) {
            return;
        }
        claimRepository.deleteAll(linked);
        for (CandidateClaim claim : linked) {
            verdictWithdrawal.withdrawForClaim(candidate.getId(), claim.getId());
        }
        matchTriggerService.requestMatchForCandidate(candidate.getId());
    }

    private static boolean belongsTo(ResumeAnalysisFact fact, CandidateProfile candidate) {
        return fact.getAnalysis() != null && fact.getAnalysis().getResume() != null
                && fact.getAnalysis().getResume().getCandidate() != null
                && candidate.getId().equals(fact.getAnalysis().getResume().getCandidate().getId());
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
