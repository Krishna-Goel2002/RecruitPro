package com.recruitpro.verification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * One piece of evidence behind a verdict (principle 1): source URL (or file), retrieval time, SHA-256 of the
 * normalised snapshot text and the snapshot itself (in storage, {@code snapshot_path}). Immutable once stored.
 */
@Entity
@Table(name = "evidence_items")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class EvidenceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for ownership proofs and reference answers. */
    @Column(name = "run_id")
    private Long runId;

    /** Null for reference answers. */
    @Column(name = "source_id")
    private Long sourceId;

    /** Set for reference answers only. */
    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Column(name = "item_key", nullable = false, length = 40)
    private String itemKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private EvidenceItemKind kind;

    @Column(length = 1000)
    private String url;

    @Column(length = 300)
    private String title;

    @Column(name = "retrieved_at", nullable = false)
    private LocalDateTime retrievedAt;

    /** {@code sha256:<hex>} of the UTF-8 snapshot text. */
    @Column(name = "content_hash", nullable = false, length = 80)
    private String contentHash;

    @Column(name = "snapshot_path", length = 500)
    private String snapshotPath;

    @Column(length = 1000)
    private String excerpt;

    @Column(name = "attributes_json", columnDefinition = "TEXT")
    private String attributesJson;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
