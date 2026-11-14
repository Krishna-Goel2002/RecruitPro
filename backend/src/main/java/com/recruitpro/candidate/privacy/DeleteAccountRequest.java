package com.recruitpro.candidate.privacy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DELETE /api/v1/candidates/me: {password}. A wrong password is 400 (never 401). */
public record DeleteAccountRequest(@NotBlank @Size(max = 200) String password) {
}
