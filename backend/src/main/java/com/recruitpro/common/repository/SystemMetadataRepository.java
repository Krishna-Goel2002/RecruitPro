package com.recruitpro.common.repository;

import com.recruitpro.common.entity.SystemMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository interface for SystemMetadata entity.
 */
@Repository
public interface SystemMetadataRepository extends JpaRepository<SystemMetadata, Long> {

    Optional<SystemMetadata> findByPropertyKey(String propertyKey);
}
