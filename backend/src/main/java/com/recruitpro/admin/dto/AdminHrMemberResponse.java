package com.recruitpro.admin.dto;

import com.recruitpro.user.entity.Role;
import java.time.LocalDateTime;

public record AdminHrMemberResponse(
        Long userId,
        Long hrProfileId,
        String email,
        String firstName,
        String lastName,
        Role role,
        String department,
        String designation,
        String employeeId,
        boolean enabled,
        LocalDateTime createdAt,
        long jobsCount
) { }
