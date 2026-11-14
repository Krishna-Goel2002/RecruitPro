package com.recruitpro.admin.service;

import com.recruitpro.admin.dto.AdminUserResponse;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.audit.service.AuditService;
import com.recruitpro.auth.service.RefreshTokenService;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.util.LikePatterns;
import com.recruitpro.hr.service.HrProfileService;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Staff administration within the caller's organisation (D3). Users of other organisations behave as
 * missing (404). The last enabled administrator cannot be disabled or demoted; the check locks the
 * organisation's administrators first (L3).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminUserService {
    private final UserRepository userRepository;
    private final HrProfileService hrProfileService;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final SecurityUtils securityUtils;

    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> listUsers(int page, int size, String search, Role role, Boolean enabled) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BadRequestException("page must be non-negative and size between 1 and 100");
        }
        Long organizationId = securityUtils.requireOrganizationId();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (role != null) {
                predicates.add(cb.equal(root.get("role"), role));
            }
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            if (StringUtils.hasText(search)) {
                String pattern = LikePatterns.containsLower(search);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("email")), pattern, LikePatterns.ESCAPE),
                        cb.like(cb.lower(root.get("firstName")), pattern, LikePatterns.ESCAPE),
                        cb.like(cb.lower(root.get("lastName")), pattern, LikePatterns.ESCAPE)));
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
        CurrentUser admin = securityUtils.requireOrganizationMember();
        User user = userRepository.findByIdAndOrganizationId(userId, admin.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        if (!enabled && user.getRole() == Role.ADMIN) {
            if (admin.userId().equals(user.getId())) {
                throw new BadRequestException("Administrators cannot deactivate their own account");
            }
            if (user.isEnabled() && userRepository.lockEnabledByOrganizationAndRole(admin.organizationId(), Role.ADMIN).size() <= 1) {
                throw new BadRequestException("Cannot deactivate the last active administrator");
            }
        }

        boolean previous = user.isEnabled();
        user.setEnabled(enabled);
        User saved = userRepository.saveAndFlush(user);
        if (previous && !enabled) {
            refreshTokenService.revokeAllForUser(saved.getId(), RefreshTokenService.REASON_ACCOUNT_DISABLED);
        }

        auditService.record(
                AuditActions.USER_STATUS_CHANGED,
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
        CurrentUser admin = securityUtils.requireOrganizationMember();
        User user = userRepository.findByIdAndOrganizationId(userId, admin.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (newRole == Role.CANDIDATE) {
            throw new BadRequestException("Organisation members can only be given staff roles");
        }

        Role previousRole = user.getRole();
        if (previousRole == newRole) {
            return AdminUserResponse.from(user);
        }

        if (previousRole == Role.ADMIN) {
            if (admin.userId().equals(user.getId())) {
                throw new BadRequestException("Administrators cannot demote their own account");
            }
            List<User> admins = user.isEnabled()
                    ? userRepository.lockEnabledByOrganizationAndRole(admin.organizationId(), Role.ADMIN)
                    : userRepository.lockAllByOrganizationAndRole(admin.organizationId(), Role.ADMIN);
            if (admins.size() <= 1) {
                throw new BadRequestException("Cannot demote the last remaining administrator");
            }
        }

        user.setRole(newRole);
        User saved = userRepository.saveAndFlush(user);
        hrProfileService.ensureProfileFor(saved);

        auditService.record(
                AuditActions.USER_ROLE_CHANGED,
                "USER",
                String.valueOf(user.getId()),
                String.format("User %s role changed from %s to %s", user.getEmail(), previousRole, newRole));

        return AdminUserResponse.from(saved);
    }
}
