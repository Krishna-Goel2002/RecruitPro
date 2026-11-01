package com.recruitpro.candidate.entity;

import com.recruitpro.common.entity.BaseEntity;
import com.recruitpro.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "candidate_profiles")
@Getter @Setter @NoArgsConstructor
public class CandidateProfile extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "date_of_birth") private LocalDate dateOfBirth;
    @Column(length = 30) private String gender;
    @Column(columnDefinition = "TEXT") private String address;
    @Column(length = 100) private String city;
    @Column(length = 100) private String state;
    @Column(length = 100) private String country;
    @Column(name = "zip_code", length = 20) private String zipCode;
    @Column(name = "linkedin_url", length = 500) private String linkedinUrl;
    @Column(name = "portfolio_url", length = 500) private String portfolioUrl;
    @Column(name = "years_of_experience") private Integer yearsOfExperience;
    @Column(name = "current_company", length = 255) private String currentCompany;
    @Column(name = "current_designation", length = 255) private String currentDesignation;
    @Column(name = "expected_salary", precision = 12, scale = 2) private BigDecimal expectedSalary;
    @Column(name = "notice_period_days") private Integer noticePeriodDays;
    @Column(name = "profile_summary", columnDefinition = "TEXT") private String profileSummary;
}
