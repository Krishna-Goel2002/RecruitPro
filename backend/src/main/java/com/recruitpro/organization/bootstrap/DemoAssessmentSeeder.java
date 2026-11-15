package com.recruitpro.organization.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationDecisionAction;
import com.recruitpro.application.entity.ApplicationDecisionHistory;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.assessment.dto.CriterionScore;
import com.recruitpro.assessment.dto.RubricCriterion;
import com.recruitpro.assessment.entity.WorkSample;
import com.recruitpro.assessment.entity.WorkSampleAssignment;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;
import com.recruitpro.assessment.entity.WorkSampleStatus;
import com.recruitpro.assessment.repository.WorkSampleAssignmentRepository;
import com.recruitpro.assessment.repository.WorkSampleRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.candidate.service.ResumeIntelligenceService;
import com.recruitpro.candidate.storage.ResumeStorage;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.common.util.JsonText;
import com.recruitpro.evidence.service.MatchTriggerService;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.repository.JobRequirementRepository;
import com.recruitpro.interview.dto.InterviewQuestion;
import com.recruitpro.interview.dto.ScorecardRating;
import com.recruitpro.interview.entity.Interview;
import com.recruitpro.interview.entity.InterviewKit;
import com.recruitpro.interview.entity.InterviewScorecard;
import com.recruitpro.interview.entity.InterviewStatus;
import com.recruitpro.interview.entity.Recommendation;
import com.recruitpro.interview.repository.InterviewKitRepository;
import com.recruitpro.interview.repository.InterviewRepository;
import com.recruitpro.interview.repository.InterviewScorecardRepository;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Demo data for the assessment features (WP-PLAT, plan v2 Section 6.8), called by {@link DemoDataSeeder}: for the job
 * "Backend Engineer (Java)" an interview kit, an ACTIVE work sample, the demo candidate's application (INTERVIEWING,
 * identity revealed, with a generated one-page resume when the candidate has none), a SCORED work-sample assignment,
 * a COMPLETED interview with a submitted scorecard and a SCHEDULED interview in three days. Every piece is created
 * only when missing, so the seeder can run on every start-up.
 */
@Service
public class DemoAssessmentSeeder {

    static final String JOB_TITLE = "Backend Engineer (Java)";
    static final String WORK_SAMPLE_TITLE = "Design a REST endpoint with Spring Boot";
    private static final Logger log = LoggerFactory.getLogger(DemoAssessmentSeeder.class);

    @PersistenceContext
    private EntityManager entityManager;

    private final UserRepository userRepository;
    private final CandidateProfileRepository profileRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeStorage resumeStorage;
    private final ResumeIntelligenceService intelligenceService;
    private final ApplicationRepository applicationRepository;
    private final ApplicationDecisionHistoryRepository historyRepository;
    private final JobRequirementRepository requirementRepository;
    private final InterviewKitRepository kitRepository;
    private final WorkSampleRepository workSampleRepository;
    private final WorkSampleAssignmentRepository assignmentRepository;
    private final InterviewRepository interviewRepository;
    private final InterviewScorecardRepository scorecardRepository;
    private final MatchTriggerService matchTriggerService;
    private final ObjectMapper objectMapper;

    public DemoAssessmentSeeder(UserRepository userRepository, CandidateProfileRepository profileRepository,
                                ResumeRepository resumeRepository, ResumeStorage resumeStorage,
                                ResumeIntelligenceService intelligenceService, ApplicationRepository applicationRepository,
                                ApplicationDecisionHistoryRepository historyRepository,
                                JobRequirementRepository requirementRepository, InterviewKitRepository kitRepository,
                                WorkSampleRepository workSampleRepository, WorkSampleAssignmentRepository assignmentRepository,
                                InterviewRepository interviewRepository, InterviewScorecardRepository scorecardRepository,
                                MatchTriggerService matchTriggerService, ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.resumeRepository = resumeRepository;
        this.resumeStorage = resumeStorage;
        this.intelligenceService = intelligenceService;
        this.applicationRepository = applicationRepository;
        this.historyRepository = historyRepository;
        this.requirementRepository = requirementRepository;
        this.kitRepository = kitRepository;
        this.workSampleRepository = workSampleRepository;
        this.assignmentRepository = assignmentRepository;
        this.interviewRepository = interviewRepository;
        this.scorecardRepository = scorecardRepository;
        this.matchTriggerService = matchTriggerService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void seed(Long organizationId) {
        Job job = findJob(organizationId);
        User hrManager = userRepository.findByEmail(DemoDataSeeder.HR_EMAIL).orElse(null);
        User interviewer = userRepository.findByEmail(DemoDataSeeder.INTERVIEWER_EMAIL).orElse(null);
        User candidateUser = userRepository.findByEmail(DemoDataSeeder.CANDIDATE_EMAIL).orElse(null);
        if (job == null || hrManager == null || interviewer == null || candidateUser == null) {
            log.warn("Demo assessments not seeded: job '{}' or a demo account is missing", JOB_TITLE);
            return;
        }
        CandidateProfile candidate = profileRepository.findByUserId(candidateUser.getId()).orElse(null);
        if (candidate == null) {
            log.warn("Demo assessments not seeded: {} has no candidate profile", DemoDataSeeder.CANDIDATE_EMAIL);
            return;
        }
        Map<String, Long> requirements = requirementIds(job);
        Long javaReq = requirements.get("java");
        Long spring = requirements.get("spring boot");
        Long postgres = requirements.get("postgresql");
        Long backend = requirements.get("backend development");

        List<InterviewQuestion> kit = kit(javaReq, spring, postgres, backend);
        if (kitRepository.findByJobId(job.getId()).isEmpty()) {
            InterviewKit row = new InterviewKit();
            row.setJobId(job.getId());
            row.setQuestionsJson(JsonText.write(objectMapper, kit));
            row.setGeneratedBy("manual");
            kitRepository.save(row);
        }
        WorkSample sample = workSample(job, hrManager, spring, postgres);
        Application application = application(job, candidate, hrManager);
        if (application.getStatus() != ApplicationStatus.INTERVIEWING && application.getStatus() != ApplicationStatus.SHORTLISTED) {
            log.info("Demo application is {}; work sample and interviews not seeded", application.getStatus());
            return;
        }
        boolean changed = false;
        if (!assignmentRepository.existsByWorkSampleIdAndApplicationId(sample.getId(), application.getId())) {
            scoredAssignment(sample, application, hrManager, spring, postgres);
            changed = true;
        }
        if (interviewRepository.findAllForApplication(application.getId()).isEmpty()) {
            interviews(application, interviewer, hrManager, javaReq, spring, postgres, backend);
            changed = true;
        }
        if (changed) {
            matchTriggerService.requestMatch(application.getId());
        }
        log.info("Demo assessments ready for '{}': kit, scored work sample, completed and scheduled interview", JOB_TITLE);
    }

    private Job findJob(Long organizationId) {
        List<Job> jobs = entityManager.createQuery(
                        "select j from Job j where j.organizationId = :organizationId and j.title = :title order by j.id", Job.class)
                .setParameter("organizationId", organizationId)
                .setParameter("title", JOB_TITLE)
                .setMaxResults(1)
                .getResultList();
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    private Map<String, Long> requirementIds(Job job) {
        Map<String, Long> ids = new java.util.HashMap<>();
        for (JobRequirement requirement : requirementRepository.findByJobIdOrderById(job.getId())) {
            if (requirement.getDescription() != null) {
                ids.putIfAbsent(requirement.getDescription().trim().toLowerCase(java.util.Locale.ROOT), requirement.getId());
            }
        }
        return ids;
    }

    private static List<InterviewQuestion> kit(Long javaReq, Long spring, Long postgres, Long backend) {
        List<InterviewQuestion> questions = new ArrayList<>();
        questions.add(new InterviewQuestion("q1", javaReq,
                "Tell us about a Java service you built recently. How did you structure it and why?",
                List.of("What would you change today?", "How did you test it?"),
                anchors("Cannot describe a concrete service", "Describes a service but not the design choices",
                        "Explains the structure and the trade-offs", "Explains trade-offs, alternatives and lessons learned")));
        questions.add(new InterviewQuestion("q2", spring,
                "How do you handle validation and errors in a Spring Boot REST API?",
                List.of("Where do you translate exceptions into HTTP responses?"),
                anchors("No clear approach", "Validates input but errors are inconsistent",
                        "Bean Validation plus one central exception handler", "Consistent error contract, documented and tested")));
        questions.add(new InterviewQuestion("q3", postgres,
                "A query on a large PostgreSQL table became slow. How do you investigate?",
                List.of("How do you read an execution plan?"),
                anchors("Guesses without measuring", "Adds indexes without checking the plan",
                        "Uses EXPLAIN ANALYZE and targeted indexes", "Measures, fixes, and prevents regressions")));
        questions.add(new InterviewQuestion("q4", backend,
                "Describe an incident in a backend system you were responsible for.",
                List.of("What did you change afterwards?"),
                anchors("No example", "Example without own contribution", "Clear own contribution and fix",
                        "Contribution, root cause and lasting prevention")));
        return questions;
    }

    private WorkSample workSample(Job job, User hrManager, Long spring, Long postgres) {
        for (WorkSample existing : workSampleRepository.findAllByJobIdOrderByIdAsc(job.getId())) {
            if (WORK_SAMPLE_TITLE.equals(existing.getTitle())) {
                return existing;
            }
        }
        List<RubricCriterion> rubric = List.of(
                new RubricCriterion(spring, "REST design with Spring Boot",
                        anchors("Endpoint does not work", "Works but ignores validation and errors",
                                "Clear resource design with validation and error handling",
                                "Clean design, consistent errors and tests")),
                new RubricCriterion(postgres, "Data model and queries",
                        anchors("No persistence", "Persistence without constraints",
                                "Sensible schema with constraints and indexes",
                                "Schema, indexes and migration explained with trade-offs")));
        WorkSample sample = new WorkSample();
        sample.setJob(job);
        sample.setTitle(WORK_SAMPLE_TITLE);
        sample.setInstructions("Design a REST endpoint POST /orders that validates its input, stores the order in "
                + "PostgreSQL and returns consistent errors. Describe your schema and how you would test the endpoint. "
                + "You may attach a ZIP or PDF.");
        sample.setTimeLimitMinutes(60);
        sample.setRubricJson(JsonText.write(objectMapper, rubric));
        sample.setStatus(WorkSampleStatus.ACTIVE);
        sample.setCreatedByUserId(hrManager.getId());
        return workSampleRepository.save(sample);
    }

    private Application application(Job job, CandidateProfile candidate, User hrManager) {
        List<Application> existing = entityManager.createQuery(
                        "select a from Application a where a.candidate.id = :candidateId and a.job.id = :jobId", Application.class)
                .setParameter("candidateId", candidate.getId())
                .setParameter("jobId", job.getId())
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        Resume resume = resume(candidate);
        LocalDateTime now = UtcTime.now();
        Application application = new Application();
        application.setCandidate(candidate);
        application.setJob(job);
        application.setResume(resume);
        application.setCoverLetter("I enjoy building reliable Java services and would like to help build yours.");
        application.setAppliedAt(now.minusDays(10));
        application.setStatus(ApplicationStatus.INTERVIEWING);
        application.setIdentityRevealedAt(now.minusDays(8));
        application.setIdentityRevealedById(hrManager.getId());
        application.setStatusChangedAt(now.minusDays(6));
        Application saved = applicationRepository.save(application);
        history(saved, hrManager, ApplicationStatus.SUBMITTED, ApplicationStatus.SHORTLISTED, ApplicationDecisionAction.SHORTLIST,
                "Strong Java and Spring Boot background (demo data)");
        history(saved, hrManager, ApplicationStatus.SHORTLISTED, ApplicationStatus.INTERVIEWING,
                ApplicationDecisionAction.MOVE_TO_INTERVIEW, null);
        return saved;
    }

    private void history(Application application, User actor, ApplicationStatus from, ApplicationStatus to,
                         ApplicationDecisionAction action, String comment) {
        ApplicationDecisionHistory row = new ApplicationDecisionHistory();
        row.setApplication(application);
        row.setActor(actor);
        row.setPreviousStatus(from);
        row.setNewStatus(to);
        row.setActionType(action);
        row.setComment(comment);
        historyRepository.save(row);
    }

    /** The candidate's latest resume, or a generated one-page PDF (queued for parsing like an upload). */
    private Resume resume(CandidateProfile candidate) {
        List<Resume> resumes = resumeRepository.findAllByCandidateIdOrderByUploadedAtDescIdDesc(candidate.getId());
        if (!resumes.isEmpty()) {
            return resumes.get(0);
        }
        byte[] pdf = DemoPdf.onePage(List.of(
                "Casey Candidate",
                "Backend Engineer",
                "Skills: Java, Spring Boot, PostgreSQL, Docker, REST APIs, JUnit",
                "Experience",
                "Software Engineer, Example Tech (January 2020 - Present)",
                "Built Java and Spring Boot services backed by PostgreSQL for order processing.",
                "Education",
                "B.Tech in Computer Science, 2019"));
        String key = UUID.randomUUID().toString();
        try {
            resumeStorage.store(key, pdf);
        } catch (IOException ex) {
            throw new IllegalStateException("The demo resume could not be stored", ex);
        }
        Resume resume = new Resume();
        resume.setCandidate(candidate);
        resume.setStorageKey(key);
        resume.setOriginalFilename("casey-candidate-resume.pdf");
        resume.setFileExtension("pdf");
        resume.setContentType("application/pdf");
        resume.setFileSize(pdf.length);
        resume.setUploadedAt(UtcTime.now().minusDays(11));
        Resume saved = resumeRepository.save(resume);
        intelligenceService.createFor(saved);
        return saved;
    }

    private void scoredAssignment(WorkSample sample, Application application, User hrManager, Long spring, Long postgres) {
        LocalDateTime now = UtcTime.now();
        List<CriterionScore> scores = List.of(
                new CriterionScore(spring, "REST design with Spring Boot", 4, "Clean resource design and error contract"),
                new CriterionScore(postgres, "Data model and queries", 3, "Good schema; index choice explained briefly"));
        WorkSampleAssignment assignment = new WorkSampleAssignment();
        assignment.setWorkSample(sample);
        assignment.setApplication(application);
        assignment.setStatus(WorkSampleAssignmentStatus.SCORED);
        assignment.setAssignedAt(now.minusDays(6));
        assignment.setStartedAt(now.minusDays(5));
        assignment.setSubmittedAt(now.minusDays(5).plusMinutes(52));
        assignment.setResponseText("POST /orders validates the body with Bean Validation, stores the order in an orders "
                + "table (primary key, foreign key to customers, unique external reference) and maps errors to a single "
                + "JSON error shape in a controller advice. Tests: MockMvc for validation and errors, Testcontainers for "
                + "the repository.");
        assignment.setScoresJson(JsonText.write(objectMapper, scores));
        assignment.setTotalScore(new BigDecimal("3.50"));
        assignment.setScoredByUserId(hrManager.getId());
        assignment.setScoredAt(now.minusDays(4));
        assignmentRepository.save(assignment);
    }

    private void interviews(Application application, User interviewer, User hrManager, Long javaReq, Long spring,
                            Long postgres, Long backend) {
        LocalDateTime now = UtcTime.now();
        Interview completed = new Interview();
        completed.setApplication(application);
        completed.setInterviewerUserId(interviewer.getId());
        completed.setScheduledAt(now.minusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0));
        completed.setDurationMinutes(45);
        completed.setLocation("Video call");
        completed.setStatus(InterviewStatus.COMPLETED);
        completed.setCreatedByUserId(hrManager.getId());
        Interview savedCompleted = interviewRepository.save(completed);
        List<ScorecardRating> ratings = List.of(
                new ScorecardRating("q1", javaReq, 3, "Clear explanation of a payment service"),
                new ScorecardRating("q2", spring, 4, "Central exception handler with a documented error contract"),
                new ScorecardRating("q3", postgres, 3, "Uses EXPLAIN ANALYZE; good index reasoning"),
                new ScorecardRating("q4", backend, 2, "Example lacked root-cause analysis"));
        InterviewScorecard scorecard = new InterviewScorecard();
        scorecard.setInterviewId(savedCompleted.getId());
        scorecard.setRatingsJson(JsonText.write(objectMapper, ratings));
        scorecard.setRecommendation(Recommendation.YES);
        scorecard.setSummary("Solid Java and Spring Boot skills; probe incident handling in the next round.");
        scorecard.setSubmittedAt(now.minusDays(2).withHour(11).withMinute(0).withSecond(0).withNano(0));
        scorecardRepository.save(scorecard);

        Interview scheduled = new Interview();
        scheduled.setApplication(application);
        scheduled.setInterviewerUserId(interviewer.getId());
        scheduled.setScheduledAt(now.plusDays(3).withHour(9).withMinute(30).withSecond(0).withNano(0));
        scheduled.setDurationMinutes(60);
        scheduled.setLocation("Bengaluru office, room 3B");
        scheduled.setStatus(InterviewStatus.SCHEDULED);
        scheduled.setCreatedByUserId(hrManager.getId());
        interviewRepository.save(scheduled);
    }

    private static Map<String, String> anchors(String one, String two, String three, String four) {
        return Map.of("1", one, "2", two, "3", three, "4", four);
    }

    /** A minimal single-page PDF (Helvetica, ASCII text) with a correct cross-reference table. */
    static final class DemoPdf {

        private DemoPdf() {
        }

        static byte[] onePage(List<String> lines) {
            StringBuilder content = new StringBuilder("BT /F1 11 Tf 72 740 Td 16 TL\n");
            for (String line : lines) {
                content.append('(').append(escape(line)).append(") Tj T*\n");
            }
            content.append("ET\n");
            String stream = content.toString();
            List<String> objects = List.of(
                    "<< /Type /Catalog /Pages 2 0 R >>",
                    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                    "<< /Length " + stream.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n" + stream + "endstream");
            StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
            List<Integer> offsets = new ArrayList<>();
            for (int i = 0; i < objects.size(); i++) {
                offsets.add(pdf.length());
                pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
            }
            int xref = pdf.length();
            pdf.append("xref\n0 ").append(objects.size() + 1).append('\n').append("0000000000 65535 f \n");
            for (Integer offset : offsets) {
                pdf.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset));
            }
            pdf.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                    .append(xref).append("\n%%EOF\n");
            return pdf.toString().getBytes(StandardCharsets.US_ASCII);
        }

        private static String escape(String text) {
            StringBuilder out = new StringBuilder(text.length());
            for (char c : text.toCharArray()) {
                if (c == '(' || c == ')' || c == '\\') {
                    out.append('\\');
                }
                out.append(c < 0x80 ? c : '?');
            }
            return out.toString();
        }
    }
}
