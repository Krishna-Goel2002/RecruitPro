package com.recruitpro.auth.mfa;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserMfaRepository extends JpaRepository<UserMfa, Long> {

    /** Locks the user's MFA row while a code is checked, so the same code cannot be accepted twice concurrently. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from UserMfa m where m.userId = :userId")
    Optional<UserMfa> lockByUserId(@Param("userId") Long userId);
}
