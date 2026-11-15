package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/** A shadow-evaluation label. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LabelView(Long verdictId, String labelVerdict, String note, LocalDateTime at) {
}
