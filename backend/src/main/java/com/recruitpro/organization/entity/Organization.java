package com.recruitpro.organization.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** D3: a customer organisation. Staff users and jobs belong to exactly one organisation. */
@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
public class Organization extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, unique = true, length = 80)
    private String slug;

    /** D4: reviewers see anonymised applicants until shortlisting (used by WP-BE-B). */
    @Column(name = "blind_review", nullable = false)
    private boolean blindReview = true;

    /** Whether candidates see the verification status of each requirement (used by WP-BE-B). */
    @Column(name = "share_verification_status", nullable = false)
    private boolean shareVerificationStatus = true;
}
