package com.recruitpro.auth.mfa.dto;

import java.util.List;

/** POST /api/v1/me/mfa/recovery-codes: ten new recovery codes (shown once); earlier codes stop working. */
public record RecoveryCodesResponse(List<String> recoveryCodes) {
}
