package com.recruitpro.hr.service;

import com.recruitpro.ai.dto.AiCompileRequest;
import com.recruitpro.ai.dto.AiCompileResponse;
import com.recruitpro.ai.service.AiServiceClient;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.hr.dto.CompileRequirementsRequest;
import com.recruitpro.hr.dto.CompileRequirementsResponse;
import com.recruitpro.hr.entity.ExperienceLevel;
import com.recruitpro.hr.entity.Proficiency;
import com.recruitpro.hr.entity.RequirementType;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** POST /api/v1/jobs/compile-requirements proxies the AI compiler and never persists. */
class RequirementCompilerServiceTest {

    @Test
    void mapsSuggestionsAndWarnings() {
        AtomicReference<AiCompileRequest> sent = new AtomicReference<>();
        RequirementCompilerService service = service(sent, () -> new AiCompileResponse("rules",
                List.of(new AiCompileResponse.Requirement("EXPERIENCE", "GST and Tally", true, false, 3, List.of(), null,
                                "listed under requirements"),
                        new AiCompileResponse.Requirement("SKILL", "SAP FICO", false, true, null, List.of("QuickBooks"),
                                "advanced", "nice to have"),
                        new AiCompileResponse.Requirement("UNKNOWN", "Team player", null, null, null, null, null, null)),
                List.of(new AiCompileResponse.Warning("Young", "AGE_CODED", "Describe the work instead."))));

        CompileRequirementsResponse result = service.compile(new CompileRequirementsRequest(" Accountant ",
                " Requirements: 3+ years of GST ", ExperienceLevel.MID));

        assertEquals("Accountant", sent.get().title());
        assertEquals("MID", sent.get().experienceLevel());
        assertEquals("rules", result.engine());
        assertEquals(3, result.requirements().size());
        assertEquals(RequirementType.EXPERIENCE, result.requirements().get(0).type());
        assertEquals(3, result.requirements().get(0).minimumYears());
        assertFalse(result.requirements().get(1).knockout(), "a nice-to-have is never a knockout");
        assertEquals(Proficiency.ADVANCED, result.requirements().get(1).proficiency());
        assertEquals(List.of("QuickBooks"), result.requirements().get(1).alternatives());
        assertEquals(RequirementType.OTHER, result.requirements().get(2).type());
        assertTrue(result.requirements().get(2).mandatory());
        // R2-H01: knockout defaults to mandatory, except for OTHER, which is never scored and so never a ranking gate.
        assertFalse(result.requirements().get(2).knockout(), "an OTHER suggestion is never a knockout");
        assertEquals("AGE_CODED", result.warnings().get(0).kind());
    }

    @Test
    void titlesAndDescriptionsAsLongAsAJobsAreCutToTheAiServiceLimits() {
        // Integration pass (R6-B1): a job title may have 255 characters and a description 50,000, but the compile
        // request allowed only 200 / 20,000, so "Suggest requirements" failed for jobs that could be saved.
        AtomicReference<AiCompileRequest> sent = new AtomicReference<>();
        service(sent, () -> new AiCompileResponse("rules", List.of(), List.of()))
                .compile(new CompileRequirementsRequest("T".repeat(255), "D".repeat(50000), null));
        assertEquals(200, sent.get().title().length());
        assertEquals(20000, sent.get().description().length());
    }

    @Test
    void unavailableServiceIs503AndRejectedInputIs400() {
        ResponseStatusException down = assertThrows(ResponseStatusException.class,
                () -> service(new AtomicReference<>(), () -> { throw new ResourceAccessException("Connection refused"); })
                        .compile(new CompileRequirementsRequest("Role", "Description", null)));
        assertEquals(503, down.getStatusCode().value());
        assertThrows(BadRequestException.class,
                () -> service(new AtomicReference<>(), () -> { throw new HttpClientErrorException(HttpStatus.UNPROCESSABLE_ENTITY); })
                        .compile(new CompileRequirementsRequest("Role", "Description", null)));
    }

    private static RequirementCompilerService service(AtomicReference<AiCompileRequest> sent, Supplier<AiCompileResponse> response) {
        AiServiceClient client = new AiServiceClient(RestClient.builder(), "http://127.0.0.1:1", "test-only-token", 1000) {
            @Override
            public AiCompileResponse compileRequirements(AiCompileRequest request) {
                sent.set(request);
                return response.get();
            }
        };
        SecurityUtils securityUtils = new SecurityUtils(null) {
            @Override public CurrentUser currentUser() { return new CurrentUser(1L, "hr@a.test", Role.HR_MANAGER, 10L); }
        };
        return new RequirementCompilerService(client, new HrJobAccessService(null, securityUtils));
    }
}
