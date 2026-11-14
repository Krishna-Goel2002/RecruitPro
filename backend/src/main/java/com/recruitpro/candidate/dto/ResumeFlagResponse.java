package com.recruitpro.candidate.dto;

/** Parser flag, e.g. HIDDEN_TEXT, PROMPT_INJECTION_SUSPECTED, TWO_COLUMN_LAYOUT, OCR_USED, LEGACY_DOC. */
public record ResumeFlagResponse(String code, String detail) {
}
