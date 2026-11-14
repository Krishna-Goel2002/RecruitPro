package com.recruitpro.interview.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The interview kit of a job (one per job). {@code questionsJson} holds {@code InterviewQuestion[]}: id, linked
 * requirement (or null), question, follow-ups and anchors "1".."4". {@code generatedBy}: the AI engine ("rules" or
 * "llm") or "manual" after an edit.
 */
@Entity
@Table(name = "interview_kits")
@Getter
@Setter
@NoArgsConstructor
public class InterviewKit extends BaseEntity {

    @Column(name = "job_id", nullable = false, unique = true)
    private Long jobId;

    @Column(name = "questions_json", nullable = false, columnDefinition = "TEXT")
    private String questionsJson;

    @Column(name = "generated_by", length = 40)
    private String generatedBy;
}
