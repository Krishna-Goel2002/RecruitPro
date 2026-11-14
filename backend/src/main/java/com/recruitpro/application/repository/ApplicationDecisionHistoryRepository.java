package com.recruitpro.application.repository;

import com.recruitpro.application.entity.ApplicationDecisionHistory;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ApplicationDecisionHistoryRepository extends JpaRepository<ApplicationDecisionHistory, Long> {
    @EntityGraph(attributePaths = "actor")
    List<ApplicationDecisionHistory> findAllByApplicationIdOrderByCreatedAtAscIdAsc(Long applicationId);

    /** Every decision event of a job's applications with its actor, oldest first (job audit export, WP-BE-C). */
    @EntityGraph(attributePaths = "actor")
    @Query("select h from ApplicationDecisionHistory h where h.application.job.id = :jobId order by h.createdAt asc, h.id asc")
    List<ApplicationDecisionHistory> findAllForJobExport(@Param("jobId") Long jobId);
}
