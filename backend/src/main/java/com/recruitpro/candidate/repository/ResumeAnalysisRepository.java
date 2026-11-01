package com.recruitpro.candidate.repository;

import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ResumeAnalysisRepository extends JpaRepository<ResumeAnalysis, Long> {
    Optional<ResumeAnalysis> findByResumeId(Long resumeId);
    Optional<ResumeAnalysis> findByResumeIdAndResumeCandidateId(Long resumeId, Long candidateId);
    Optional<ResumeAnalysis> findFirstByStatusOrderByCreatedAtAscIdAsc(ResumeAnalysisStatus status);

    @Query("select a from ResumeAnalysis a join fetch a.resume where a.id = :id")
    Optional<ResumeAnalysis> findWithResumeById(@Param("id") Long id);

    @Modifying
    @Query("update ResumeAnalysis a set a.status = :processing, a.attemptCount = a.attemptCount + 1, a.lastAttemptAt = :now, a.errorCode = null where a.id = :id and a.status = :pending")
    int claimPending(@Param("id") Long id, @Param("pending") ResumeAnalysisStatus pending,
                     @Param("processing") ResumeAnalysisStatus processing, @Param("now") LocalDateTime now);

    @Modifying
    @Query("update ResumeAnalysis a set a.status = :pending, a.errorCode = 'PROCESSING_TIMEOUT' where a.status = :processing and a.lastAttemptAt < :cutoff")
    int recoverStaleProcessing(@Param("processing") ResumeAnalysisStatus processing,
                               @Param("pending") ResumeAnalysisStatus pending, @Param("cutoff") LocalDateTime cutoff);
}
