package com.recruitpro.verification.dto;

/** One answer: {@code answer} CONFIRM, PARTLY, CANNOT_CONFIRM or DISAGREE; {@code comment} max 2000. */
public record PublicReferenceAnswer(String claimId, String answer, String comment) {
}
