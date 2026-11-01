package com.recruitpro.candidate.dto;

import com.recruitpro.candidate.entity.CandidateProfile;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record CandidateProfileResponse(Long id, String email, String firstName, String lastName,
        LocalDate dateOfBirth, String gender, String address, String city, String state, String country,
        String zipCode, String linkedinUrl, String portfolioUrl, Integer yearsOfExperience,
        String currentCompany, String currentDesignation, BigDecimal expectedSalary,
        Integer noticePeriodDays, String profileSummary, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static CandidateProfileResponse from(CandidateProfile p) {
        return new CandidateProfileResponse(p.getId(), p.getUser().getEmail(), p.getUser().getFirstName(), p.getUser().getLastName(),
                p.getDateOfBirth(), p.getGender(), p.getAddress(), p.getCity(), p.getState(), p.getCountry(), p.getZipCode(),
                p.getLinkedinUrl(), p.getPortfolioUrl(), p.getYearsOfExperience(), p.getCurrentCompany(),
                p.getCurrentDesignation(), p.getExpectedSalary(), p.getNoticePeriodDays(), p.getProfileSummary(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
