package com.recruitpro.hr.service;

import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.Role;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;

/**
 * Organisation-scoped job access (D3, S5, M1). A job of another organisation behaves as missing (404).
 * ADMIN and HR_MANAGER manage every job in their organisation; RECRUITER reads jobs and applicants;
 * INTERVIEWER has no job access.
 */
@Service
public class HrJobAccessService {

    private static final Set<Role> MANAGERS = EnumSet.of(Role.ADMIN, Role.HR_MANAGER);
    private static final Set<Role> READERS = EnumSet.of(Role.ADMIN, Role.HR_MANAGER, Role.RECRUITER);

    private final JobRepository jobRepository;
    private final SecurityUtils securityUtils;

    public HrJobAccessService(JobRepository jobRepository, SecurityUtils securityUtils) {
        this.jobRepository = jobRepository;
        this.securityUtils = securityUtils;
    }

    /** Job of the caller's organisation that the caller (ADMIN or HR_MANAGER) may change. */
    public Job requireManageAccess(Long jobId) {
        return findInOrganization(jobId, requireJobManager());
    }

    /** Job of the caller's organisation whose applicants the caller (ADMIN, HR_MANAGER, RECRUITER) may read. */
    public Job requireApplicantReadAccess(Long jobId) {
        return findInOrganization(jobId, requireJobReader());
    }

    /** Job of the caller's organisation that the caller (ADMIN, HR_MANAGER, RECRUITER) may read. */
    public Job requireJobReadAccess(Long jobId) {
        return findInOrganization(jobId, requireJobReader());
    }

    /** Current caller when they may create and manage jobs; carries the organisation id. */
    public CurrentUser requireJobManager() {
        return requireRole(MANAGERS, "You are not permitted to manage jobs");
    }

    /** Current caller when they may read jobs and applicants; carries the organisation id. */
    public CurrentUser requireJobReader() {
        return requireRole(READERS, "You are not permitted to view jobs");
    }

    private CurrentUser requireRole(Set<Role> allowed, String message) {
        CurrentUser user = securityUtils.currentUser();
        if (!allowed.contains(user.role())) {
            throw new ForbiddenException(message);
        }
        if (user.organizationId() == null) {
            throw new ForbiddenException("Your account is not linked to an organisation");
        }
        return user;
    }

    private Job findInOrganization(Long jobId, CurrentUser user) {
        if (jobId == null) {
            throw new ResourceNotFoundException("Job not found");
        }
        return jobRepository.findByIdAndOrganizationId(jobId, user.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Job not found"));
    }
}
