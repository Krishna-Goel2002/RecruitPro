package com.recruitpro.verification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Verification settings of one organisation (principle 9). No row means SHADOW with every connector enabled.
 * {@code connectors_json} is an object {@code {"GITHUB": true, ..., "REFERENCE": true}}; the two trusted-domain
 * columns are JSON arrays of lower-case domain names (V26).
 */
@Entity
@Table(name = "organization_verification_settings")
@Getter
@Setter
@NoArgsConstructor
public class OrganizationVerificationSettings {

    @Id
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private VerificationMode mode = VerificationMode.SHADOW;

    @Column(name = "connectors_json", columnDefinition = "TEXT")
    private String connectorsJson;

    /** JSON array of employer domains the organisation trusts for references (V26); null = none. */
    @Column(name = "trusted_employer_domains_json", columnDefinition = "TEXT")
    private String trustedEmployerDomainsJson;

    /** JSON array of Open Badge issuer domains the organisation trusts (V26); null = none. */
    @Column(name = "trusted_badge_issuers_json", columnDefinition = "TEXT")
    private String trustedBadgeIssuersJson;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
