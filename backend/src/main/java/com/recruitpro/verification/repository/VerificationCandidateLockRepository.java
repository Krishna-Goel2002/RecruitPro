package com.recruitpro.verification.repository;

import com.recruitpro.candidate.entity.CandidateProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Serialises the per-candidate limits of the verification package (duplicate sources, runs per 24 h, open
 * references) by locking the candidate profile row (plain query, FOR UPDATE on candidate_profiles only).
 */
public interface VerificationCandidateLockRepository extends Repository<CandidateProfile, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CandidateProfile p where p.id = :id")
    Optional<CandidateProfile> lockById(@Param("id") Long id);
}
