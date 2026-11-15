package com.recruitpro.verification.controller;

import com.recruitpro.common.exception.TooManyRequestsException;
import com.recruitpro.common.web.ClientIp;
import com.recruitpro.verification.service.GitHubOAuthService;
import com.recruitpro.verification.service.PublicEndpointRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Public GitHub OAuth callback (plan v2 section 5): the browser returns from GitHub without an access token; the
 * one-time state identifies the candidate. Always answers 302 to the candidate portal
 * ({@code /verification?connected=github} or {@code ?error=<code>}).
 */
@RestController
@RequestMapping("/api/v1/verification/oauth")
public class VerificationOAuthCallbackController {

    private final GitHubOAuthService gitHubOAuthService;
    private final PublicEndpointRateLimiter rateLimiter;

    public VerificationOAuthCallbackController(GitHubOAuthService gitHubOAuthService, PublicEndpointRateLimiter rateLimiter) {
        this.gitHubOAuthService = gitHubOAuthService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/github/callback")
    @Operation(summary = "GitHub OAuth redirect target (public); redirects to the candidate portal")
    public ResponseEntity<Void> gitHubCallback(@RequestParam(value = "code", required = false) String code,
                                               @RequestParam(value = "state", required = false) String state,
                                               @RequestParam(value = "error", required = false) String error,
                                               HttpServletRequest request) {
        String target;
        try {
            rateLimiter.check(PublicEndpointRateLimiter.OAUTH_CALLBACK, ClientIp.of(request));
            target = gitHubOAuthService.callback(code, state, error);
        } catch (TooManyRequestsException ex) {
            target = gitHubOAuthService.rateLimitedRedirect();
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).cacheControl(CacheControl.noStore()).build();
    }
}
