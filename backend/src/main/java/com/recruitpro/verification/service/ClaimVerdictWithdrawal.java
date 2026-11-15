package com.recruitpro.verification.service;

import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.SubjectType;
import com.recruitpro.verification.repository.ClaimVerdictRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Withdraws the current verdicts of a claim subject that disappears (review fix 16 Nov 2026): a candidate claim
 * that is deleted, replaced by the extracted value, or whose value changes. Such verdicts become WITHDRAWN and not
 * current, so the staff view, the shadow queue and matching stop using them. Depends only on the verdict repository
 * so the evidence package can call it without a bean cycle; callers re-match the candidate themselves. Resume facts
 * of a replaced analysis are excluded at read time instead ({@link VerificationStaffSupport#withCurrentSubjects}).
 */
@Component
public class ClaimVerdictWithdrawal {

    private final ClaimVerdictRepository verdictRepository;

    public ClaimVerdictWithdrawal(ClaimVerdictRepository verdictRepository) {
        this.verdictRepository = verdictRepository;
    }

    /** Returns the number of verdicts withdrawn. */
    @Transactional
    public int withdrawForSubject(Long candidateId, SubjectType subjectType, Long subjectId) {
        if (candidateId == null || subjectType == null || subjectId == null) {
            return 0;
        }
        List<ClaimVerdict> current = verdictRepository.findAllByCandidateIdAndSubjectTypeAndSubjectIdAndCurrentTrue(
                candidateId, subjectType, subjectId);
        for (ClaimVerdict verdict : current) {
            verdict.setCandidateStatus(CandidateVerdictStatus.WITHDRAWN);
            verdict.setCurrent(false);
        }
        if (!current.isEmpty()) {
            verdictRepository.saveAll(current);
        }
        return current.size();
    }

    /** A deleted or changed candidate claim. */
    public int withdrawForClaim(Long candidateId, Long claimId) {
        return withdrawForSubject(candidateId, SubjectType.CANDIDATE_CLAIM, claimId);
    }
}
