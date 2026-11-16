package com.recruitpro.assistant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of a confirm: {kind, id?, link?} when it ran (kind job, application, interview, work_sample,
 * work_sample_assignment, interview_kit), {kind: "failed", error} when the re-validation or the service refused it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionResult(String kind, Long id, String link, String error) {
}
