package com.recruitpro.fairness.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Voluntary equal-opportunity answers of one candidate (D14). Stored apart from the profile, only with consent,
 * deletable at any time, never shown to reviewers and only reported in aggregates (groups smaller than 5
 * suppressed). Values are the codes of plan 5.5 ({@link EqualOpportunityValues}); null means "not answered".
 */
@Entity
@Table(name = "eeo_responses")
@Getter
@Setter
@NoArgsConstructor
public class EeoResponse extends BaseEntity {

    @Column(name = "candidate_id", nullable = false, unique = true)
    private Long candidateId;

    @Column(length = 40)
    private String gender;

    @Column(name = "age_band", length = 20)
    private String ageBand;

    @Column(length = 20)
    private String disability;

    @Column(name = "social_category", length = 40)
    private String socialCategory;

    @Column(name = "consent_given_at", nullable = false)
    private LocalDateTime consentGivenAt;
}
