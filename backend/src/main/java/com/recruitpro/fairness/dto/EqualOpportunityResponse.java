package com.recruitpro.fairness.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.recruitpro.fairness.entity.EeoResponse;

import java.time.LocalDateTime;

/** The candidate's own stored survey answers and when consent was (last) given. Only ever returned to the candidate. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EqualOpportunityResponse(String gender, String ageBand, String disability, String socialCategory,
                                       LocalDateTime consentGivenAt) {

    public static EqualOpportunityResponse from(EeoResponse response) {
        return new EqualOpportunityResponse(response.getGender(), response.getAgeBand(), response.getDisability(),
                response.getSocialCategory(), response.getConsentGivenAt());
    }
}
