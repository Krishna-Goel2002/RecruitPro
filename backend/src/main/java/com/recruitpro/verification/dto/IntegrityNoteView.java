package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** An integrity note (e.g. BULK_HISTORY, INSTRUCTION_TEXT); never changes a verdict by itself. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record IntegrityNoteView(String code, String text, List<String> evidenceKeys) {
}
