package com.recruitpro.candidate.repository;

import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ResumeAnalysisRepository extends JpaRepository<ResumeAnalysis, Long> {
    Optional<ResumeAnalysis> findByResumeId(Long resumeId);
    Optional<ResumeAnalysis> findByResumeIdAndResumeCandidateId(Long resumeId, Long candidateId);

    @Query("select new com.recruitpro.candidate.repository.AnalysisSummary(a.resume.id, a.id, a.status, a.flagsJson) "
            + "from ResumeAnalysis a where a.resume.id in :resumeIds")
    List<AnalysisSummary> findSummariesByResumeIds(@Param("resumeIds") Collection<Long> resumeIds);

    @Query("select a from ResumeAnalysis a join fetch a.resume where a.id = :id")
    Optional<ResumeAnalysis> findWithResumeById(@Param("id") Long id);

    /**
     * Moves an analysis that is waiting for parsing (PENDING, or PROCESSING left behind by an interrupted
     * attempt) to PROCESSING. The PARSE_RESUME background job is the only caller, one job per analysis.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ResumeAnalysis a set a.status = :processing, a.attemptCount = a.attemptCount + 1, a.lastAttemptAt = :now, "
            + "a.errorCode = null, a.updatedAt = :now where a.id = :id and a.status in :claimable")
    int claimForProcessing(@Param("id") Long id, @Param("claimable") Collection<ResumeAnalysisStatus> claimable,
                           @Param("processing") ResumeAnalysisStatus processing, @Param("now") LocalDateTime now);
}
