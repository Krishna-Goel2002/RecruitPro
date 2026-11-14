package com.recruitpro.interview.service;

import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.service.CandidateProfileService;
import com.recruitpro.interview.dto.CandidateInterviewResponse;
import com.recruitpro.interview.repository.InterviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** GET /api/v1/candidates/me/interviews: time, place and status only (no interviewer, ratings or notes). */
@Service
@Transactional
public class CandidateInterviewService {

    private final CandidateProfileService profileService;
    private final InterviewRepository interviewRepository;
    private final InterviewViews views;

    public CandidateInterviewService(CandidateProfileService profileService, InterviewRepository interviewRepository,
                                     InterviewViews views) {
        this.profileService = profileService;
        this.interviewRepository = interviewRepository;
        this.views = views;
    }

    /** Not read-only: the candidate profile may be provisioned on first use. */
    public List<CandidateInterviewResponse> listMine() {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        return interviewRepository.findAllForCandidate(candidate.getId()).stream().map(views::candidateView).toList();
    }
}
