package com.recruitpro.verification.repository;

import com.recruitpro.application.entity.Application;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** Read-only application lookups of the verification package (kept here so shared repositories stay untouched). */
public interface VerificationLookupRepository extends Repository<Application, Long> {

    /** Candidates with at least one application to the organisation's jobs. */
    @Query("select distinct a.candidate.id from Application a where a.job.organizationId = :organizationId")
    List<Long> findCandidateIdsByOrganizationId(@Param("organizationId") Long organizationId);

    /** The candidate's applications to the organisation's jobs, newest first (job loaded). */
    @Query("select a from Application a join fetch a.job j where a.candidate.id = :candidateId "
            + "and j.organizationId = :organizationId order by a.id desc")
    List<Application> findByCandidateAndOrganization(@Param("candidateId") Long candidateId,
                                                     @Param("organizationId") Long organizationId);

    /** Applications of several candidates to the organisation's jobs (job loaded). */
    @Query("select a from Application a join fetch a.job j where a.candidate.id in :candidateIds "
            + "and j.organizationId = :organizationId order by a.id desc")
    List<Application> findByCandidatesAndOrganization(@Param("candidateIds") Collection<Long> candidateIds,
                                                      @Param("organizationId") Long organizationId);
}
