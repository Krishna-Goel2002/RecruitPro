package com.recruitpro.assistant.repository;

import com.recruitpro.assistant.entity.AssistantMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, Long> {

    List<AssistantMessage> findAllByConversationIdOrderByIdAsc(Long conversationId);

    /** Newest first; the caller reverses the page (last N messages for the planner). */
    List<AssistantMessage> findByConversationIdOrderByIdDesc(Long conversationId, Pageable pageable);
}
