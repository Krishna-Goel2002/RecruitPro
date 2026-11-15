package com.recruitpro.verification.repository;

import com.recruitpro.verification.entity.EvidenceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EvidenceItemRepository extends JpaRepository<EvidenceItem, Long> {

    List<EvidenceItem> findAllBySourceId(Long sourceId);

    List<EvidenceItem> findAllByRunIdOrderByIdAsc(Long runId);

    List<EvidenceItem> findAllByCandidateIdOrderByIdAsc(Long candidateId);

    @Modifying(flushAutomatically = true)
    @Query("delete from EvidenceItem e where e.sourceId = :sourceId")
    int deleteAllBySource(@Param("sourceId") Long sourceId);

    @Modifying(flushAutomatically = true)
    @Query("delete from EvidenceItem e where e.candidateId = :candidateId")
    int deleteAllByCandidate(@Param("candidateId") Long candidateId);
}
