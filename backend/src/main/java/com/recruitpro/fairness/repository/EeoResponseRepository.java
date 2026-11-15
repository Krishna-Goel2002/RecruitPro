package com.recruitpro.fairness.repository;

import com.recruitpro.fairness.entity.EeoResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Survey answers (D14) and the aggregate inputs of fairness reports. Nothing here is exposed to reviewers; only
 * {@code FairnessReportService} reads answers of other people, and it only returns aggregates.
 */
public interface EeoResponseRepository extends JpaRepository<EeoResponse, Long> {

    Optional<EeoResponse> findByCandidateId(Long candidateId);

    @Modifying(flushAutomatically = true)
    @Query("delete from EeoResponse e where e.candidateId = :candidateId")
    int deleteAllByCandidate(@Param("candidateId") Long candidateId);

    @Query("select e from EeoResponse e where e.candidateId in "
            + "(select a.candidate.id from Application a where a.job.id = :jobId)")
    List<EeoResponse> findAllForJob(@Param("jobId") Long jobId);

    @Query("select e from EeoResponse e where e.candidateId in "
            + "(select a.candidate.id from Application a where a.job.organizationId = :organizationId)")
    List<EeoResponse> findAllForOrganization(@Param("organizationId") Long organizationId);

    @Query("select new com.recruitpro.fairness.repository.ApplicantStageRow(a.id, a.candidate.id, a.status) "
            + "from Application a where a.job.id = :jobId")
    List<ApplicantStageRow> findApplicantsOfJob(@Param("jobId") Long jobId);

    @Query("select new com.recruitpro.fairness.repository.ApplicantStageRow(a.id, a.candidate.id, a.status) "
            + "from Application a where a.job.organizationId = :organizationId")
    List<ApplicantStageRow> findApplicantsOfOrganization(@Param("organizationId") Long organizationId);

    /** Applicants of a job whose application was created in [from, toExclusive) (WP-PLAT date filter). */
    @Query("select new com.recruitpro.fairness.repository.ApplicantStageRow(a.id, a.candidate.id, a.status) "
            + "from Application a where a.job.id = :jobId and a.createdAt >= :from and a.createdAt < :toExclusive")
    List<ApplicantStageRow> findApplicantsOfJobCreatedBetween(@Param("jobId") Long jobId, @Param("from") LocalDateTime from,
                                                              @Param("toExclusive") LocalDateTime toExclusive);

    @Query("select new com.recruitpro.fairness.repository.ApplicantStageRow(a.id, a.candidate.id, a.status) "
            + "from Application a where a.job.organizationId = :organizationId and a.createdAt >= :from "
            + "and a.createdAt < :toExclusive")
    List<ApplicantStageRow> findApplicantsOfOrganizationCreatedBetween(@Param("organizationId") Long organizationId,
                                                                       @Param("from") LocalDateTime from,
                                                                       @Param("toExclusive") LocalDateTime toExclusive);

    @Query("select new com.recruitpro.fairness.repository.StatusEventRow(h.application.id, h.previousStatus, h.newStatus) "
            + "from ApplicationDecisionHistory h where h.application.job.id = :jobId")
    List<StatusEventRow> findStatusEventsOfJob(@Param("jobId") Long jobId);

    @Query("select new com.recruitpro.fairness.repository.StatusEventRow(h.application.id, h.previousStatus, h.newStatus) "
            + "from ApplicationDecisionHistory h where h.application.job.organizationId = :organizationId")
    List<StatusEventRow> findStatusEventsOfOrganization(@Param("organizationId") Long organizationId);
}
