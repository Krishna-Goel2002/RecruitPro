package com.recruitpro.assessment.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A file the candidate submitted with a work sample (WP-PLAT): pdf, zip, docx, pptx, png or jpg, at most 20 MB, at
 * most three per assignment. {@code storagePath} is the key in {@code WorkSampleFileStorage}; {@code contentType} is
 * derived from the verified file type, never taken from the client.
 */
@Entity
@Table(name = "work_sample_submission_files")
@Getter
@Setter
@NoArgsConstructor
public class WorkSampleSubmissionFile extends BaseEntity {

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;

    @Column(nullable = false, length = 64)
    private String sha256;
}
