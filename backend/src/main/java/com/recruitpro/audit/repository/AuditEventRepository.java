package com.recruitpro.audit.repository;

import com.recruitpro.audit.entity.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    Page<AuditEvent> findAllByOrganizationId(Long organizationId, Pageable pageable);

    Page<AuditEvent> findAllByOrganizationIdAndAction(Long organizationId, String action, Pageable pageable);

    List<AuditEvent> findAllByOrganizationIdAndCreatedAtBetween(Long organizationId, LocalDateTime from,
                                                                LocalDateTime to, Pageable pageable);

    List<AuditEvent> findAllByActionOrderByIdAsc(String action);

    List<AuditEvent> findAllByOrganizationIdAndActionOrderByIdAsc(Long organizationId, String action);

    List<AuditEvent> findAllByActorUserIdOrderByIdAsc(Long actorUserId);

    /**
     * Candidate account deletion: the audit rows stay (actor id, action, target, time) but the actor's email is
     * replaced by the anonymised address. Rows are never deleted.
     */
    @Modifying(flushAutomatically = true)
    @Query("update AuditEvent e set e.actorEmail = :email where e.actorUserId = :userId")
    int pseudonymiseActor(@Param("userId") Long userId, @Param("email") String email);
}
