package com.recruitpro.hr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.ai.dto.AiBatchMatchRequest;
import com.recruitpro.ai.dto.AiBatchMatchResponse;
import com.recruitpro.ai.dto.AiMatchResponse;
import com.recruitpro.ai.service.AiServiceClient;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.entity.ResumeFactReviewStatus;
import com.recruitpro.candidate.entity.ResumeFactType;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.service.ResumeIntelligenceService;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobMatchRun;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.hr.repository.JobMatchRunRepository;
import com.recruitpro.hr.repository.JobRequirementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.client.RestClient;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicantIntelligenceServiceTest {
    private ApplicantIntelligenceService service;
    private Job job;
    private JobRequirement requirement;
    private Application application;
    private ResumeAnalysis analysis;
    private ResumeAnalysisFact fact;
    private AtomicInteger matchCalls;
    private AtomicInteger runSaves;
    private AtomicReference<JobMatchRun> persistedRun;
    private AtomicReference<AiBatchMatchRequest> latestMatchRequest;
    private AtomicReference<ApplicationStatus> requestedStatus;

    @BeforeEach
    void setUp() {
        job = new Job();
        job.setId(7L);
        requirement = new JobRequirement();
        requirement.setId(11L);
        requirement.setJob(job);
        requirement.setType(RequirementType.SKILL);
        requirement.setDescription("Java");
        requirement.setMandatory(true);

        Resume resume = new Resume();
        resume.setId(19L);
        application = new Application();
        application.setId(23L);
        application.setResume(resume);
        application.setStatus(ApplicationStatus.SUBMITTED);
        application.setAppliedAt(LocalDateTime.parse("2026-01-01T12:00:00"));

        analysis = new ResumeAnalysis();
        analysis.setId(31L);
        analysis.setResume(resume);
        analysis.setStatus(ResumeAnalysisStatus.COMPLETED);
        analysis.setSourceSha256("c".repeat(64));

        fact = new ResumeAnalysisFact();
        fact.setId(41L);
        fact.setAnalysis(analysis);
        fact.setType(ResumeFactType.SKILL);
        fact.setExtractedValue("Java");
        fact.setNormalizedValue("java");
        fact.setEvidence("Java used in backend services");
        fact.setSourceSection("skills");
        fact.setReviewStatus(ResumeFactReviewStatus.UNREVIEWED);

        AtomicReference<JobMatchRun> storedRun = new AtomicReference<>();
        persistedRun = storedRun;
        runSaves = new AtomicInteger();
        matchCalls = new AtomicInteger();
        latestMatchRequest = new AtomicReference<>();
        requestedStatus = new AtomicReference<>();
        JobRequirementRepository requirements = proxy(JobRequirementRepository.class, (method, args) ->
                method.equals("findByJobIdOrderById") ? List.of(requirement) : null);
        ApplicationRepository applications = proxy(ApplicationRepository.class, (method, args) -> {
            if (method.equals("findAllByJobId")) return new PageImpl<>(List.of(application), PageRequest.of(0, 20), 1);
            if (method.equals("findAllByJobIdAndStatus")) {
                ApplicationStatus status = (ApplicationStatus) args[1];
                requestedStatus.set(status);
                List<Application> filtered = application.getStatus() == status ? List.of(application) : List.of();
                return new PageImpl<>(filtered, (PageRequest) args[2], filtered.size());
            }
            return null;
        });
        ResumeAnalysisFactRepository facts = proxy(ResumeAnalysisFactRepository.class, (method, args) ->
                method.equals("findAllByAnalysisIdOrderById") ? List.of(fact) : null);
        JobMatchRunRepository matchRuns = proxy(JobMatchRunRepository.class, (method, args) -> {
            if (method.equals("findByApplicationId")) return Optional.ofNullable(storedRun.get());
            if (method.equals("save")) {
                runSaves.incrementAndGet();
                storedRun.set((JobMatchRun) args[0]);
                return args[0];
            }
            return null;
        });
        ResumeIntelligenceService resumeIntelligence = new ResumeIntelligenceService(null, null, null) {
            @Override public ResumeAnalysis getByResume(Long resumeId) { return analysis; }
        };
        AiServiceClient aiClient = new AiServiceClient(RestClient.builder(), "http://127.0.0.1:8000", "test-only-token", 1000) {
            @Override public AiBatchMatchResponse matchBatch(AiBatchMatchRequest request) {
                latestMatchRequest.set(request);
                double score = matchCalls.incrementAndGet() == 1 ? 100.0 : 50.0;
                return result(score);
            }
        };
        HrJobAccessService access = new HrJobAccessService(null) {
            @Override public Job requireManageAccess(Long jobId) { return job; }
            @Override public Job requireApplicantReadAccess(Long jobId) { return job; }
        };
        service = new ApplicantIntelligenceService(requirements, applications, resumeIntelligence,
                facts, aiClient, matchRuns, new ObjectMapper(), access);
    }

    @Test
    void persistsMatchResultsReusesMatchingFingerprintAndRecomputesAfterCandidateCorrection() {
        var first = service.list(7L, 0, 20).getContent().get(0);
        assertEquals(100.0, first.observedEvidenceCoverage());
        assertEquals("MATCHED", first.requirements().get(0).status());
        assertEquals(1, runSaves.get());
        assertEquals("evidence-coverage-2", persistedRun.get().getAlgorithmVersion());

        var cached = service.list(7L, 0, 20).getContent().get(0);
        assertEquals(100.0, cached.observedEvidenceCoverage());
        assertEquals(1, matchCalls.get());

        fact.setReviewedValue("Java 21");
        fact.setReviewStatus(ResumeFactReviewStatus.CORRECTED);
        var recalculated = service.list(7L, 0, 20).getContent().get(0);
        assertEquals(50.0, recalculated.observedEvidenceCoverage());
        assertEquals(2, matchCalls.get());
        assertEquals(2, runSaves.get());
        assertEquals("Java 21", latestMatchRequest.get().candidates().get(0).facts().get(0).value());
        assertEquals("Java 21", latestMatchRequest.get().candidates().get(0).facts().get(0).evidence());
        assertEquals("candidate review", latestMatchRequest.get().candidates().get(0).facts().get(0).sourceSection());
    }

    @Test
    void filtersApplicantListByApplicationStatus() {
        application.setStatus(ApplicationStatus.SHORTLISTED);
        var result = service.list(7L, 0, 20, ApplicationStatus.SHORTLISTED);

        assertEquals(ApplicationStatus.SHORTLISTED, requestedStatus.get());
        assertEquals(1, result.getTotalElements());
        assertTrue(result.getContent().stream().allMatch(item -> item.applicationStatus() == ApplicationStatus.SHORTLISTED));
    }

    private AiBatchMatchResponse result(double score) {
        return new AiBatchMatchResponse(List.of(new AiBatchMatchResponse.Candidate(23L, score,
                "Observed requirement evidence coverage (not a probability of success)", "evidence-coverage-2",
                List.of(new AiMatchResponse.RequirementMatch(11L, "MATCHED",
                        "Java used in backend services", "skills", "RESUME", "Resume evidence contains Java.")))));
    }

    private static <T> T proxy(Class<T> repositoryType, RepositoryCall call) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> repositoryType.getSimpleName() + " test proxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            return call.invoke(method.getName(), args == null ? new Object[0] : args);
        };
        return repositoryType.cast(Proxy.newProxyInstance(repositoryType.getClassLoader(), new Class<?>[]{repositoryType}, handler));
    }

    @FunctionalInterface
    private interface RepositoryCall {
        Object invoke(String method, Object[] args);
    }
}
