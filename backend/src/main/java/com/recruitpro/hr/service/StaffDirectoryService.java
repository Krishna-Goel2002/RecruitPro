package com.recruitpro.hr.service;

import com.recruitpro.hr.dto.InterviewerResponse;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** GET /api/v1/hr/interviewers: enabled staff of the caller's organisation (D3), sorted by name. */
@Service
@Transactional(readOnly = true)
public class StaffDirectoryService {

    private static final Set<Role> STAFF_ROLES = EnumSet.of(Role.ADMIN, Role.HR_MANAGER, Role.RECRUITER, Role.INTERVIEWER);

    private final SecurityUtils securityUtils;
    private final UserRepository userRepository;

    public StaffDirectoryService(SecurityUtils securityUtils, UserRepository userRepository) {
        this.securityUtils = securityUtils;
        this.userRepository = userRepository;
    }

    public List<InterviewerResponse> interviewers() {
        CurrentUser caller = securityUtils.requireOrganizationMember();
        return userRepository.findAllByOrganizationIdAndRoleIn(caller.organizationId(), STAFF_ROLES).stream()
                .filter(user -> user.isEnabled() && user.getDeletedAt() == null)
                .sorted(Comparator.comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER).thenComparing(User::getId))
                .map(user -> new InterviewerResponse(user.getId(), user.getFullName(), user.getEmail(), user.getRole()))
                .toList();
    }
}
