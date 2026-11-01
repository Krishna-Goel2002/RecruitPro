package com.recruitpro.candidate.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CandidateProfileUpdateRequest(
        @Past LocalDate dateOfBirth,
        @Size(max = 30) String gender,
        @Size(max = 5000) String address,
        @Size(max = 100) String city,
        @Size(max = 100) String state,
        @Size(max = 100) String country,
        @Size(max = 20) String zipCode,
        @Size(max = 500) @Pattern(regexp = "^$|https?://.+", message = "must be an http or https URL") String linkedinUrl,
        @Size(max = 500) @Pattern(regexp = "^$|https?://.+", message = "must be an http or https URL") String portfolioUrl,
        @PositiveOrZero @Max(100) Integer yearsOfExperience,
        @Size(max = 255) String currentCompany,
        @Size(max = 255) String currentDesignation,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal expectedSalary,
        @PositiveOrZero @Max(3650) Integer noticePeriodDays,
        @Size(max = 5000) String profileSummary) { }
