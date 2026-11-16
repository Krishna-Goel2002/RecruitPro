package com.recruitpro.assistant.repository;

import com.recruitpro.assistant.entity.AssistantConversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AssistantConversationRepository extends JpaRepository<AssistantConversation, Long> {

    /** The conversation when it belongs to the user (ownership check: anything else is 404). */
    Optional<AssistantConversation> findByIdAndUserId(Long id, Long userId);

    Page<AssistantConversation> findAllByUserId(Long userId, Pageable pageable);

    /**
     * Names and e-mail addresses of candidates whose identity a member of the organisation can know (revealed
     * applications, or every application when {@code allRevealed} because blind review is off), newest first:
     * {@code [firstName, lastName, email]}. Used only to strip them from text sent to the planner.
     */
    @Query("select u.firstName, u.lastName, u.email from Application a join a.candidate c join c.user u "
            + "where a.job.organizationId = :organizationId and (a.identityRevealedAt is not null or :allRevealed = true) "
            + "order by a.id desc")
    List<Object[]> findKnownCandidateNames(@Param("organizationId") Long organizationId,
                                           @Param("allRevealed") boolean allRevealed, Pageable pageable);
}
