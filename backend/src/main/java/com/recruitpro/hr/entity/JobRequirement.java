package com.recruitpro.hr.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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

    @Column(name = "minimum_years")
    private Integer minimumYears;
}
