package com.recruitpro.interview.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The version of a scorecard that an edit replaced (WP-PLAT): {@code snapshotJson} holds
 * {@code {ratings, recommendation, summary, submittedAt, updatedAt}}; {@code revisedBy} is the editor's user id.
 */
@Entity
@Table(name = "interview_scorecard_revisions")
@Getter
@Setter
@NoArgsConstructor
public class InterviewScorecardRevision extends BaseEntity {

    @Column(name = "scorecard_id", nullable = false)
    private Long scorecardId;

    @Column(name = "revised_by")
    private Long revisedBy;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;
}
