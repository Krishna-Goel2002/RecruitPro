package com.recruitpro.interview.service;

import com.recruitpro.ai.dto.AiMatchRequest;
import com.recruitpro.application.entity.Application;
import com.recruitpro.evidence.entity.EvidenceTier;
import com.recruitpro.evidence.service.EvidenceContributor;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.interview.dto.ScorecardRating;
import com.recruitpro.interview.entity.Interview;
import com.recruitpro.interview.entity.InterviewScorecard;
import com.recruitpro.interview.entity.InterviewStatus;
import com.recruitpro.interview.repository.InterviewRepository;
import com.recruitpro.interview.repository.InterviewScorecardRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier 4 evidence (D5, plan 5.6): for every COMPLETED interview of the application, each scorecard rating of 3 or 4
 * on a question linked to a current requirement yields one INTERVIEW_VERIFIED fact
 * {@code iv:<interviewId>:r<requirementId>} (the best rating per requirement). The citation never contains the
 * interviewer's notes or the candidate's identity. Read-only.
 */
@Component
@Order(20)
public class InterviewEvidenceContributor implements EvidenceContributor {

    public static final String SOURCE_SECTION = "interview";

    private final InterviewRepository interviewRepository;
    private final InterviewScorecardRepository scorecardRepository;
    private final InterviewViews views;

    public InterviewEvidenceContributor(InterviewRepository interviewRepository,
                                        InterviewScorecardRepository scorecardRepository, InterviewViews views) {
        this.interviewRepository = interviewRepository;
        this.scorecardRepository = scorecardRepository;
        this.views = views;
    }

    @Override
    public List<AiMatchRequest.Fact> contribute(Application application, List<JobRequirement> requirements) {
        if (application == null || application.getId() == null || requirements == null || requirements.isEmpty()) {
            return List.of();
        }
        List<Interview> interviews = new ArrayList<>(interviewRepository.findAllByApplicationIdAndStatus(application.getId(),
                InterviewStatus.COMPLETED));
        if (interviews.isEmpty()) {
            return List.of();
        }
        Map<Long, JobRequirement> byId = new HashMap<>();
        for (JobRequirement requirement : requirements) {
            byId.put(requirement.getId(), requirement);
        }
        List<Long> interviewIds = new ArrayList<>();
        for (Interview interview : interviews) {
            interviewIds.add(interview.getId());
        }
        Map<Long, InterviewScorecard> scorecards = new HashMap<>();
        for (InterviewScorecard scorecard : scorecardRepository.findAllByInterviewIdIn(interviewIds)) {
            scorecards.put(scorecard.getInterviewId(), scorecard);
        }
        List<AiMatchRequest.Fact> facts = new ArrayList<>();
        interviews.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        for (Interview interview : interviews) {
            InterviewScorecard scorecard = scorecards.get(interview.getId());
            if (scorecard == null) {
                continue;
            }
            Map<Long, ScorecardRating> best = new LinkedHashMap<>();
            for (ScorecardRating rating : views.ratings(scorecard.getRatingsJson())) {
                if (rating.requirementId() == null || rating.rating() == null
                        || rating.rating() < InterviewService.VERIFIED_MIN_RATING || !byId.containsKey(rating.requirementId())) {
                    continue;
                }
                best.merge(rating.requirementId(), rating, (current, next) -> next.rating() > current.rating() ? next : current);
            }
            for (Map.Entry<Long, ScorecardRating> entry : best.entrySet()) {
                JobRequirement requirement = byId.get(entry.getKey());
                facts.add(EvidenceContributor.fact(factId(interview.getId(), requirement.getId()),
                        requirement.getType().name(), requirement.getDescription(),
                        "Structured interview #" + interview.getId() + ": question " + entry.getValue().questionId()
                                + " rated " + entry.getValue().rating() + " of 4",
                        SOURCE_SECTION, EvidenceTier.INTERVIEW_VERIFIED));
            }
        }
        return facts;
    }

    /** {@code iv:<interviewId>:r<requirementId>} (plan 6.1). */
    public static String factId(long interviewId, long requirementId) {
        return "iv:" + interviewId + ":r" + requirementId;
    }
}
