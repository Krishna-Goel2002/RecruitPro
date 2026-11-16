package com.recruitpro.assessment.repository;

import com.recruitpro.assessment.entity.WorkSampleAssignment;
import com.recruitpro.assessment.entity.WorkSampleAssignmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorkSampleAssignmentRepository extends JpaRepository<WorkSampleAssignment, Long> {

    boolean existsByWorkSampleId(Long workSampleId);

    long countByWorkSampleId(Long workSampleId);

    /** [workSampleId, count] for every sample of a job that has assignments (work-sample list). */
    @Query("select a.workSample.id, count(a) from WorkSampleAssignment a where a.workSample.job.id = :jobId "
            + "group by a.workSample.id")
    List<Object[]> countByWorkSampleForJob(@Param("jobId") Long jobId);

    boolean existsByWorkSampleIdAndApplicationId(Long workSampleId, Long applicationId);

    /** Every assignment of a job's work samples, newest first (HR assignment list). */
    @Query("select a from WorkSampleAssignment a join fetch a.workSample ws where ws.job.id = :jobId "
            + "order by a.assignedAt desc, a.id desc")
    List<WorkSampleAssignment> findAllForJob(@Param("jobId") Long jobId);

    @Query("select a from WorkSampleAssignment a join fetch a.workSample ws where ws.job.id = :jobId and a.status = :status "
            + "order by a.assignedAt desc, a.id desc")
    List<WorkSampleAssignment> findAllForJobAndStatus(@Param("jobId") Long jobId,
                                                      @Param("status") WorkSampleAssignmentStatus status);

    /** Assignments of one application with their samples, oldest first. */
    @Query("select a from WorkSampleAssignment a join fetch a.workSample where a.application.id = :applicationId "
            + "order by a.assignedAt asc, a.id asc")
    List<WorkSampleAssignment> findAllForApplication(@Param("applicationId") Long applicationId);

    /** Assignments of one application in one status, with their samples (evidence contributor). */
    @Query("select a from WorkSampleAssignment a join fetch a.workSample where a.application.id = :applicationId "
            + "and a.status = :status order by a.id asc")
    List<WorkSampleAssignment> findAllForApplicationAndStatus(@Param("applicationId") Long applicationId,
                                                              @Param("status") WorkSampleAssignmentStatus status);

    /** Every assignment of a candidate with sample, job and application, newest first (candidate portal, export). */
    @Query("select a from WorkSampleAssignment a join fetch a.workSample ws join fetch ws.job join fetch a.application app "
            + "where app.candidate.id = :candidateId order by a.assignedAt desc, a.id desc")
    List<WorkSampleAssignment> findAllForCandidate(@Param("candidateId") Long candidateId);

    /** Statuses for a page of applicants, oldest assignment first. */
    @Query("select new com.recruitpro.assessment.repository.AssignmentStatusRow(a.application.id, a.id, a.status) "
            + "from WorkSampleAssignment a where a.application.id in :applicationIds order by a.assignedAt asc, a.id asc")
    List<AssignmentStatusRow> findStatusesByApplicationIds(@Param("applicationIds") Collection<Long> applicationIds);

    /** Locks one assignment row (start, submit, score). Plain query, no joins. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from WorkSampleAssignment a where a.id = :id")
    Optional<WorkSampleAssignment> lockById(@Param("id") Long id);

    /** Candidate of an assignment (ownership check before locking). */
    @Query("select a.application.candidate.id from WorkSampleAssignment a where a.id = :id")
    Optional<Long> findCandidateIdById(@Param("id") Long id);

    /** Job of an assignment (tenancy check of the score endpoint). */
    @Query("select a.workSample.job.id from WorkSampleAssignment a where a.id = :id")
    Optional<Long> findJobIdById(@Param("id") Long id);

    /** Started assignments with their samples (expiry sweep). */
    @Query("select a from WorkSampleAssignment a join fetch a.workSample where a.status = :status and a.startedAt is not null")
    List<WorkSampleAssignment> findStartedWithSample(@Param("status") WorkSampleAssignmentStatus status);

    /** Assignments in a status whose due date passed (expiry sweep). */
    @Query("select a.id from WorkSampleAssignment a where a.status = :status and a.dueAt is not null and a.dueAt < :now")
    List<Long> findIdsDueBefore(@Param("status") WorkSampleAssignmentStatus status, @Param("now") LocalDateTime now);

    /** Moves one assignment from {@code expected} to {@code target}; 0 when it was changed meanwhile. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update WorkSampleAssignment a set a.status = :target, a.updatedAt = :now where a.id = :id and a.status = :expected")
    int transition(@Param("id") Long id, @Param("expected") WorkSampleAssignmentStatus expected,
                   @Param("target") WorkSampleAssignmentStatus target, @Param("now") LocalDateTime now);
}
