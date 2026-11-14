package com.recruitpro.organization.dto;

import com.recruitpro.user.entity.Role;

import java.time.LocalDateTime;

public record InvitationPreviewResponse(String organizationName, String email, Role role, LocalDateTime expiresAt) { }
