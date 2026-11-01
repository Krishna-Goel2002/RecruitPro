package com.recruitpro.application.repository;

import com.recruitpro.application.entity.ApplicationDecisionHistory;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ApplicationDecisionHistoryRepository extends JpaRepository<ApplicationDecisionHistory, Long> {
    @EntityGraph(attributePaths = "actor")
    List<ApplicationDecisionHistory> findAllByApplicationIdOrderByCreatedAtAscIdAsc(Long applicationId);
}
