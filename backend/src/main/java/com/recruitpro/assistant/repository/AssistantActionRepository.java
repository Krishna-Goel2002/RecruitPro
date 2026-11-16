package com.recruitpro.assistant.repository;

import com.recruitpro.assistant.entity.AssistantAction;
import com.recruitpro.assistant.entity.AssistantActionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssistantActionRepository extends JpaRepository<AssistantAction, Long> {

    /** The action when it belongs to the user (ownership check: anything else is 404). */
    Optional<AssistantAction> findByIdAndUserId(Long id, Long userId);

    List<AssistantAction> findAllByIdIn(Collection<Long> ids);

    /**
     * Claims a PENDING, unexpired action for {@code to} (CONFIRMED or CANCELLED). Returns 1 for exactly one caller;
     * a second confirm (or a confirm racing a cancel) gets 0 and answers 409. Nothing is locked while the action runs.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AssistantAction a set a.status = :to, a.updatedAt = :now "
            + "where a.id = :id and a.status = :from and a.expiresAt > :now")
    int claim(@Param("id") Long id, @Param("from") AssistantActionStatus from, @Param("to") AssistantActionStatus to,
              @Param("now") LocalDateTime now);

    /** PENDING &rarr; EXPIRED once the expiry time has passed. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AssistantAction a set a.status = :expired, a.updatedAt = :now "
            + "where a.id = :id and a.status = :pending and a.expiresAt <= :now")
    int expire(@Param("id") Long id, @Param("pending") AssistantActionStatus pending,
               @Param("expired") AssistantActionStatus expired, @Param("now") LocalDateTime now);
}
