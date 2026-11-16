package com.recruitpro.assistant.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * The page the user is on: {@code page} job|applicant|jobs|admin|interviews|other (anything else counts as other),
 * the current job and application. Ids the user may not see are ignored.
 */
public record MessageContext(@Size(max = 40) String page, @Positive Long jobId, @Positive Long applicationId) {
}
