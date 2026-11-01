package com.recruitpro.admin.dto;

import com.recruitpro.user.entity.Role;
import jakarta.validation.constraints.NotNull;

public record AdminUserRoleUpdateRequest(
        @NotNull(message = "role is required")
        Role role
) { }
