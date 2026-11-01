package com.recruitpro.candidate.dto;

import com.recruitpro.candidate.entity.Resume;
import java.time.LocalDateTime;

public record ResumeResponse(Long id, String originalFilename, String contentType, long fileSize,
                             LocalDateTime uploadedAt) {
    public static ResumeResponse from(Resume resume) {
        return new ResumeResponse(resume.getId(), resume.getOriginalFilename(), resume.getContentType(),
                resume.getFileSize(), resume.getUploadedAt());
    }
}
