package com.recruitpro.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.ai.dto.AiMatchResponse;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.entity.ResumeFactReviewStatus;
import com.recruitpro.candidate.entity.ResumeFactType;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.service.StoredMatchResults;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobMatchRun;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.hr.repository.JobMatchRunRepository;
import com.recruitpro.hr.repository.JobRequirementRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Fixtures for screening tests: resumes, analyses, facts, applications, requirements and match runs. */
@Component
public class ScreeningFixtures {

    private final ResumeRepository resumes;
    private final ResumeAnalysisRepository analyses;
    private final ResumeAnalysisFactRepository facts;
    private final ApplicationRepository applications;
    private final JobRequirementRepository requirements;
    private final JobMatchRunRepository matchRuns;
    private final ObjectMapper objectMapper;

    public ScreeningFixtures(ResumeRepository resumes, ResumeAnalysisRepository analyses, ResumeAnalysisFactRepository facts,
                             ApplicationRepository applications, JobRequirementRepository requirements,
                             JobMatchRunRepository matchRuns, ObjectMapper objectMapper) {
        this.resumes = resumes;
        this.analyses = analyses;
        this.facts = facts;
        this.applications = applications;
        this.requirements = requirements;
        this.matchRuns = matchRuns;
        this.objectMapper = objectMapper;
    }

    public Resume resume(CandidateProfile candidate, String filename) {
        Resume resume = new Resume();
        resume.setCandidate(candidate);
        resume.setStorageKey(UUID.randomUUID().toString());
        resume.setOriginalFilename(filename);
        resume.setFileExtension("pdf");
        resume.setContentType("application/pdf");
        resume.setFileSize(32);
        resume.setUploadedAt(UtcTime.now());
        return resumes.save(resume);
    }

    public ResumeAnalysis analysis(Resume resume, ResumeAnalysisStatus status) {
        ResumeAnalysis analysis = new ResumeAnalysis();
        analysis.setResume(resume);
        analysis.setStatus(status);
        if (status == ResumeAnalysisStatus.COMPLETED || status == ResumeAnalysisStatus.PARTIAL) {
            analysis.setParserVersion("rule-parser-test");
            analysis.setCompletedAt(UtcTime.now());
        }
        return analyses.save(analysis);
    }

    public ResumeAnalysisFact fact(ResumeAnalysis analysis, ResumeFactType type, String value) {
        ResumeAnalysisFact fact = new ResumeAnalysisFact();
        fact.setAnalysis(analysis);
        fact.setType(type);
        fact.setExtractedValue(value);
        fact.setNormalizedValue(value.toLowerCase());
        fact.setEvidence(value + " (resume evidence)");
        fact.setSourceSection("skills");
        fact.setReviewStatus(ResumeFactReviewStatus.UNREVIEWED);
        return facts.save(fact);
    }

    public Application application(CandidateProfile candidate, Job job, Resume resume, LocalDateTime appliedAt) {
        return application(candidate, job, resume, appliedAt, null);
    }

    /** A new SUBMITTED application; the returned instance keeps the given (loaded) job, candidate and resume. */
    public Application application(CandidateProfile candidate, Job job, Resume resume, LocalDateTime appliedAt, String coverLetter) {
        Application application = new Application();
        application.setCandidate(candidate);
        application.setJob(job);
        application.setResume(resume);
        application.setStatus(ApplicationStatus.SUBMITTED);
        application.setAppliedAt(appliedAt);
        application.setCoverLetter(coverLetter);
        return applications.saveAndFlush(application);
    }

    public Application withStatus(Application application, ApplicationStatus status) {
        application.setStatus(status);
        return applications.saveAndFlush(application);
    }

    public JobRequirement requirement(Job job, RequirementType type, String description, boolean mandatory, boolean knockout) {
        JobRequirement requirement = new JobRequirement();
        requirement.setJob(job);
        requirement.setType(type);
        requirement.setDescription(description);
        requirement.setMandatory(mandatory);
        requirement.setKnockout(knockout);
        return requirements.save(requirement);
    }

    /** A current evidence-ladder run with the given numbers (previous current runs are left untouched). */
    public JobMatchRun currentRun(Application application, ResumeAnalysis analysis, Double verified, Double claimed,
                                  Boolean knockoutsVerified, List<AiMatchResponse.RequirementResult> results,
                                  List<Long> unverifiedKnockouts) {
        JobMatchRun run = new JobMatchRun();
        run.setApplication(application);
        run.setJob(application.getJob());
        run.setResumeAnalysis(analysis);
        run.setInputFingerprint("f".repeat(64));
        run.setAlgorithmVersion("evidence-ladder-3");
        run.setScoreLabel("Verified requirement evidence coverage (not a probability of success)");
        run.setVerifiedCoverage(verified == null ? null : BigDecimal.valueOf(verified).setScale(1, RoundingMode.HALF_UP));
        run.setObservedEvidenceCoverage(run.getVerifiedCoverage());
        run.setClaimedCoverage(claimed == null ? null : BigDecimal.valueOf(claimed).setScale(1, RoundingMode.HALF_UP));
        run.setKnockoutsVerified(knockoutsVerified);
        run.setRequirementResultsJson(new StoredMatchResults(results, unverifiedKnockouts).write(objectMapper));
        run.setCalculatedAt(UtcTime.now());
        run.setCurrentRun(true);
        run.setRequirementsVersion(application.getJob().getRequirementsVersion());
        run.setTriggerReason("INITIAL");
        return matchRuns.save(run);
    }

    public static AiMatchResponse.RequirementResult matched(Long requirementId, String tier, String provenance) {
        return new AiMatchResponse.RequirementResult(requirementId, "MATCHED", tier, "Evidence for requirement " + requirementId,
                List.of("fact:1"), "skills", provenance, "Matched.", null);
    }

    public static AiMatchResponse.RequirementResult notObserved(Long requirementId) {
        return new AiMatchResponse.RequirementResult(requirementId, "NOT_OBSERVED", null, null, List.of(), null, null,
                "Not observed.", "Ask about it in the interview.");
    }
}
