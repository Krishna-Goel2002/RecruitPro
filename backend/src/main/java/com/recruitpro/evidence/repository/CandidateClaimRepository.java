package com.recruitpro.evidence.repository;

import com.recruitpro.evidence.entity.CandidateClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CandidateClaimRepository extends JpaRepository<CandidateClaim, Long> {

    List<CandidateClaim> findAllByCandidateIdOrderByCreatedAtDescIdDesc(Long candidateId);

    List<CandidateClaim> findAllByCandidateIdOrderByIdAsc(Long candidateId);

    Optional<CandidateClaim> findByIdAndCandidateId(Long id, Long candidateId);

    List<CandidateClaim> findAllByCandidateIdAndSourceFactId(Long candidateId, Long sourceFactId);

    List<CandidateClaim> findAllByCandidateIdAndSourceFactIdIn(Long candidateId, Collection<Long> sourceFactIds);

    long countByCandidateId(Long candidateId);
}
