package com.recruitpro.verification.dto;

import java.util.List;

/**
 * POST /api/v1/public/references/{token}: either {@code {answers, refereeComment, confirmIdentity: true}} or
 * {@code {decline: true}}.
 */
public record PublicReferenceSubmitRequest(List<PublicReferenceAnswer> answers, String refereeComment,
                                           Boolean confirmIdentity, Boolean decline) {
}
