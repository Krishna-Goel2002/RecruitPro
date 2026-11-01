package com.recruitpro.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entity mapping for system_metadata table created in Flyway V1.
 */
@Entity
@Table(name = "system_metadata")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemMetadata extends BaseEntity {

    @Column(name = "property_key", nullable = false, unique = true, length = 100)
    private String propertyKey;

    @Column(name = "property_value", nullable = false)
    private String propertyValue;

    @Column(name = "description", length = 500)
    private String description;
}
