package com.recruitpro.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.ai.dto.AiMatchResponse;
import com.recruitpro.ai.service.AiServiceClient;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.entity.ResumeFactReviewStatus;
import com.recruitpro.candidate.entity.ResumeFactType;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.evidence.service.MatchApplicationJobHandler;
import com.recruitpro.evidence.service.MatchingService;
import com.recruitpro.evidence.service.PreparedMatch;
import com.recruitpro.evidence.service.StoredMatchResults;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobMatchRun;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.hr.repository.JobMatchRunRepository;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.service.NonRetryableJobException;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MATCH_APPLICATION (D7, D8): the handler builds the v3 request, calls the (stubbed) AI service outside a
 * transaction and appends a current run; transient failures throw (retry), rejections fail permanently.
 */
@SpringBootTest
@ActiveProfiles("test")
class MatchApplicationJobHandlerIntegrationTest {

    @Autowired MatchingService matchingService;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired JobMatchRunRepository matchRuns;
    @Autowired JobRepository jobs;
    @Autowired CandidateClaimRepository claims;
    @Autowired ResumeAnalysisRepository analyses;
    @Autowired ResumeAnalysisFactRepository facts;
    @Autowired ObjectMapper objectMapper;

    Job job;
    JobRequirement java;
    JobRequirement docker;
    CandidateProfile candidate;
    ResumeAnalysis analysis;
    ResumeAnalysisFact javaFact;
    Application application;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        Organization organization = fixtures.organization("Matching Org");
        HRProfile owner = fixtures.hrMember(organization, "owner@match.test", Role.HR_MANAGER);
        job = fixtures.job(owner, "Backend Engineer", JobStatus.PUBLISHED);
        java = screening.requirement(job, RequirementType.SKILL, "Java", true, true);
        docker = screening.requirement(job, RequirementType.SKILL, "Docker", false, false);
        candidate = fixtures.candidate("match-candidate@cand.test");
        Resume resume = screening.resume(candidate, "resume.pdf");
        analysis = screening.analysis(resume, ResumeAnalysisStatus.COMPLETED);
        analysis.setTimelineJson("{\"totalMonths\":48,\"roles\":[],\"skillMonths\":{\"Java\":48}}");
        analysis = analyses.save(analysis);
        javaFact = screening.fact(analysis, ResumeFactType.SKILL, "Java");
        screening.fact(analysis, ResumeFactType.EMAIL, "match-candidate@cand.test");
        application = screening.application(candidate, job, resume, java.getCreatedAt());
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void runsAreAppendOnlyAndExactlyOneIsCurrent() throws Exception {
        StubClient client = new StubClient(() -> response(50.0, 50.0, true, List.of()));
        MatchApplicationJobHandler handler = new MatchApplicationJobHandler(matchingService, client);
        assertEquals(BackgroundJobType.MATCH_APPLICATION, handler.type());

        handler.handle(application.getId());

        AiMatchRequest sent = client.requests.get(0);
        assertEquals(List.of(java.getId(), docker.getId()), sent.requirements().stream().map(AiMatchRequest.Requirement::id).toList());
        assertTrue(sent.requirements().get(0).knockout());
        assertEquals(List.of("fact:" + javaFact.getId()), sent.facts().stream().map(AiMatchRequest.Fact::id).toList(),
                "contact facts are never sent");
        assertEquals("RESUME_STATED", sent.facts().get(0).tier());
        assertEquals(48, sent.timeline().path("totalMonths").asInt());
        List<JobMatchRun> runs = matchRuns.findAllByApplicationIdOrderByIdAsc(application.getId());
        assertEquals(1, runs.size());
        JobMatchRun first = runs.get(0);
        assertTrue(first.isCurrentRun());
        assertEquals(0, new BigDecimal("50.0").compareTo(first.getVerifiedCoverage()));
        assertEquals(0, new BigDecimal("50.0").compareTo(first.getObservedEvidenceCoverage()));
        assertEquals(Boolean.TRUE, first.getKnockoutsVerified());
        assertEquals("evidence-ladder-3", first.getAlgorithmVersion());
        assertEquals("INITIAL", first.getTriggerReason());
        assertEquals(1, first.getRequirementsVersion());
        assertEquals(2, StoredMatchResults.read(objectMapper, first.getRequirementResultsJson()).requirements().size());

        // The candidate corrects the extracted skill: the fact keeps its extracted value, the claim is sent separately.
        javaFact.setReviewStatus(ResumeFactReviewStatus.CORRECTED);
        javaFact.setReviewedValue("Java 21 and Docker");
        facts.save(javaFact);
        CandidateClaim claim = new CandidateClaim();
        claim.setCandidate(candidate);
        claim.setClaimType(ClaimType.SKILL);
        claim.setClaimValue("Java 21 and Docker");
        claim.setSourceFactId(javaFact.getId());
        claim = claims.save(claim);
        client.response = () -> response(50.0, 100.0, true, List.of());
        handler.handle(application.getId());

        AiMatchRequest second = client.requests.get(1);
        assertEquals("Java", second.facts().get(0).value());
        assertEquals("RESUME_STATED", second.facts().get(0).tier());
        assertEquals("claim:" + claim.getId(), second.facts().get(1).id());
        assertEquals("SELF_CLAIMED", second.facts().get(1).tier());
        runs = matchRuns.findAllByApplicationIdOrderByIdAsc(application.getId());
        assertEquals(2, runs.size());
        assertFalse(runs.get(0).isCurrentRun(), "earlier runs are kept but no longer current");
        assertTrue(runs.get(1).isCurrentRun());
        assertEquals("EVIDENCE_CHANGED", runs.get(1).getTriggerReason());
        assertEquals(0, new BigDecimal("100.0").compareTo(runs.get(1).getClaimedCoverage()));

        handler.handle(application.getId());
        assertEquals("RERUN", matchRuns.findAllByApplicationIdOrderByIdAsc(application.getId()).get(2).getTriggerReason());

        job.setRequirementsVersion(2);
        job = jobs.save(job);
        client.response = () -> response(100.0, 100.0, false, List.of(java.getId()));
        handler.handle(application.getId());
        runs = matchRuns.findAllByApplicationIdOrderByIdAsc(application.getId());
        JobMatchRun latest = runs.get(3);
        assertEquals("REQUIREMENTS_CHANGED", latest.getTriggerReason());
        assertEquals(2, latest.getRequirementsVersion());
        assertEquals(Boolean.FALSE, latest.getKnockoutsVerified());
        assertEquals(List.of(java.getId()), StoredMatchResults.read(objectMapper, latest.getRequirementResultsJson()).unverifiedKnockouts());
        assertEquals(1, runs.stream().filter(JobMatchRun::isCurrentRun).count());

        // A result computed for an older requirements version is stored but does not replace the current run.
        PreparedMatch stale = matchingService.prepare(application.getId());
        PreparedMatch olderVersion = new PreparedMatch(stale.applicationId(), stale.jobId(), stale.resumeAnalysisId(),
                stale.analysisCompletedAt(), 1, stale.request(), stale.fingerprint(), stale.preparedAt());
        JobMatchRun storedStale = matchingService.persist(olderVersion, response(0.0, 0.0, false, List.of()));
        assertFalse(storedStale.isCurrentRun());
        // Same version but inputs read before the current run's inputs (an overlapping job that finished late).
        PreparedMatch readEarlier = new PreparedMatch(stale.applicationId(), stale.jobId(), stale.resumeAnalysisId(),
                stale.analysisCompletedAt(), 2, stale.request(), stale.fingerprint(), latest.getCalculatedAt().minusSeconds(5));
        assertFalse(matchingService.persist(readEarlier, response(0.0, 0.0, false, List.of())).isCurrentRun());
        assertEquals(latest.getId(), matchRuns.findFirstByApplicationIdAndCurrentRunTrueOrderByIdDesc(application.getId())
                .orElseThrow().getId());

        // A late result for an application that was withdrawn meanwhile is dropped.
        PreparedMatch beforeWithdrawal = matchingService.prepare(application.getId());
        screening.withStatus(application, ApplicationStatus.WITHDRAWN);
        assertNull(matchingService.persist(beforeWithdrawal, response(90.0, 90.0, true, List.of())));
    }

    @Test
    void transientFailuresThrowForRetryAndRejectionsFailPermanentlyWithoutRuns() {
        assertThrows(IllegalStateException.class, () -> handler(() -> {
            throw new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
        }).handle(application.getId()));
        assertThrows(IllegalStateException.class, () -> handler(() -> {
            throw new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);
        }).handle(application.getId()));
        assertThrows(IllegalStateException.class, () -> handler(() -> {
            throw new ResourceAccessException("Connection refused");
        }).handle(application.getId()));
        assertThrows(IllegalStateException.class, () -> handler(() -> null).handle(application.getId()));
        assertThrows(NonRetryableJobException.class, () -> handler(() -> {
            throw new HttpClientErrorException(HttpStatus.UNPROCESSABLE_ENTITY);
        }).handle(application.getId()));
        assertThrows(NonRetryableJobException.class, () -> handler(() -> {
            throw new IllegalStateException(AiServiceClient.NOT_CONFIGURED_MESSAGE);
        }).handle(application.getId()));
        assertEquals(0, matchRuns.countByApplicationId(application.getId()));
    }

    @Test
    void nothingIsMatchedWithoutRequirementsWhileTheResumeIsParsedOrAfterWithdrawal() throws Exception {
        StubClient client = new StubClient(() -> response(10.0, 10.0, true, List.of()));
        MatchApplicationJobHandler handler = new MatchApplicationJobHandler(matchingService, client);

        analysis.setStatus(ResumeAnalysisStatus.PROCESSING);
        analysis = analyses.save(analysis);
        handler.handle(application.getId());
        analysis.setStatus(ResumeAnalysisStatus.COMPLETED);
        analysis = analyses.save(analysis);

        screening.withStatus(application, ApplicationStatus.WITHDRAWN);
        handler.handle(application.getId());
        handler.handle(999_999L);
        assertTrue(client.requests.isEmpty());
        assertEquals(0, matchRuns.countByApplicationId(application.getId()));
    }

    @Test
    void anUnreadableResumeIsMatchedOnlyWhenTheCandidateHasOtherEvidence() throws Exception {
        StubClient client = new StubClient(() -> response(0.0, 50.0, false, List.of(java.getId())));
        MatchApplicationJobHandler handler = new MatchApplicationJobHandler(matchingService, client);
        analysis.setStatus(ResumeAnalysisStatus.NEEDS_OCR);
        analysis = analyses.save(analysis);
        handler.handle(application.getId());
        assertTrue(client.requests.isEmpty());

        CandidateClaim claim = new CandidateClaim();
        claim.setCandidate(candidate);
        claim.setClaimType(ClaimType.SKILL);
        claim.setClaimValue("Java");
        claims.save(claim);
        handler.handle(application.getId());
        assertEquals(1, client.requests.size());
        assertEquals(List.of("SELF_CLAIMED"), client.requests.get(0).facts().stream().map(AiMatchRequest.Fact::tier).toList(),
                "resume facts of an unusable analysis are not sent");
        assertEquals(1, matchRuns.countByApplicationId(application.getId()));
    }

    private MatchApplicationJobHandler handler(Supplier<AiMatchResponse> response) {
        return new MatchApplicationJobHandler(matchingService, new StubClient(response));
    }

    private AiMatchResponse response(Double verified, Double claimed, boolean knockoutsVerified, List<Long> unverified) {
        return new AiMatchResponse("evidence-ladder-3", "Verified requirement evidence coverage (not a probability of success)",
                verified, claimed, knockoutsVerified, unverified, verified,
                List.of(ScreeningFixtures.matched(java.getId(), "RESUME_STATED", "RESUME"),
                        ScreeningFixtures.notObserved(docker.getId())));
    }

    /** AiServiceClient whose /match call is answered in memory. */
    static final class StubClient extends AiServiceClient {
        final List<AiMatchRequest> requests = new ArrayList<>();
        Supplier<AiMatchResponse> response;

        StubClient(Supplier<AiMatchResponse> response) {
            super(RestClient.builder(), "http://127.0.0.1:1", "test-only-token", 1000);
            this.response = response;
        }

        @Override
        public AiMatchResponse match(AiMatchRequest request) {
            requests.add(request);
            return response.get();
        }
    }
}
