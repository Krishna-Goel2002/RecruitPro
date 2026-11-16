package com.recruitpro.assistant.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A staff user's assistant conversation (V29). Only its owner can read, continue or delete it. {@code draftJson} keeps
 * the job-posting slot-filling state between messages (the planner is stateless).
 */
@Entity
@Table(name = "assistant_conversations")
@Getter
@Setter
@NoArgsConstructor
public class AssistantConversation extends BaseEntity {

    public static final String DEFAULT_TITLE = "New conversation";

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String title = DEFAULT_TITLE;

    @Column(name = "draft_json", columnDefinition = "TEXT")
    private String draftJson;
}
