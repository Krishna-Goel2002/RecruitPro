package com.recruitpro.verification.service;

import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.verification.entity.ClaimSubjectRef;
import com.recruitpro.verification.entity.SubjectType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The candidate's claim subjects (plan v2 section 5, GET /ledger): SKILL, CERTIFICATION, EDUCATION and EXPERIENCE
 * facts of the finished analysis of each resume (contact facts never), and the candidate's claims (a recruiter's
 * rejection is per organisation and does not remove a claim here). Ids are {@code fact:<id>} / {@code claim:<id>}.
 */
@Component
public class VerificationSubjects {

    /** Analyses whose facts are current (PARTIAL analyses are used by matching too). */
    static final Set<ResumeAnalysisStatus> USABLE = EnumSet.of(ResumeAnalysisStatus.COMPLETED, ResumeAnalysisStatus.PARTIAL);
    /** Agent service limit of claims per request. */
    public static final int MAX_CLAIMS = 200;

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository analysisRepository;
    private final ResumeAnalysisFactRepository factRepository;
    private final CandidateClaimRepository claimRepository;

    public VerificationSubjects(ResumeRepository resumeRepository, ResumeAnalysisRepository analysisRepository,
                                ResumeAnalysisFactRepository factRepository, CandidateClaimRepository claimRepository) {
        this.resumeRepository = resumeRepository;
        this.analysisRepository = analysisRepository;
        this.factRepository = factRepository;
        this.claimRepository = claimRepository;
    }

    /** One claim subject with its own (organisation-independent) tier. */
    public record Subject(SubjectType subjectType, Long subjectId, ClaimType claimType, String value, EvidenceTier tier) {
        public String ref() {
            return ClaimSubjectRef.of(subjectType, subjectId);
        }
    }

    @Transactional(readOnly = true)
    public List<Subject> currentSubjects(Long candidateId) {
        List<Subject> subjects = new ArrayList<>();
        for (Resume resume : resumeRepository.findAllByCandidateIdOrderByUploadedAtDescIdDesc(candidateId)) {
            ResumeAnalysis analysis = analysisRepository.findByResumeId(resume.getId()).orElse(null);
            if (analysis == null || !USABLE.contains(analysis.getStatus())) {
                continue;
            }
            for (ResumeAnalysisFact fact : factRepository.findAllByAnalysisIdOrderById(analysis.getId())) {
                ClaimType type = ClaimType.fromFactType(fact.getType());
                String value = fact.getExtractedValue() == null ? null : fact.getExtractedValue().trim();
                if (type == null || value == null || value.isEmpty()) {
                    continue;
                }
                EvidenceTier tier = fact.getEvidenceTier() == null ? EvidenceTier.RESUME_STATED : fact.getEvidenceTier();
                subjects.add(new Subject(SubjectType.RESUME_FACT, fact.getId(), type, limit(value), tier));
            }
        }
        for (CandidateClaim claim : claimRepository.findAllByCandidateIdOrderByIdAsc(candidateId)) {
            // R3-B03: a recruiter's rejection applies to that recruiter's organisation only (R2-H02,
            // candidate_claim_verifications); the claim's own columns are only a candidate-facing summary of all
            // organisations' checks (CandidateClaim.summarize), so they must not remove the claim, and its verdicts,
            // for any organisation or for the candidate. The subject's tier below is that summary (candidate ledger).
            if (claim.getClaimType() == null || claim.getClaimValue() == null || claim.getClaimValue().isBlank()) {
                continue;
            }
            EvidenceTier tier = claim.getTier() == null ? EvidenceTier.SELF_CLAIMED : claim.getTier();
            subjects.add(new Subject(SubjectType.CANDIDATE_CLAIM, claim.getId(), claim.getClaimType(),
                    limit(claim.getClaimValue().trim()), tier));
        }
        return subjects;
    }

    /** Current subjects by reference ({@code fact:12}), in ledger order. */
    @Transactional(readOnly = true)
    public Map<String, Subject> currentSubjectsByRef(Long candidateId) {
        Map<String, Subject> byRef = new LinkedHashMap<>();
        for (Subject subject : currentSubjects(candidateId)) {
            byRef.putIfAbsent(subject.ref(), subject);
        }
        return byRef;
    }

    private static String limit(String value) {
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
