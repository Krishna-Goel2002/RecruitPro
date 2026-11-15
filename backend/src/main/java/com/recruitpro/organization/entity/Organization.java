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

    /** Staff must use TOTP MFA (plan v2, Section 6.1); candidates are never affected. */
    @Column(name = "require_staff_mfa", nullable = false)
    private boolean requireStaffMfa = false;

    /**
     * Comma-separated e-mail domains whose verified addresses may sign in with SSO (plan v2, Section 6.2), e.g.
     * {@code acme.com,acme.co.uk}; null or empty disables SSO for the organisation.
     */
    @Column(name = "sso_allowed_domains", length = 500)
    private String ssoAllowedDomains;
}
