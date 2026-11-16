package com.recruitpro.auth.repository;

import com.recruitpro.auth.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from RefreshToken token join fetch token.user where token.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    boolean existsByFamilyIdAndRevokedAtIsNull(String familyId);

    List<RefreshToken> findAllByFamilyIdOrderByIdAsc(String familyId);

    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now, t.revokedReason = :reason, t.updatedAt = :now "
            + "where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId, @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now, t.revokedReason = :reason, t.updatedAt = :now "
            + "where t.user.id = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") Long userId, @Param("reason") String reason, @Param("now") LocalDateTime now);

    /** The first token of a family (its sign-in time, R1-U2). */
    Optional<RefreshToken> findFirstByFamilyIdOrderByIdAsc(String familyId);
}
