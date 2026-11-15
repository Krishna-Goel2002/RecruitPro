package com.recruitpro.auth.mfa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserRecoveryCodeRepository extends JpaRepository<UserRecoveryCode, Long> {

    List<UserRecoveryCode> findAllByUserIdAndUsedAtIsNullOrderByIdAsc(Long userId);

    long countByUserIdAndUsedAtIsNull(Long userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from UserRecoveryCode c where c.userId = :userId")
    int deleteAllByUser(@Param("userId") Long userId);
}
