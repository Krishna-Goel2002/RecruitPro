package com.recruitpro.verification.dto;

import java.util.Map;

/** PUT /api/v1/admin/verification/settings; connectors left out keep their value. */
public record VerificationSettingsRequest(String mode, Map<String, Boolean> connectors) {
}
