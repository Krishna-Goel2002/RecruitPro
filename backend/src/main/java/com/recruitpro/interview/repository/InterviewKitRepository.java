package com.recruitpro.interview.repository;

import com.recruitpro.interview.entity.InterviewKit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InterviewKitRepository extends JpaRepository<InterviewKit, Long> {

    Optional<InterviewKit> findByJobId(Long jobId);
}
