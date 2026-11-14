package com.recruitpro.hr.dto;

/** The revealed candidate block of ApplicantDetail (plan 5.4); null until the identity is revealed. */
public record CandidateIdentityResponse(String fullName, String email, String city, String country, String linkedinUrl,
                                        String portfolioUrl, Integer yearsOfExperience, String currentCompany,
                                        String currentDesignation) {
}
