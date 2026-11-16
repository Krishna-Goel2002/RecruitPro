package com.recruitpro.assistant.tool;

import com.recruitpro.user.entity.Role;

import java.util.EnumSet;
import java.util.Set;

/**
 * Which roles see a tool in their catalogue. Mirrors the portal's role rules ({@code HrJobAccessService}): managers
 * (ADMIN, HR_MANAGER) change jobs and applications; readers (plus RECRUITER) read jobs and applicants; every staff
 * role, INTERVIEWER included, has interview tools. The tool's service call checks the role again (as the user).
 */
public enum ToolCapability {
    /** Every staff role, INTERVIEWER included (interview-related and help tools). */
    ANY_STAFF(EnumSet.of(Role.ADMIN, Role.HR_MANAGER, Role.RECRUITER, Role.INTERVIEWER)),
    /** ADMIN, HR_MANAGER, RECRUITER: jobs and applicants (HrJobAccessService.requireJobReader). */
    JOBS_READ(EnumSet.of(Role.ADMIN, Role.HR_MANAGER, Role.RECRUITER)),
    /** ADMIN, HR_MANAGER: job and application changes, fairness (HrJobAccessService.requireJobManager). */
    JOBS_MANAGE(EnumSet.of(Role.ADMIN, Role.HR_MANAGER));

    private final Set<Role> roles;

    ToolCapability(Set<Role> roles) {
        this.roles = roles;
    }

    public boolean allows(Role role) {
        return role != null && roles.contains(role);
    }
}
