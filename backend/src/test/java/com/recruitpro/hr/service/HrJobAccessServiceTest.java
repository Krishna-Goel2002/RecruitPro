package com.recruitpro.hr.service;

import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.Role;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** D3: job access is scoped to the caller's organisation; managers change, recruiters read, interviewers neither. */
class HrJobAccessServiceTest {

    @Test
    void jobAccessIsScopedToTheCallersOrganisationAndManagersOnly() {
        Job ownJob = new Job(); ownJob.setId(3L); ownJob.setOrganizationId(100L);
        JobRepository jobs = (JobRepository) Proxy.newProxyInstance(JobRepository.class.getClassLoader(),
                new Class<?>[]{JobRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findByIdAndOrganizationId")) {
                        return args[0].equals(3L) && args[1].equals(100L) ? Optional.of(ownJob) : Optional.empty();
                    }
                    return null;
                });
        assertSame(ownJob, access(jobs, new CurrentUser(1L, "manager@a.test", Role.HR_MANAGER, 100L)).requireManageAccess(3L));
        assertSame(ownJob, access(jobs, new CurrentUser(2L, "admin@a.test", Role.ADMIN, 100L)).requireManageAccess(3L));
        assertThrows(ResourceNotFoundException.class,
                () -> access(jobs, new CurrentUser(3L, "manager@b.test", Role.HR_MANAGER, 200L)).requireManageAccess(3L));
        assertThrows(ForbiddenException.class,
                () -> access(jobs, new CurrentUser(4L, "recruiter@a.test", Role.RECRUITER, 100L)).requireManageAccess(3L));
        assertSame(ownJob, access(jobs, new CurrentUser(4L, "recruiter@a.test", Role.RECRUITER, 100L)).requireApplicantReadAccess(3L));
        assertThrows(ForbiddenException.class,
                () -> access(jobs, new CurrentUser(5L, "interviewer@a.test", Role.INTERVIEWER, 100L)).requireApplicantReadAccess(3L));
        assertThrows(ForbiddenException.class,
                () -> access(jobs, new CurrentUser(6L, "candidate@a.test", Role.CANDIDATE, null)).requireApplicantReadAccess(3L));
    }

    private HrJobAccessService access(JobRepository jobs, CurrentUser caller) {
        SecurityUtils securityUtils = new SecurityUtils(null) {
            @Override public CurrentUser currentUser() { return caller; }
        };
        return new HrJobAccessService(jobs, securityUtils);
    }
}
