package com.recruitpro.auth.mfa.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.recruitpro.auth.dto.AuthResponse;

import java.util.List;

/**
 * Ten recovery codes (shown once). {@code session} is present only when the confirmation used an enrolment
 * {@code mfaToken}: the sign-in is then complete (the portal's refresh cookie is set as well).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MfaConfirmResponse(List<String> recoveryCodes, AuthResponse session) {
}
