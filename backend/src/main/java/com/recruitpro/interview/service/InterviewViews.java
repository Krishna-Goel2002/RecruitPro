package com.recruitpro.interview.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.application.entity.Application;
import com.recruitpro.common.util.AnchorLevels;
import com.recruitpro.common.util.JsonText;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.service.ApplicantQueryService;
import com.recruitpro.interview.dto.CandidateInterviewResponse;
import com.recruitpro.interview.dto.InterviewQuestion;
import com.recruitpro.interview.dto.InterviewResponse;
import com.recruitpro.interview.dto.ScorecardRating;
import com.recruitpro.interview.dto.ScorecardResponse;
import com.recruitpro.interview.entity.Interview;
import com.recruitpro.interview.entity.InterviewScorecard;
import com.recruitpro.interview.repository.InterviewScorecardRepository;
import com.recruitpro.interview.repository.InterviewScorecardRevisionRepository;
import com.recruitpro.interview.repository.RevisionCountRow;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds interview responses and reads/writes the stored kit and rating JSON. Staff views follow blind review for
 * the candidate's name; candidate views never carry interviewer, ratings, notes or recommendation. Callers run
 * inside a transaction (lazy associations are read).
 */
@Component
public class InterviewViews {

    private static final TypeReference<List<InterviewQuestion>> QUESTIONS = new TypeReference<>() { };
    private static final TypeReference<List<ScorecardRating>> RATINGS = new TypeReference<>() { };

    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final InterviewScorecardRepository scorecardRepository;
    private final InterviewScorecardRevisionRepository revisionRepository;

    public InterviewViews(ObjectMapper objectMapper, UserRepository userRepository,
                          OrganizationRepository organizationRepository, InterviewScorecardRepository scorecardRepository,
                          InterviewScorecardRevisionRepository revisionRepository) {
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.scorecardRepository = scorecardRepository;
        this.revisionRepository = revisionRepository;
    }

    public InterviewResponse staffView(Interview interview) {
        return staffViews(List.of(interview)).get(0);
    }

    /** Staff views; interviewer names and scorecards are loaded in batch. */
    public List<InterviewResponse> staffViews(List<Interview> interviews) {
        if (interviews.isEmpty()) {
            return List.of();
        }
        Set<Long> interviewerIds = new HashSet<>();
        List<Long> interviewIds = new ArrayList<>(interviews.size());
        for (Interview interview : interviews) {
            interviewerIds.add(interview.getInterviewerUserId());
            interviewIds.add(interview.getId());
        }
        Map<Long, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(interviewerIds)) {
            names.put(user.getId(), user.getFullName());
        }
        Map<Long, InterviewScorecard> scorecards = new HashMap<>();
        List<Long> scorecardIds = new ArrayList<>();
        for (InterviewScorecard scorecard : scorecardRepository.findAllByInterviewIdIn(interviewIds)) {
            scorecards.put(scorecard.getInterviewId(), scorecard);
            scorecardIds.add(scorecard.getId());
        }
        Map<Long, Long> revisionCounts = new HashMap<>();
        if (!scorecardIds.isEmpty()) {
            for (RevisionCountRow row : revisionRepository.countByScorecardIds(scorecardIds)) {
                revisionCounts.put(row.scorecardId(), row.count());
            }
        }
        Map<Long, Boolean> blindByOrganization = new HashMap<>();
        List<InterviewResponse> responses = new ArrayList<>(interviews.size());
        for (Interview interview : interviews) {
            Application application = interview.getApplication();
            Job job = application.getJob();
            InterviewScorecard scorecard = scorecards.get(interview.getId());
            responses.add(new InterviewResponse(interview.getId(), application.getId(), job.getId(), job.getTitle(),
                    candidateName(application, job, blindByOrganization), interview.getInterviewerUserId(),
                    names.getOrDefault(interview.getInterviewerUserId(), "Former staff member"),
                    interview.getScheduledAt(), interview.getDurationMinutes(), interview.getLocation(),
                    interview.getStatus(), scorecard == null ? null
                    : scorecard(scorecard, revisionCounts.getOrDefault(scorecard.getId(), 0L))));
        }
        return responses;
    }

    /** Candidate view: time, place and status only. */
    public CandidateInterviewResponse candidateView(Interview interview) {
        return new CandidateInterviewResponse(interview.getId(), interview.getApplication().getJob().getTitle(),
                interview.getScheduledAt(), interview.getDurationMinutes(), interview.getLocation(), interview.getStatus());
    }

    public ScorecardResponse scorecard(InterviewScorecard scorecard) {
        return scorecard(scorecard, revisionRepository.countByScorecardId(scorecard.getId()));
    }

    public ScorecardResponse scorecard(InterviewScorecard scorecard, long revisionCount) {
        return new ScorecardResponse(scorecard.getId(), ratings(scorecard.getRatingsJson()), scorecard.getRecommendation(),
                scorecard.getSummary(), scorecard.getSubmittedAt(), scorecard.getUpdatedAt(), (int) revisionCount);
    }

    /** A stored revision snapshot (a ScorecardResponse as JSON); null when unreadable. */
    public ScorecardResponse readScorecardSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ScorecardResponse.class);
        } catch (IOException ex) {
            return null;
        }
    }

    /** Blind label until the identity is revealed (D4), then the full name. */
    String candidateName(Application application, Job job, Map<Long, Boolean> blindByOrganization) {
        Boolean blind = blindByOrganization.computeIfAbsent(job.getOrganizationId(), organizationId ->
                organizationRepository.findById(organizationId).map(Organization::isBlindReview).orElse(true));
        if (!ApplicantQueryService.isRevealed(application.getIdentityRevealedAt(), Boolean.TRUE.equals(blind))) {
            return ApplicantQueryService.blindName(application.getId());
        }
        User user = application.getCandidate() == null ? null : application.getCandidate().getUser();
        String name = user == null ? "" : user.getFullName();
        return name.isBlank() ? ApplicantQueryService.blindName(application.getId()) : name;
    }

    // ---- stored JSON ----

    /** Stored kit questions; anchors keyed "1".."4"; null lists become empty. */
    public List<InterviewQuestion> questions(String json) {
        List<InterviewQuestion> stored = readList(json, QUESTIONS);
        List<InterviewQuestion> result = new ArrayList<>(stored.size());
        for (InterviewQuestion question : stored) {
            if (question != null) {
                result.add(new InterviewQuestion(question.id(), question.requirementId(), question.question(),
                        question.followUps() == null ? List.of() : question.followUps(),
                        AnchorLevels.normalize(question.anchors(), "", Integer.MAX_VALUE)));
            }
        }
        return result;
    }

    public String writeQuestions(List<InterviewQuestion> questions) {
        return JsonText.write(objectMapper, questions == null ? List.of() : questions);
    }

    public List<ScorecardRating> ratings(String json) {
        List<ScorecardRating> stored = readList(json, RATINGS);
        List<ScorecardRating> result = new ArrayList<>(stored.size());
        for (ScorecardRating rating : stored) {
            if (rating != null) {
                result.add(rating);
            }
        }
        return result;
    }

    public String writeRatings(List<ScorecardRating> ratings) {
        return JsonText.write(objectMapper, ratings == null ? List.of() : ratings);
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
