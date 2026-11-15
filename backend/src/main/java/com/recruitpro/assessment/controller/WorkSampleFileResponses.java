package com.recruitpro.assessment.controller;

import com.recruitpro.assessment.service.WorkSampleFileService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/** Download responses for work-sample files: attachment, stored content type, no caching, no sniffing. */
final class WorkSampleFileResponses {

    private WorkSampleFileResponses() {
    }

    static ResponseEntity<byte[]> of(WorkSampleFileService.Download download) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.file().getOriginalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.file().getContentType()))
                .contentLength(download.content().length)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(download.content());
    }
}
