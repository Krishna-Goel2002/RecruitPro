package com.recruitpro.security;

import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;

/**
 * The authenticated caller, resolved from the user row that was loaded for this request.
 * {@code organizationId} is null for candidates.
 */
public record CurrentUser(Long userId, String email, Role role, Long organizationId) {

    public static CurrentUser of(User user) {
        return new CurrentUser(user.getId(), user.getEmail(), user.getRole(), user.getOrganizationId());
    }

    public boolean hasRole(Role expected) {
        return role == expected;
    }

    public boolean hasAnyRole(Role... roles) {
        for (Role candidate : roles) {
            if (role == candidate) {
                return true;
            }
        }
        return false;
    }

    /** Staff users (every role except CANDIDATE) belong to an organisation. */
    public boolean isStaff() {
        return role != null && role != Role.CANDIDATE;
    }
}
