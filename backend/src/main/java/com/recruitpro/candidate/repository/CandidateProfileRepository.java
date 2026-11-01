package com.recruitpro.candidate.repository;

import com.recruitpro.candidate.entity.CandidateProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CandidateProfileRepository extends JpaRepository<CandidateProfile, Long> {
    Optional<CandidateProfile> findByUserEmailIgnoreCase(String email);
    Optional<CandidateProfile> findByIdAndUserEmailIgnoreCase(Long id, String email);
    Optional<CandidateProfile> findByUserId(Long userId);
}
