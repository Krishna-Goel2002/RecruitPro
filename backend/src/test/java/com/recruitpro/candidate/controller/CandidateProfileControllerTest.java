package com.recruitpro.candidate.controller;

import com.recruitpro.candidate.dto.CandidateProfileResponse;
import com.recruitpro.candidate.dto.CandidateProfileUpdateRequest;
import com.recruitpro.candidate.service.CandidateProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CandidateProfileControllerTest {
    private static final CandidateProfileResponse PROFILE = new CandidateProfileResponse(
            42L, "candidate@example.test", "Asha", "Rao", null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void profileAliasReturnsTheAuthenticatedProfileResponse() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "candidate@example.test", "unused-test-credentials", "ROLE_CANDIDATE"));
        CandidateProfileService service = new CandidateProfileService(null, null) {
            @Override public CandidateProfileResponse getMine() { return PROFILE; }
            @Override public CandidateProfileResponse updateMine(CandidateProfileUpdateRequest request) { return PROFILE; }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CandidateProfileController(service)).build();

        mvc.perform(get("/api/v1/candidates/me/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(42)))
                .andExpect(jsonPath("$.data.email", is("candidate@example.test")));
        mvc.perform(get("/api/v1/candidates/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(42)));
    }

    @Test
    void profileRoutesRemainCandidateOnly() {
        PreAuthorize authorization = CandidateProfileController.class.getAnnotation(PreAuthorize.class);

        assertNotNull(authorization);
        assertEquals("hasRole('CANDIDATE')", authorization.value());
    }
}
