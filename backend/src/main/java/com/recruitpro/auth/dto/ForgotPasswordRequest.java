package com.recruitpro.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Always answered with 200, whether or not the address belongs to an account. */
public record ForgotPasswordRequest(@NotBlank @Size(max = 255) String email) { }
