package com.recruitpro.candidate.entity;

import com.recruitpro.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "candidate_resumes")
@Getter @Setter @NoArgsConstructor
public class Resume extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private CandidateProfile candidate;
    @Column(name = "storage_key", nullable = false, unique = true, length = 36) private String storageKey;
    @Column(name = "original_filename", nullable = false, length = 255) private String originalFilename;
    @Column(name = "file_extension", nullable = false, length = 10) private String fileExtension;
    @Column(name = "content_type", nullable = false, length = 100) private String contentType;
    @Column(name = "file_size", nullable = false) private long fileSize;
    @Column(name = "uploaded_at", nullable = false) private LocalDateTime uploadedAt;
}
