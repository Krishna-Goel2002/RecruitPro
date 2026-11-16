package com.recruitpro.interview.service;

import com.recruitpro.ai.dto.AiInterviewKitRequest;
import com.recruitpro.ai.dto.AiInterviewKitResponse;
import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.ai.service.AiAuthoringCalls;
import com.recruitpro.ai.service.AiServiceClient;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.util.AnchorLevels;
import com.recruitpro.evidence.service.MatchRequestBuilder;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.repository.JobRequirementRepository;
import com.recruitpro.hr.service.HrJobAccessService;
import com.recruitpro.interview.dto.InterviewKitResponse;
import com.recruitpro.interview.dto.InterviewKitUpdateRequest;
import com.recruitpro.interview.dto.InterviewQuestion;
import com.recruitpro.interview.entity.InterviewKit;
import com.recruitpro.interview.repository.InterviewKitRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Interview kits (plan 5.6): one per job. Generation calls the AI service outside any transaction and replaces the
 * kit; edits replace the questions. GET answers 404 while a job has no kit (the HR portal shows "no kit yet").
 * Scorecards keep their own copy of question ids and requirement links, so editing a kit never changes a
 * submitted scorecard.
 */
@Service
public class InterviewKitService {

    static final int MAX_QUESTIONS = 60;
    static final int MAX_QUESTION = 2000;
    static final int MAX_FOLLOW_UPS = 10;
    static final int MAX_FOLLOW_UP = 1000;
    static final int MAX_ANCHOR = 1000;
    static final int MAX_ID = 40;
    static final String MISSING_ANCHOR = "Not described yet";
    public static final String GENERATED_BY_MANUAL = "manual";

    private static final String UNAVAILABLE = "Interview-kit drafting is temporarily unavailable; please try again later";
    private static final String REJECTED = "The job could not be turned into an interview kit; check its title and requirements";

    private final HrJobAccessService jobAccess;
    private final InterviewKitRepository kitRepository;
    private final JobRequirementRepository requirementRepository;
    private final AiServiceClient aiServiceClient;
    private final InterviewViews views;
    private final TransactionTemplate transactionTemplate;

    public InterviewKitService(HrJobAccessService jobAccess, InterviewKitRepository kitRepository,
                               JobRequirementRepository requirementRepository, AiServiceClient aiServiceClient,
                               InterviewViews views, PlatformTransactionManager transactionManager) {
        this.jobAccess = jobAccess;
        this.kitRepository = kitRepository;
        this.requirementRepository = requirementRepository;
        this.aiServiceClient = aiServiceClient;
        this.views = views;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** GET /api/v1/hr/jobs/{jobId}/interview-kit (ADMIN, HR_MANAGER, RECRUITER); 404 when there is no kit yet. */
    @Transactional(readOnly = true)
    public InterviewKitResponse get(Long jobId) {
        Job job = jobAccess.requireJobReadAccess(jobId);
        InterviewKit kit = kitRepository.findByJobId(job.getId())
                .orElseThrow(() -> new ResourceNotFoundException("This job has no interview kit yet"));
        return response(kit);
    }

    /**
     * POST /api/v1/hr/jobs/{jobId}/interview-kit/generate (ADMIN, HR_MANAGER): drafts questions for every requirement
     * (1-2 each) and replaces the job's kit. Not transactional around the AI call. 503 when the AI service is
     * unavailable, 400 when it rejects the input.
     */
    public InterviewKitResponse generate(Long jobId) {
        Job job = jobAccess.requireManageAccess(jobId);
        if (job.getStatus() == JobStatus.CANCELLED) {
            throw new BadRequestException("Cancelled jobs cannot get an interview kit");
        }
        List<JobRequirement> requirements = requirementRepository.findByJobIdOrderByDisplayOrderAscIdAsc(job.getId());
        Set<Long> requirementIds = new HashSet<>();
        List<AiMatchRequest.Requirement> aiRequirements = new ArrayList<>();
        for (JobRequirement requirement : requirements) {
            requirementIds.add(requirement.getId());
            if (aiRequirements.size() < AiMatchRequest.MAX_REQUIREMENTS) {
                aiRequirements.add(MatchRequestBuilder.requirement(requirement));
            }
        }
        String title = job.getTitle() == null ? "" : job.getTitle().trim();
        AiInterviewKitRequest request = new AiInterviewKitRequest(title.length() > 200 ? title.substring(0, 200) : title,
                List.copyOf(aiRequirements), null);
        AiInterviewKitResponse response = AiAuthoringCalls.call(() -> aiServiceClient.generateInterviewKit(request),
                UNAVAILABLE, REJECTED);
        List<InterviewQuestion> questions = fromAi(response.questions(), requirementIds);
        if (questions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        }
        String engine = response.engine() == null || response.engine().isBlank() ? "rules" : response.engine().trim();
        String generatedBy = engine.length() > MAX_ID ? engine.substring(0, MAX_ID) : engine;
        Long jobIdValue = job.getId();
        return transactionTemplate.execute(status -> save(jobIdValue, questions, generatedBy));
    }

    /** PUT /api/v1/hr/jobs/{jobId}/interview-kit (ADMIN, HR_MANAGER): replaces the questions (generatedBy "manual"). */
    @Transactional
    public InterviewKitResponse update(Long jobId, InterviewKitUpdateRequest request) {
        if (request == null || request.questions() == null) {
            throw new BadRequestException("questions are required");
        }
        Job job = jobAccess.requireManageAccess(jobId);
        if (job.getStatus() == JobStatus.CANCELLED) {
            throw new BadRequestException("Cancelled jobs cannot get an interview kit");
        }
        Set<Long> requirementIds = new HashSet<>();
        for (JobRequirement requirement : requirementRepository.findByJobIdOrderByDisplayOrderAscIdAsc(job.getId())) {
            requirementIds.add(requirement.getId());
        }
        return save(job.getId(), validate(request.questions(), requirementIds), GENERATED_BY_MANUAL);
    }

    // ---- internals ----

    private InterviewKitResponse save(Long jobId, List<InterviewQuestion> questions, String generatedBy) {
        InterviewKit kit = kitRepository.findByJobId(jobId).orElseGet(InterviewKit::new);
        kit.setJobId(jobId);
        kit.setQuestionsJson(views.writeQuestions(questions));
        kit.setGeneratedBy(generatedBy);
        return response(kitRepository.saveAndFlush(kit));
    }

    private InterviewKitResponse response(InterviewKit kit) {
        return new InterviewKitResponse(kit.getJobId(), views.questions(kit.getQuestionsJson()), kit.getGeneratedBy(),
                kit.getUpdatedAt());
    }

    /** Lenient mapping of AI questions: invalid links dropped, missing anchors marked, ids made unique. */
    static List<InterviewQuestion> fromAi(List<AiInterviewKitResponse.Question> generated, Set<Long> requirementIds) {
        List<InterviewQuestion> questions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (generated == null) {
            return questions;
        }
        for (AiInterviewKitResponse.Question question : generated) {
            if (question == null || question.question() == null || question.question().isBlank()) {
                continue;
            }
            if (questions.size() >= MAX_QUESTIONS) {
                break;
            }
            String id = uniqueId(question.id(), ids, questions.size() + 1);
            Long requirementId = question.requirementId() != null && requirementIds.contains(question.requirementId())
                    ? question.requirementId() : null;
            questions.add(new InterviewQuestion(id, requirementId, cut(question.question(), MAX_QUESTION),
                    followUps(question.followUps()), AnchorLevels.normalize(question.anchors(), MISSING_ANCHOR, MAX_ANCHOR)));
        }
        return questions;
    }

    /** Strict validation of an edited kit. */
    static List<InterviewQuestion> validate(List<InterviewQuestion> submitted, Set<Long> requirementIds) {
        if (submitted.size() > MAX_QUESTIONS) {
            throw new BadRequestException("An interview kit can have at most " + MAX_QUESTIONS + " questions");
        }
        List<InterviewQuestion> questions = new ArrayList<>(submitted.size());
        // Explicit ids first, so that generated ids for blank ones never collide with them.
        Set<String> ids = new HashSet<>();
        for (InterviewQuestion question : submitted) {
            if (question != null && question.id() != null && !question.id().isBlank()) {
                String id = question.id().trim();
                if (id.length() > MAX_ID) {
                    throw new BadRequestException("Question ids must be at most " + MAX_ID + " characters");
                }
                if (!ids.add(id)) {
                    throw new BadRequestException("Question ids must be unique within the kit");
                }
            }
        }
        for (InterviewQuestion question : submitted) {
            if (question == null || question.question() == null || question.question().isBlank()
                    || question.question().trim().length() > MAX_QUESTION) {
                throw new BadRequestException("Every question needs text of 1 to " + MAX_QUESTION + " characters");
            }
            String id = question.id() == null || question.id().isBlank()
                    ? uniqueId(null, ids, questions.size() + 1) : question.id().trim();
            if (question.requirementId() != null && !requirementIds.contains(question.requirementId())) {
                throw new BadRequestException("A question is linked to a requirement that is not part of this job");
            }
            List<String> followUps = question.followUps() == null ? List.of() : question.followUps();
            if (followUps.size() > MAX_FOLLOW_UPS) {
                throw new BadRequestException("A question can have at most " + MAX_FOLLOW_UPS + " follow-ups");
            }
            for (String followUp : followUps) {
                if (followUp != null && followUp.trim().length() > MAX_FOLLOW_UP) {
                    throw new BadRequestException("Follow-ups must be at most " + MAX_FOLLOW_UP + " characters");
                }
            }
            if (!AnchorLevels.complete(question.anchors(), MAX_ANCHOR)) {
                throw new BadRequestException("Describe all four anchors (1-4, at most " + MAX_ANCHOR
                        + " characters each) of every question");
            }
            questions.add(new InterviewQuestion(id, question.requirementId(), question.question().trim(),
                    followUps(followUps), AnchorLevels.normalize(question.anchors(), null, MAX_ANCHOR)));
        }
        return questions;
    }

    private static String uniqueId(String proposed, Set<String> used, int position) {
        String id = proposed == null ? "" : proposed.trim();
        if (!id.isEmpty() && id.length() <= MAX_ID && used.add(id)) {
            return id;
        }
        int counter = position;
        String generated = "q" + counter;
        while (!used.add(generated)) {
            counter++;
            generated = "q" + counter;
        }
        return generated;
    }

    private static List<String> followUps(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            if (value != null && !value.isBlank() && result.size() < MAX_FOLLOW_UPS) {
                result.add(cut(value, MAX_FOLLOW_UP));
            }
        }
        return result;
    }

    private static String cut(String value, int max) {
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
