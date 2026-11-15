package com.recruitpro.auth.sso;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SsoLoginStateRepository extends JpaRepository<SsoLoginState, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SsoLoginState s where s.stateHash = :stateHash")
    Optional<SsoLoginState> lockByStateHash(@Param("stateHash") String stateHash);

    /** Housekeeping: states that expired before the cutoff. */
    @Modifying(flushAutomatically = true)
    @Query("delete from SsoLoginState s where s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
