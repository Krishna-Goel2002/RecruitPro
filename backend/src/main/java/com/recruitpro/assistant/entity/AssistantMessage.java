package com.recruitpro.assistant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One message of a conversation (V29). {@code role} is {@link #ROLE_USER} or {@link #ROLE_ASSISTANT};
 * {@code blocksJson} the JSON array of UI blocks; {@code planner} the planner that produced an assistant message.
 * Messages are never changed after they are written (no updated_at).
 */
@Entity
@Table(name = "assistant_messages")
@Getter
@Setter
@NoArgsConstructor
public class AssistantMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "blocks_json", columnDefinition = "TEXT")
    private String blocksJson;

    @Column(length = 40)
    private String planner;

    @Column(name = "status_trace", length = 1000)
    private String statusTrace;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
