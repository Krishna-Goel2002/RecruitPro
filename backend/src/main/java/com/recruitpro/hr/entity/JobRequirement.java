package com.recruitpro.hr.entity;

import com.recruitpro.common.entity.BaseEntity;
import com.recruitpro.common.persistence.StringListJsonConverter;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Requirement v2 (plan 5.1): alternatives (any one satisfies it, H2), knockout gate (D6: ranking gate,
 * never an automatic rejection; defaults to mandatory) and an optional proficiency.
 */
@Entity
@Table(name = "job_requirements")
@Getter @Setter @NoArgsConstructor
public class JobRequirement extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "requirement_type", nullable = false, length = 30)
    private RequirementType type;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "is_mandatory", nullable = false)
    private boolean mandatory = true;

    @Column(nullable = false)
    private boolean knockout;

    @Column(name = "minimum_years")
    private Integer minimumYears;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "alternatives_json", columnDefinition = "TEXT")
    private List<String> alternatives = new ArrayList<>();

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(length = 20)
    private Proficiency proficiency;

    /** Position in the job's list (V28, R6-B2): HR's order; requirements are read by (displayOrder, id). */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** Never null; a copy-safe view for DTOs. */
    public List<String> getAlternatives() {
        return alternatives == null ? new ArrayList<>() : alternatives;
    }
}
