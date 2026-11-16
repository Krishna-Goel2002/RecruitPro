package com.recruitpro.assistant.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * A write the assistant proposed (V29). It runs only when its owner confirms it (re-validated, as that user).
 * {@code argsJson}: the validated, normalised tool arguments; {@code previewJson}: what the user was shown
 * ({@code summary} plus the tool's preview fields); {@code resultJson}: {kind, id, link} or {kind: "failed", error}.
 */
@Entity
@Table(name = "assistant_actions")
@Getter
@Setter
@NoArgsConstructor
public class AssistantAction extends BaseEntity {

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(name = "args_json", nullable = false, columnDefinition = "TEXT")
    private String argsJson;

    @Column(name = "preview_json", columnDefinition = "TEXT")
    private String previewJson;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AssistantActionStatus status = AssistantActionStatus.PENDING;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    /** PENDING but past its expiry time (shown and treated as EXPIRED). */
    public boolean isExpired(LocalDateTime now) {
        return status == AssistantActionStatus.PENDING && expiresAt != null && !expiresAt.isAfter(now);
    }
}
