package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** A cited, de-identified reason: {@code text}, the evidence item keys it cites and an optional quote. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VerdictReason(String text, List<String> evidenceKeys, String quote) {
}
