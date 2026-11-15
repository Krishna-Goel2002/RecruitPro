package com.recruitpro.verification.dto;

import java.util.List;
import java.util.Map;

/**
 * PUT /api/v1/admin/verification/settings; connectors left out keep their value. {@code trustedEmployerDomains} and
 * {@code trustedBadgeIssuers} (V26) replace the stored list when present (send {@code []} to clear) and keep it when
 * null or absent. Entries are domain names such as {@code example.com} (scheme, path and {@code www.} are stripped).
 */
public record VerificationSettingsRequest(String mode, Map<String, Boolean> connectors, List<String> trustedEmployerDomains,
                                          List<String> trustedBadgeIssuers) {
}
