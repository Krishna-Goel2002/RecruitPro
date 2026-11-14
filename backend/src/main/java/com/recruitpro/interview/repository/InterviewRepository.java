package com.recruitpro.interview.repository;

import com.recruitpro.interview.entity.Interview;
import com.recruitpro.interview.entity.InterviewStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InterviewRepository extends JpaRepository<Interview, Long> {

    /** One interview with its application and job (tenancy is checked on the job's organisation). */
    @Query("select i from Interview i join fetch i.application a join fetch a.job where i.id = :id")
    Optional<Interview> findWithApplicationById(@Param("id") Long id);

    /** Organisation of an interview's job (tenancy check before locking). */
    @Query("select i.application.job.organizationId from Interview i where i.id = :id")
    Optional<Long> findOrganizationIdById(@Param("id") Long id);

    /** Locks one interview row (reschedule, cancel, scorecard). Plain query, no joins. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Interview i where i.id = :id")
    Optional<Interview> lockById(@Param("id") Long id);

    /** Interviews assigned to a staff user within their organisation, soonest first. */
    @Query("select i from Interview i join fetch i.application a join fetch a.job j "
            + "where i.interviewerUserId = :userId and j.organizationId = :organizationId order by i.scheduledAt asc, i.id asc")
    List<Interview> findAssigned(@Param("userId") Long userId, @Param("organizationId") Long organizationId);

    @Query("select i from Interview i where i.application.id = :applicationId order by i.scheduledAt asc, i.id asc")
    List<Interview> findAllForApplication(@Param("applicationId") Long applicationId);

    List<Interview> findAllByApplicationIdAndStatus(Long applicationId, InterviewStatus status);

    /** Every interview of a candidate with application and job (candidate portal, export). */
    @Query("select i from Interview i join fetch i.application a join fetch a.job where a.candidate.id = :candidateId "
            + "order by i.scheduledAt asc, i.id asc")
    List<Interview> findAllForCandidate(@Param("candidateId") Long candidateId);

    @Query("select new com.recruitpro.interview.repository.InterviewCountRow(i.application.id, count(i)) from Interview i "
            + "where i.application.id in :applicationIds and i.status <> :excluded group by i.application.id")
    List<InterviewCountRow> countByApplicationIds(@Param("applicationIds") Collection<Long> applicationIds,
                                                  @Param("excluded") InterviewStatus excluded);
}
