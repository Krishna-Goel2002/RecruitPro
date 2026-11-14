package com.recruitpro.hr.dto;

import com.recruitpro.user.entity.Role;

/** GET /api/v1/hr/interviewers: staff of the caller's organisation who can be assigned interviews. */
public record InterviewerResponse(Long userId, String name, String email, Role role) {
}
