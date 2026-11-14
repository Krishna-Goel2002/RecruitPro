package com.recruitpro.admin.service;

import com.recruitpro.admin.dto.AdminHrMemberResponse;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.repository.HRProfileRepository;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** HR team directory of the caller's organisation (D3). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminHrManagementService {
    private final UserRepository userRepository;
    private final HRProfileRepository hrProfileRepository;
    private final JobRepository jobRepository;
    private final SecurityUtils securityUtils;

    public List<AdminHrMemberResponse> listHrTeam() {
        Long organizationId = securityUtils.requireOrganizationId();
        List<User> hrUsers = new ArrayList<>(userRepository.findAllByOrganizationIdAndRoleIn(organizationId,
                List.of(Role.HR_MANAGER, Role.RECRUITER)));
        hrUsers.sort(Comparator.comparing(User::getId));
        List<AdminHrMemberResponse> results = new ArrayList<>();

        for (User user : hrUsers) {
            HRProfile profile = hrProfileRepository.findByUserId(user.getId()).orElse(null);
            long jobsCount = profile != null ? jobRepository.countByCreatedById(profile.getId()) : 0;
            results.add(new AdminHrMemberResponse(
                    user.getId(),
                    profile != null ? profile.getId() : null,
                    user.getEmail(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getRole(),
                    profile != null && profile.getDepartment() != null ? profile.getDepartment() : "General",
                    profile != null ? profile.getDesignation() : null,
                    profile != null ? profile.getEmployeeId() : null,
                    user.isEnabled(),
                    user.getCreatedAt(),
                    jobsCount
            ));
        }
        return results;
    }
}
