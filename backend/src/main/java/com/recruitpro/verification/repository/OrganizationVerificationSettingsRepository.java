package com.recruitpro.verification.repository;

import com.recruitpro.verification.entity.OrganizationVerificationSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationVerificationSettingsRepository extends JpaRepository<OrganizationVerificationSettings, Long> {
}
