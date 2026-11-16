package com.recruitpro.assessment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.assessment.dto.CandidateWorkSampleResponse;
import com.recruitpro.assessment.dto.CriterionScore;
import com.recruitpro.assessment.dto.RubricCriterion;
import com.recruitpro.assessment.dto.WorkSampleAssignmentResponse;
import com.recruitpro.assessment.dto.WorkSampleFileResponse;
import com.recruitpro.assessment.dto.WorkSampleResponse;
import com.recruitpro.assessment.entity.WorkSample;
import com.recruitpro.assessment.entity.WorkSampleAssignment;
import com.recruitpro.common.util.AnchorLevels;
import com.recruitpro.common.util.JsonText;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds work-sample responses and reads/writes the stored rubric and score JSON. Staff views carry the response
 * and scores; the candidate view never does, and hides the instructions until the candidate starts.
 * Callers run inside a transaction (lazy associations are read).
 */
@Component
public class WorkSampleViews {

    private static final TypeReference<List<RubricCriterion>> RUBRIC = new TypeReference<>() { };
    private static final TypeReference<List<CriterionScore>> SCORES = new TypeReference<>() { };

    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final WorkSampleFileService fileService;

    public WorkSampleViews(ObjectMapper objectMapper, UserRepository userRepository, WorkSampleFileService fileService) {
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.fileService = fileService;
    }

    public WorkSampleResponse workSample(WorkSample sample) {
        return new WorkSampleResponse(sample.getId(), sample.getJob().getId(), sample.getTitle(), sample.getInstructions(),
                sample.getTimeLimitMinutes(), rubric(sample.getRubricJson()), sample.getStatus(), sample.getCreatedAt(),
                sample.getUpdatedAt());
    }

    public WorkSampleAssignmentResponse assignment(WorkSampleAssignment assignment) {
        return assignments(List.of(assignment)).get(0);
    }

    /** Staff views of several assignments; scorer names are loaded in one query. */
    public List<WorkSampleAssignmentResponse> assignments(List<WorkSampleAssignment> assignments) {
        Set<Long> scorerIds = new HashSet<>();
        List<Long> assignmentIds = new ArrayList<>(assignments.size());
        for (WorkSampleAssignment assignment : assignments) {
            assignmentIds.add(assignment.getId());
            if (assignment.getScoredByUserId() != null) {
                scorerIds.add(assignment.getScoredByUserId());
            }
            if (assignment.getCancelledByUserId() != null) {
                scorerIds.add(assignment.getCancelledByUserId());
            }
        }
        Map<Long, List<WorkSampleFileResponse>> files = fileService.listFor(assignmentIds);
        Map<Long, String> names = new HashMap<>();
        if (!scorerIds.isEmpty()) {
            for (User user : userRepository.findAllById(scorerIds)) {
                names.put(user.getId(), user.getFullName());
            }
        }
        List<WorkSampleAssignmentResponse> responses = new ArrayList<>(assignments.size());
        for (WorkSampleAssignment assignment : assignments) {
            WorkSample sample = assignment.getWorkSample();
            responses.add(new WorkSampleAssignmentResponse(assignment.getId(), sample.getId(), sample.getTitle(),
                    assignment.getApplication().getId(), assignment.getStatus(), assignment.getAssignedAt(),
                    assignment.getDueAt(), assignment.getStartedAt(), assignment.getSubmittedAt(),
                    assignment.getResponseText(), assignment.getResponseUrl(), scores(assignment.getScoresJson()),
                    assignment.getTotalScore(), assignment.getScoredAt(),
                    assignment.getScoredByUserId() == null ? null : names.get(assignment.getScoredByUserId()),
                    files.getOrDefault(assignment.getId(), List.of()), assignment.getCancelledAt(),
                    assignment.getCancelReason(),
                    assignment.getCancelledByUserId() == null ? null : names.get(assignment.getCancelledByUserId())));
        }
        return responses;
    }

    /** Candidate view: no scores, no rubric; instructions only once started. */
    public CandidateWorkSampleResponse candidateView(WorkSampleAssignment assignment) {
        WorkSample sample = assignment.getWorkSample();
        boolean started = assignment.getStartedAt() != null;
        LocalDateTime deadline = started ? assignment.getStartedAt().plusMinutes(sample.getTimeLimitMinutes()) : null;
        return new CandidateWorkSampleResponse(assignment.getId(), sample.getJob().getTitle(), sample.getTitle(),
                started ? sample.getInstructions() : null, sample.getTimeLimitMinutes(), assignment.getStatus(),
                assignment.getDueAt(), assignment.getStartedAt(), assignment.getSubmittedAt(), deadline,
                fileService.list(assignment.getId()), assignment.getCancelledAt(), assignment.getCancelReason(),
                assignment.getApplication() == null ? null : assignment.getApplication().getId());
    }

    // ---- stored JSON ----

    /** The stored rubric; an empty list for missing or unreadable JSON. Anchors are returned keyed "1".."4". */
    public List<RubricCriterion> rubric(String json) {
        List<RubricCriterion> stored = readList(json, RUBRIC);
        List<RubricCriterion> result = new ArrayList<>(stored.size());
        for (RubricCriterion criterion : stored) {
            if (criterion != null) {
                result.add(new RubricCriterion(criterion.requirementId(), criterion.criterion(),
                        AnchorLevels.normalize(criterion.anchors(), "", Integer.MAX_VALUE)));
            }
        }
        return result;
    }

    public String writeRubric(List<RubricCriterion> rubric) {
        return JsonText.write(objectMapper, rubric == null ? List.of() : rubric);
    }

    /** The stored scores; an empty list when not scored yet. */
    public List<CriterionScore> scores(String json) {
        List<CriterionScore> stored = readList(json, SCORES);
        List<CriterionScore> result = new ArrayList<>(stored.size());
        for (CriterionScore score : stored) {
            if (score != null) {
                result.add(score);
            }
        }
        return result;
    }

    public String writeScores(List<CriterionScore> scores) {
        return JsonText.write(objectMapper, scores == null ? List.of() : scores);
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<T> values = objectMapper.readValue(json, type);
            return values == null ? List.of() : values;
        } catch (IOException ex) {
            return List.of();
        }
    }
}
