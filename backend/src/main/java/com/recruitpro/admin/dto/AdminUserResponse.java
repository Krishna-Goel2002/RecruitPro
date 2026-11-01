package com.recruitpro.admin.dto;

import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import java.time.LocalDateTime;

public record AdminUserResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        Role role,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                user.isEnabled(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
