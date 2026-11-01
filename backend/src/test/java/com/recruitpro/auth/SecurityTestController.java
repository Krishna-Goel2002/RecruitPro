package com.recruitpro.auth;

import com.recruitpro.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test/security")
@SecurityRequirement(name = "bearerAuth")
class SecurityTestController {
    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<String> admin() { return ApiResponse.success("ok"); }

    @GetMapping("/hr-manager")
    @PreAuthorize("hasRole('HR_MANAGER')")
    ApiResponse<String> hrManager() { return ApiResponse.success("ok"); }

    @GetMapping("/recruiter")
    @PreAuthorize("hasRole('RECRUITER')")
    ApiResponse<String> recruiter() { return ApiResponse.success("ok"); }

    @GetMapping("/interviewer")
    @PreAuthorize("hasRole('INTERVIEWER')")
    ApiResponse<String> interviewer() { return ApiResponse.success("ok"); }

    @GetMapping("/candidate")
    @PreAuthorize("hasRole('CANDIDATE')")
    ApiResponse<String> candidate() { return ApiResponse.success("ok"); }
}
