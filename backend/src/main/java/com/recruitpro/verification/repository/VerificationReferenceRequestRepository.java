package com.recruitpro.verification.repository;

import com.recruitpro.verification.entity.ReferenceStatus;
import com.recruitpro.verification.entity.VerificationReferenceRequest;
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

public interface VerificationReferenceRequestRepository extends JpaRepository<VerificationReferenceRequest, Long> {

    List<VerificationReferenceRequest> findAllByCandidateIdOrderByCreatedAtDescIdDesc(Long candidateId);

    List<VerificationReferenceRequest> findAllByIdIn(Collection<Long> ids);

    Optional<VerificationReferenceRequest> findByIdAndCandidateId(Long id, Long candidateId);

    Optional<VerificationReferenceRequest> findByTokenHash(String tokenHash);

    long countByCandidateIdAndStatusAndExpiresAtAfter(Long candidateId, ReferenceStatus status, LocalDateTime now);

    /** Requests created since {@code after} (R3-B04: daily cap, cancelled ones included). */
    long countByCandidateIdAndCreatedAtAfter(Long candidateId, LocalDateTime after);

    /** The oldest request inside the daily window (Retry-After of the daily cap). */
    Optional<VerificationReferenceRequest> findFirstByCandidateIdAndCreatedAtAfterOrderByCreatedAtAsc(Long candidateId,
                                                                                                    LocalDateTime after);

    /** The request behind a token, row-locked: answering, declining and cancelling are serialised (R3-B02). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from VerificationReferenceRequest r where r.tokenHash = :tokenHash")
    Optional<VerificationReferenceRequest> lockByTokenHash(@Param("tokenHash") String tokenHash);

    /** One request, row-locked (cancel; R3-B02). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from VerificationReferenceRequest r where r.id = :id")
    Optional<VerificationReferenceRequest> lockById(@Param("id") Long id);

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
