package com.recruitpro.verification.controller;

import com.recruitpro.common.exception.TooManyRequestsException;
import com.recruitpro.common.web.ClientIp;
import com.recruitpro.verification.service.GitHubOAuthBindingCookie;
import com.recruitpro.verification.service.GitHubOAuthService;
import com.recruitpro.verification.service.PublicEndpointRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Public GitHub OAuth callback (plan v2 section 5): the browser returns from GitHub without an access token; the
 * one-time state identifies the candidate and must come with the browser-binding cookie set by {@code start}. Always answers 302 to the candidate portal
 * ({@code /verification?connected=github} or {@code ?error=<code>}).
 */
@RestController
@RequestMapping("/api/v1/verification/oauth")
public class VerificationOAuthCallbackController {

    private final GitHubOAuthService gitHubOAuthService;
    private final PublicEndpointRateLimiter rateLimiter;
    private final GitHubOAuthBindingCookie oauthCookie;

    public VerificationOAuthCallbackController(GitHubOAuthService gitHubOAuthService, PublicEndpointRateLimiter rateLimiter,
                                               GitHubOAuthBindingCookie oauthCookie) {
        this.gitHubOAuthService = gitHubOAuthService;
        this.rateLimiter = rateLimiter;
        this.oauthCookie = oauthCookie;
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
            target = gitHubOAuthService.callback(code, state, error, oauthCookie.read(request));
        } catch (TooManyRequestsException ex) {
            target = gitHubOAuthService.rateLimitedRedirect();
        }
        // The browser-binding cookie is single use: cleared on every answer.
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, oauthCookie.expired().toString())
                .build();
    }
}
