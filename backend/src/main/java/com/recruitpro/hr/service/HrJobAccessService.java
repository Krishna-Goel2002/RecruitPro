package com.recruitpro.hr.service;

import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HrJobAccessService {
    private final JobRepository jobRepository;

    public Job requireManageAccess(Long jobId) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> new ResourceNotFoundException("Job not found"));
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) throw new ForbiddenException("Access denied");
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        boolean owner = job.getCreatedBy() != null && job.getCreatedBy().getUser() != null
                && job.getCreatedBy().getUser().getEmail().equalsIgnoreCase(authentication.getName());
        if (!admin && !owner) throw new ForbiddenException("You are not permitted to manage this job");
        return job;
    }

    public Job requireApplicantReadAccess(Long jobId) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> new ResourceNotFoundException("Job not found"));
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) throw new ForbiddenException("Access denied");
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        boolean recruiter = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_RECRUITER"));
        boolean owner = job.getCreatedBy() != null && job.getCreatedBy().getUser() != null
                && job.getCreatedBy().getUser().getEmail().equalsIgnoreCase(authentication.getName());
        if (!admin && !owner && !recruiter) throw new ForbiddenException("You are not permitted to view applicants for this job");
        return job;
    }
}
