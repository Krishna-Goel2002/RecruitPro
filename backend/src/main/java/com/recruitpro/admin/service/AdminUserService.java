package com.recruitpro.admin.service;

import com.recruitpro.admin.audit.AdminAuditService;
import com.recruitpro.admin.dto.AdminUserResponse;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.repository.HRProfileRepository;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminUserService {
    private final UserRepository userRepository;
    private final HRProfileRepository hrProfileRepository;
    private final CandidateProfileRepository candidateProfileRepository;
    private final AdminAuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> listUsers(int page, int size, String search, Role role, Boolean enabled) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BadRequestException("page must be non-negative and size between 1 and 100");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (role != null) {
                predicates.add(cb.equal(root.get("role"), role));
            }
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            if (StringUtils.hasText(search)) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("email")), pattern),
                        cb.like(cb.lower(root.get("firstName")), pattern),
                        cb.like(cb.lower(root.get("lastName")), pattern)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<User> users = userRepository.findAll(spec, pageable);
        List<AdminUserResponse> content = users.getContent().stream()
                .map(AdminUserResponse::from)
                .toList();

        return PageResponse.<AdminUserResponse>builder()
                .content(content)
                .page(users.getNumber())
                .size(users.getSize())
                .totalElements(users.getTotalElements())
                .totalPages(users.getTotalPages())
                .first(users.isFirst())
                .last(users.isLast())
                .build();
    }

    public AdminUserResponse updateUserStatus(Long userId, boolean enabled) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String currentAdminEmail = auth != null ? auth.getName() : null;

        // Protection: cannot disable self or last active admin
        if (!enabled && user.getRole() == Role.ADMIN) {
            if (currentAdminEmail != null && currentAdminEmail.equalsIgnoreCase(user.getEmail())) {
                throw new BadRequestException("Administrators cannot deactivate their own account");
            }
            long activeAdmins = userRepository.countByRoleAndEnabled(Role.ADMIN, true);
            if (activeAdmins <= 1) {
                throw new BadRequestException("Cannot deactivate the last active administrator");
            }
        }

        boolean previous = user.isEnabled();
        user.setEnabled(enabled);
        User saved = userRepository.saveAndFlush(user);

        auditService.record(
                "USER_STATUS_CHANGED",
                "USER",
                String.valueOf(user.getId()),
                String.format("User %s status changed from enabled=%b to enabled=%b", user.getEmail(), previous,
                        enabled));

        return AdminUserResponse.from(saved);
    }

    public AdminUserResponse updateUserRole(Long userId, Role newRole) {
        if (newRole == null) {
            throw new BadRequestException("Role is required");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        Role previousRole = user.getRole();
        if (previousRole == newRole) {
            return AdminUserResponse.from(user);
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String currentAdminEmail = auth != null ? auth.getName() : null;

        // Protection: cannot demote self or last active admin
        if (previousRole == Role.ADMIN && newRole != Role.ADMIN) {
            if (currentAdminEmail != null && currentAdminEmail.equalsIgnoreCase(user.getEmail())) {
                throw new BadRequestException("Administrators cannot demote their own account");
            }
            if (user.isEnabled()) {
                long activeAdmins = userRepository.countByRoleAndEnabled(Role.ADMIN, true);
                if (activeAdmins <= 1) {
                    throw new BadRequestException("Cannot demote the last remaining administrator");
                }
            } else {
                long totalAdmins = userRepository.countByRole(Role.ADMIN);
                if (totalAdmins <= 1) {
                    throw new BadRequestException("Cannot demote the last remaining administrator");
                }
            }
        }

        // Organizational profile consistency
        if (newRole == Role.HR_MANAGER || newRole == Role.RECRUITER) {
            if (hrProfileRepository.findByUserId(user.getId()).isEmpty()) {
                HRProfile profile = new HRProfile();
                profile.setUser(user);
                profile.setDepartment("General");
                hrProfileRepository.save(profile);
            }
        } else if (newRole == Role.CANDIDATE) {
            if (candidateProfileRepository.findByUserId(user.getId()).isEmpty()) {
                CandidateProfile profile = new CandidateProfile();
                profile.setUser(user);
                candidateProfileRepository.save(profile);
            }
        }

        user.setRole(newRole);
        User saved = userRepository.saveAndFlush(user);

        auditService.record(
                "USER_ROLE_CHANGED",
                "USER",
                String.valueOf(user.getId()),
                String.format("User %s role changed from %s to %s", user.getEmail(), previousRole, newRole));

        return AdminUserResponse.from(saved);
    }
}
