package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/** The caller's organisation's latest review of a verdict. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewView(String decision, String note, String reviewerName, LocalDateTime at) {
}
