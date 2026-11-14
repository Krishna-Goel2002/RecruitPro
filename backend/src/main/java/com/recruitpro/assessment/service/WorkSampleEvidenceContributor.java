package com.recruitpro.assessment.service;

import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.application.entity.Application;
import com.recruitpro.assessment.dto.CriterionScore;
import com.recruitpro.assessment.entity.WorkSampleAssignment;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;
import com.recruitpro.assessment.repository.WorkSampleAssignmentRepository;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.service.EvidenceContributor;
import com.recruitpro.hr.entity.JobRequirement;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier 3 evidence (D5, plan 5.6): for every SCORED assignment of the application, each rubric criterion linked to a
 * current requirement of the job and scored 3 or 4 yields one DEMONSTRATED fact {@code ws:<assignmentId>:r<requirementId>}
 * (the best criterion per requirement). The citation names the work sample and criterion, never the candidate or
 * their response. Read-only.
 */
@Component
@Order(10)
public class WorkSampleEvidenceContributor implements EvidenceContributor {

    public static final int DEMONSTRATED_MIN_SCORE = 3;
    public static final String SOURCE_SECTION = "work sample";

    private final WorkSampleAssignmentRepository assignmentRepository;
    private final WorkSampleViews views;

    public WorkSampleEvidenceContributor(WorkSampleAssignmentRepository assignmentRepository, WorkSampleViews views) {
        this.assignmentRepository = assignmentRepository;
        this.views = views;
    }

    @Override
    public List<AiMatchRequest.Fact> contribute(Application application, List<JobRequirement> requirements) {
        if (application == null || application.getId() == null || requirements == null || requirements.isEmpty()) {
            return List.of();
        }
        Map<Long, JobRequirement> byId = new HashMap<>();
        for (JobRequirement requirement : requirements) {
            byId.put(requirement.getId(), requirement);
        }
        List<AiMatchRequest.Fact> facts = new ArrayList<>();
        for (WorkSampleAssignment assignment : assignmentRepository.findAllForApplicationAndStatus(application.getId(),
                WorkSampleAssignmentStatus.SCORED)) {
            Map<Long, CriterionScore> best = new LinkedHashMap<>();
            for (CriterionScore score : views.scores(assignment.getScoresJson())) {
                if (score.requirementId() == null || score.score() == null || score.score() < DEMONSTRATED_MIN_SCORE
                        || !byId.containsKey(score.requirementId())) {
                    continue;
                }
                best.merge(score.requirementId(), score, (current, next) -> next.score() > current.score() ? next : current);
            }
            for (Map.Entry<Long, CriterionScore> entry : best.entrySet()) {
                JobRequirement requirement = byId.get(entry.getKey());
                CriterionScore score = entry.getValue();
                facts.add(EvidenceContributor.fact(factId(assignment.getId(), requirement.getId()),
                        requirement.getType().name(), requirement.getDescription(),
                        "Work sample \"" + assignment.getWorkSample().getTitle() + "\": \"" + score.criterion()
                                + "\" scored " + score.score() + " of 4",
                        SOURCE_SECTION, EvidenceTier.DEMONSTRATED));
            }
        }
        return facts;
    }

    /** {@code ws:<assignmentId>:r<requirementId>} (plan 6.1). */
    public static String factId(long assignmentId, long requirementId) {
        return "ws:" + assignmentId + ":r" + requirementId;
    }
}
