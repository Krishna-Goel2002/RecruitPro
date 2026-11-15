package com.recruitpro.verification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A claim a referee is asked about, as it was when the request was sent. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReferenceClaim(String subjectType, Long subjectId, String claimType, String value) {
}
