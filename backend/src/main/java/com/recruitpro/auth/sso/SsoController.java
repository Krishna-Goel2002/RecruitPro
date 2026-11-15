package com.recruitpro.auth.sso;

import com.recruitpro.auth.web.Portal;
import com.recruitpro.auth.web.RefreshTokenCookies;
import com.recruitpro.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Staff single sign-on (public routes). The HR portal lists {@code /providers}, then sends the browser to
 * {@code /{provider}/start} (302 to the provider; with {@code Accept: application/json} the URL is returned as
 * {@code {authorizeUrl}} instead). The provider redirects to {@code /{provider}/callback}, which sets the
 * {@code rp_refresh_staff} cookie and redirects to {@code <hr-url>/sso/complete}, or to
 * {@code <hr-url>/login?ssoError=<code>}. The portal then calls {@code POST /api/v1/auth/refresh} with
 * {@code X-RecruitPro-Portal: staff} to obtain the access token.
 */
@RestController
@RequestMapping("/api/v1/auth/sso")
@Tag(name = "Authentication")
public class SsoController {

    private final SsoService ssoService;
    private final RefreshTokenCookies refreshTokenCookies;

    public SsoController(SsoService ssoService, RefreshTokenCookies refreshTokenCookies) {
        this.ssoService = ssoService;
        this.refreshTokenCookies = refreshTokenCookies;
    }

    @GetMapping("/providers")
    @Operation(summary = "Configured SSO providers: [{id, name}] (empty when SSO is not configured)")
    public ApiResponse<List<SsoProviderRegistry.ProviderSummary>> providers() {
        return ApiResponse.success(ssoService.providers());
    }

    @GetMapping("/{provider}/start")
    @Operation(summary = "Start SSO: 302 to the provider, or {authorizeUrl} with Accept: application/json")
    public ResponseEntity<?> start(@PathVariable("provider") String provider,
                                   @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        boolean json = accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains("application/json");
        try {
            String url = ssoService.start(provider);
            if (json) {
                return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(ApiResponse.success(Map.of("authorizeUrl", url)));
            }
            return redirect(url);
        } catch (SsoException ex) {
            if (json) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                        .body(ApiResponse.error("SSO_NOT_CONFIGURED: this sign-in option is not available"));
            }
            return redirect(ssoService.errorRedirect(ex.code()));
        }
    }

    @GetMapping("/{provider}/callback")
    @Operation(summary = "Provider redirect target: sets the staff refresh cookie and redirects to the HR portal")
    public ResponseEntity<Void> callback(@PathVariable("provider") String provider,
                                         @RequestParam(value = "code", required = false) String code,
                                         @RequestParam(value = "state", required = false) String state,
                                         @RequestParam(value = "error", required = false) String error,
                                         HttpServletResponse response) {
        SsoService.CallbackResult result = ssoService.callback(provider, code, state, error);
        if (result.session() != null && result.session().refreshToken() != null) {
            refreshTokenCookies.write(response, Portal.STAFF, result.session().refreshToken());
        }
        return redirect(result.redirectUrl());
    }

    private static <T> ResponseEntity<T> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).cacheControl(CacheControl.noStore()).build();
    }
}
