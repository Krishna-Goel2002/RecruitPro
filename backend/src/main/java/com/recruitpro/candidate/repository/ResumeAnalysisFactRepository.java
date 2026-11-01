package com.recruitpro.candidate.repository;

import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResumeAnalysisFactRepository extends JpaRepository<ResumeAnalysisFact, Long> {
    List<ResumeAnalysisFact> findAllByAnalysisIdOrderById(Long analysisId);
    Optional<ResumeAnalysisFact> findByIdAndAnalysisId(Long id, Long analysisId);
    void deleteAllByAnalysisId(Long analysisId);
}
