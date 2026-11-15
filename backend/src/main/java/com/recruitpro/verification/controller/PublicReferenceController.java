package com.recruitpro.verification.controller;

import com.recruitpro.common.dto.ApiResponse;
import com.recruitpro.common.web.ClientIp;
import com.recruitpro.verification.dto.PublicReferenceResponse;
import com.recruitpro.verification.dto.PublicReferenceSubmitRequest;
import com.recruitpro.verification.service.PublicEndpointRateLimiter;
import com.recruitpro.verification.service.ReferenceService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The referee's page (public, no account, rate-limited per client IP): read the request and answer it once, or
 * decline. 404 for an unknown token, 410 once expired, answered, declined or cancelled.
 */
@RestController
@RequestMapping("/api/v1/public/references")
public class PublicReferenceController {

    private final ReferenceService referenceService;
    private final PublicEndpointRateLimiter rateLimiter;

    public PublicReferenceController(ReferenceService referenceService, PublicEndpointRateLimiter rateLimiter) {
        this.referenceService = referenceService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/{token}")
    @Operation(summary = "A reference request as the referee sees it")
    public ApiResponse<PublicReferenceResponse> view(@PathVariable String token, HttpServletRequest request) {
        rateLimiter.check(PublicEndpointRateLimiter.REFERENCE_VIEW, ClientIp.of(request));
        return ApiResponse.success(referenceService.view(token));
    }

    @PostMapping("/{token}")
    @Operation(summary = "Answer (confirmIdentity: true) or decline (decline: true) a reference request")
    public ApiResponse<Void> submit(@PathVariable String token, @RequestBody PublicReferenceSubmitRequest body,
                                    HttpServletRequest request) {
        rateLimiter.check(PublicEndpointRateLimiter.REFERENCE_SUBMIT, ClientIp.of(request));
        referenceService.submit(token, body);
        return ApiResponse.success(Boolean.TRUE.equals(body == null ? null : body.decline())
                ? "Thank you. The request was declined." : "Thank you for your answers.");
    }
}
