package com.recruitpro.auth.repository;

import com.recruitpro.auth.entity.UserToken;
import com.recruitpro.auth.entity.UserTokenPurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from UserToken t join fetch t.user where t.tokenHash = :tokenHash and t.purpose = :purpose")
    Optional<UserToken> findForUpdate(@Param("tokenHash") String tokenHash, @Param("purpose") UserTokenPurpose purpose);

    @Modifying(flushAutomatically = true)
    @Query("update UserToken t set t.usedAt = :now, t.updatedAt = :now "
            + "where t.user.id = :userId and t.purpose = :purpose and t.usedAt is null")
    int invalidateOutstanding(@Param("userId") Long userId, @Param("purpose") UserTokenPurpose purpose,
                              @Param("now") LocalDateTime now);

    List<UserToken> findAllByUserIdAndPurposeOrderByIdAsc(Long userId, UserTokenPurpose purpose);

    /** Account deletion: removes every one-time token of the user. */
    @Modifying(flushAutomatically = true)
    @Query("delete from UserToken t where t.user.id = :userId")
    int deleteAllForUser(@Param("userId") Long userId);
}
