package com.recruitpro.application.service;

import com.recruitpro.application.dto.ApplicationCreateRequest;
import com.recruitpro.application.dto.ApplicationResponse;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.candidate.service.CandidateProfileService;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.DuplicateResourceException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.hr.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class ApplicationService {
    private final CandidateProfileService profileService;
    private final ApplicationRepository applicationRepository;
    private final ResumeRepository resumeRepository;
    private final JobRepository jobRepository;

    public ApplicationResponse apply(Long jobId, ApplicationCreateRequest request) {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        if (request == null || request.resumeId() == null) throw new BadRequestException("An uploaded resume is required to apply");
        Resume resume = resumeRepository.findByIdAndCandidateId(request.resumeId(), candidate.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Resume not found"));
        Job job = jobRepository.findById(jobId).orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));
        if (job.getStatus() != JobStatus.PUBLISHED || (job.getDeadline() != null && !job.getDeadline().isAfter(LocalDateTime.now())))
            throw new BadRequestException("Applications are accepted only for published jobs with an open deadline");
        if (applicationRepository.existsByCandidateIdAndJobId(candidate.getId(), jobId))
            throw new DuplicateResourceException("You have already applied to this job");
        Application application = new Application();
        application.setCandidate(candidate); application.setJob(job); application.setResume(resume);
        application.setStatus(ApplicationStatus.SUBMITTED); application.setAppliedAt(LocalDateTime.now());
        application.setCoverLetter(request.coverLetter() == null ? null : request.coverLetter().trim());
        try { return ApplicationResponse.from(applicationRepository.saveAndFlush(application)); }
        catch (DataIntegrityViolationException ex) { throw new DuplicateResourceException("You have already applied to this job"); }
    }

    @Transactional(readOnly = true)
    public PageResponse<ApplicationResponse> listMine(int page, int size) {
        validatePage(page, size);
        CandidateProfile candidate = profileService.getOrProvisionMine();
        Page<Application> result = applicationRepository.findAllByCandidateId(candidate.getId(),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "appliedAt")));
        return PageResponse.<ApplicationResponse>builder().content(result.getContent().stream().map(ApplicationResponse::from).toList())
                .page(result.getNumber()).size(result.getSize()).totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages()).first(result.isFirst()).last(result.isLast()).build();
    }

    @Transactional(readOnly = true)
    public ApplicationResponse getMine(Long id) { return ApplicationResponse.from(findOwned(id)); }

    public ApplicationResponse withdraw(Long id) {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        Application application = applicationRepository.findOwnedForUpdate(id, candidate.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        if (application.getStatus() != ApplicationStatus.SUBMITTED)
            throw new BadRequestException("Only submitted applications can be withdrawn");
        application.setStatus(ApplicationStatus.WITHDRAWN);
        return ApplicationResponse.from(applicationRepository.saveAndFlush(application));
    }

    private Application findOwned(Long id) {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        return applicationRepository.findByIdAndCandidateId(id, candidate.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
    }
    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BadRequestException("page must be non-negative and size must be between 1 and 100");
    }
}
