package com.recruitpro.verification.repository;

import com.recruitpro.verification.entity.ReferenceStatus;
import com.recruitpro.verification.entity.VerificationReferenceRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VerificationReferenceRequestRepository extends JpaRepository<VerificationReferenceRequest, Long> {

    List<VerificationReferenceRequest> findAllByCandidateIdOrderByCreatedAtDescIdDesc(Long candidateId);

    List<VerificationReferenceRequest> findAllByIdIn(Collection<Long> ids);

    Optional<VerificationReferenceRequest> findByIdAndCandidateId(Long id, Long candidateId);

    Optional<VerificationReferenceRequest> findByTokenHash(String tokenHash);

    long countByCandidateIdAndStatusAndExpiresAtAfter(Long candidateId, ReferenceStatus status, LocalDateTime now);

    /** SENT requests past their expiry become EXPIRED. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update VerificationReferenceRequest r set r.status = :expired, r.updatedAt = :now "
            + "where r.status = :sent and r.expiresAt < :now")
    int expireOverdue(@Param("sent") ReferenceStatus sent, @Param("expired") ReferenceStatus expired,
                      @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true)
    @Query("delete from VerificationReferenceRequest r where r.candidateId = :candidateId")
    int deleteAllByCandidate(@Param("candidateId") Long candidateId);
}
