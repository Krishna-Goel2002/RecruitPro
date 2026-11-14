package com.recruitpro.organization.dto;

import com.recruitpro.user.entity.Role;

import java.time.LocalDateTime;

/** {@code inviteUrl} is only present right after creation and only when app.invitations.expose-link=true. */
public record InvitationResponse(Long id, String email, Role role, String status, LocalDateTime expiresAt,
                                 LocalDateTime acceptedAt, LocalDateTime revokedAt, LocalDateTime createdAt,
                                 String invitedByName, String inviteUrl) { }
