package com.recruitpro.organization.dto;

import com.recruitpro.user.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InvitationCreateRequest(@NotBlank @Email @Size(max = 255) String email, @NotNull Role role) { }
