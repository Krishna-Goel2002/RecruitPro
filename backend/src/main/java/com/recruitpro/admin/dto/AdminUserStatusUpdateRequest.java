package com.recruitpro.admin.dto;

import jakarta.validation.constraints.NotNull;

public record AdminUserStatusUpdateRequest(
        @NotNull(message = "enabled status is required")
        Boolean enabled
) { }
