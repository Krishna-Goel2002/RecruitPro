package com.recruitpro.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.ai.dto.AiResumeParseResponse;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import com.recruitpro.candidate.entity.ResumeFactType;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.candidate.service.ResumeIntelligenceService;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.jobs.entity.BackgroundJobStatus;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.repository.BackgroundJobRepository;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser v2 storage (spans, attributes, LINK facts, flags, timeline at tier RESUME_STATED) and the
 * "analysis completed" match trigger for every open application that uses the resume.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResumeAnalysisCompletionIntegrationTest {

    @Autowired ResumeIntelligenceService intelligenceService;
    @Autowired TestDataFactory fixtures;
    @Autowired ScreeningFixtures screening;
    @Autowired ResumeAnalysisRepository analyses;
    @Autowired ResumeAnalysisFactRepository facts;
    @Autowired BackgroundJobRepository backgroundJobs;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void completingAnAnalysisStoresParserV2DataAndQueuesMatchingForEveryOpenApplication() throws Exception {
        HRProfile owner = fixtures.hrMember(fixtures.organization("Parse Org"), "owner@parse.test", Role.HR_MANAGER);
        Job first = fixtures.job(owner, "Accountant", JobStatus.PUBLISHED);
        Job second = fixtures.job(owner, "Senior Accountant", JobStatus.PUBLISHED);
        CandidateProfile candidate = fixtures.candidate("parsed@cand.test");
        Resume resume = screening.resume(candidate, "cv.pdf");
        ResumeAnalysis analysis = screening.analysis(resume, ResumeAnalysisStatus.PROCESSING);
        Application open = screening.application(candidate, first, resume, UtcTime.now());
        Application withdrawn = screening.withStatus(screening.application(candidate, second, resume, UtcTime.now()),
                ApplicationStatus.WITHDRAWN);

        AiResumeParseResponse response = new AiResumeParseResponse("COMPLETED", null, "rule-parser-2", List.of(
                new AiResumeParseResponse.Fact("SKILL", "Tally", "tally", "Tally, GST, MS Excel", "skills", "page:1", 64, 69,
                        mapper.readTree("{\"skillId\":\"tally\",\"domain\":\"FINANCE\"}")),
                new AiResumeParseResponse.Fact("LINK", "https://linkedin.example.test/in/someone", "linkedin.example.test/in/someone",
                        "https://linkedin.example.test/in/someone", "contact", null, 10, 50, null),
                new AiResumeParseResponse.Fact("UNKNOWN", "ignored", "ignored", "ignored", "skills", null, null, null, null)),
                List.of(new AiResumeParseResponse.Flag("HIDDEN_TEXT", "28 characters of hidden text were excluded."),
                        new AiResumeParseResponse.Flag("not a code", "dropped")),
                mapper.readTree("{\"totalMonths\":95,\"roles\":[],\"skillMonths\":{\"Tally\":68}}"));

        intelligenceService.complete(analysis.getId(), "a".repeat(64), response);

        ResumeAnalysis stored = analyses.findById(analysis.getId()).orElseThrow();
        assertEquals(ResumeAnalysisStatus.COMPLETED, stored.getStatus());
        assertEquals(95, mapper.readTree(stored.getTimelineJson()).path("totalMonths").asInt());
        assertEquals(1, mapper.readTree(stored.getFlagsJson()).size());
        List<ResumeAnalysisFact> storedFacts = facts.findAllByAnalysisIdOrderById(analysis.getId());
        assertEquals(List.of(ResumeFactType.SKILL, ResumeFactType.LINK), storedFacts.stream().map(ResumeAnalysisFact::getType).toList());
        ResumeAnalysisFact tally = storedFacts.get(0);
        assertEquals(EvidenceTier.RESUME_STATED, tally.getEvidenceTier());
        assertEquals(64, tally.getSpanStart());
        assertEquals(69, tally.getSpanEnd());
        assertEquals("tally", mapper.readTree(tally.getAttributesJson()).path("skillId").asText());
        assertNull(storedFacts.get(1).getAttributesJson());

        assertEquals(BackgroundJobStatus.PENDING, backgroundJobs.findAllByJobTypeAndEntityIdOrderByIdAsc(
                BackgroundJobType.MATCH_APPLICATION, open.getId()).get(0).getStatus());
        assertTrue(backgroundJobs.findAllByJobTypeAndEntityIdOrderByIdAsc(BackgroundJobType.MATCH_APPLICATION, withdrawn.getId()).isEmpty());
    }
}
