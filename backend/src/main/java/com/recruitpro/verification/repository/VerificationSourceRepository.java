package com.recruitpro.verification.repository;

import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VerificationSourceRepository extends JpaRepository<VerificationSource, Long> {

    List<VerificationSource> findAllByCandidateIdAndRevokedAtIsNullOrderByCreatedAtDescIdDesc(Long candidateId);

    List<VerificationSource> findAllByCandidateIdAndKindAndRevokedAtIsNull(Long candidateId, VerificationSourceKind kind);

    /** Active (non-revoked) sources of a candidate (R3-B06 cap). */
    long countByCandidateIdAndRevokedAtIsNull(Long candidateId);

    Optional<VerificationSource> findByIdAndCandidateId(Long id, Long candidateId);

    List<VerificationSource> findAllByCandidateIdOrderByIdAsc(Long candidateId);

    @Modifying(flushAutomatically = true)
    @Query("delete from VerificationSource s where s.candidateId = :candidateId")
    int deleteAllByCandidate(@Param("candidateId") Long candidateId);
}
