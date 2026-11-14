package com.recruitpro.organization.repository;

import com.recruitpro.organization.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    boolean existsBySlug(String slug);

    Optional<Organization> findBySlug(String slug);
}
