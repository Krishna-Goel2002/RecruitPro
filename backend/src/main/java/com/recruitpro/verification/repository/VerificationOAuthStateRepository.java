package com.recruitpro.verification.repository;

import com.recruitpro.verification.entity.VerificationOAuthState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface VerificationOAuthStateRepository extends JpaRepository<VerificationOAuthState, Long> {

    Optional<VerificationOAuthState> findByStateHash(String stateHash);

    /** Single use: only the first caller marks the state used (returns 1). */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update VerificationOAuthState s set s.usedAt = :now where s.id = :id and s.usedAt is null")
    int markUsed(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true)
    @Query("delete from VerificationOAuthState s where s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);

    @Modifying(flushAutomatically = true)
    @Query("delete from VerificationOAuthState s where s.candidateId = :candidateId")
    int deleteAllByCandidate(@Param("candidateId") Long candidateId);
}
