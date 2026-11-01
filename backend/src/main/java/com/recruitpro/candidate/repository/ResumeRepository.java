package com.recruitpro.candidate.repository;

import com.recruitpro.candidate.entity.Resume;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {
    List<Resume> findAllByCandidateIdOrderByUploadedAtDescIdDesc(Long candidateId);
    Optional<Resume> findByIdAndCandidateId(Long id, Long candidateId);
}
