package com.recruitpro.hr.repository;

import com.recruitpro.hr.entity.JobMatchRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** D8: append-only match runs; exactly one current run per application. */
public interface JobMatchRunRepository extends JpaRepository<JobMatchRun, Long> {

    List<JobMatchRun> findAllByApplicationIdAndCurrentRunTrue(Long applicationId);

    Optional<JobMatchRun> findFirstByApplicationIdAndCurrentRunTrueOrderByIdDesc(Long applicationId);

    List<JobMatchRun> findAllByApplicationIdOrderByIdAsc(Long applicationId);

    long countByApplicationId(Long applicationId);

    @Query("select new com.recruitpro.hr.repository.RunSummary(r.application.id, r.id, r.verifiedCoverage, "
            + "r.claimedCoverage, r.knockoutsVerified) from JobMatchRun r where r.job.id = :jobId and r.currentRun = true")
    List<RunSummary> findCurrentSummariesByJobId(@Param("jobId") Long jobId);

    @Query("select r from JobMatchRun r where r.application.id in :applicationIds and r.currentRun = true")
    List<JobMatchRun> findCurrentByApplicationIds(@Param("applicationIds") Collection<Long> applicationIds);

    @Query("select r.id from JobMatchRun r where r.application.id = :applicationId and r.currentRun = true order by r.id desc")
    List<Long> findCurrentRunIds(@Param("applicationId") Long applicationId);
}
