package com.recruitpro.hr.repository;

import com.recruitpro.hr.entity.HRProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface HRProfileRepository extends JpaRepository<HRProfile, Long> {
    Optional<HRProfile> findByUserEmailIgnoreCase(String email);
    Optional<HRProfile> findByUserId(Long userId);
}
