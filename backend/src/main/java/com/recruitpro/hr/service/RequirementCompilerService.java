package com.recruitpro.hr.service;

import com.recruitpro.ai.dto.AiCompileRequest;
import com.recruitpro.ai.dto.AiCompileResponse;
import com.recruitpro.ai.service.AiServiceClient;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.hr.dto.CompileRequirementsRequest;
import com.recruitpro.hr.dto.CompileRequirementsResponse;
import com.recruitpro.hr.entity.Proficiency;
import com.recruitpro.hr.entity.RequirementType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * POST /api/v1/jobs/compile-requirements: proxies the AI service's requirement compiler. Suggestions only;
 * nothing is persisted. Deliberately not transactional (no database connection is held during the AI call).
 */
@Service
public class RequirementCompilerService {

    private final AiServiceClient aiServiceClient;
    private final HrJobAccessService jobAccess;

    public RequirementCompilerService(AiServiceClient aiServiceClient, HrJobAccessService jobAccess) {
        this.aiServiceClient = aiServiceClient;
        this.jobAccess = jobAccess;
    }

    public CompileRequirementsResponse compile(CompileRequirementsRequest request) {
        jobAccess.requireJobManager();
        AiCompileResponse response;
        try {
            response = aiServiceClient.compileRequirements(new AiCompileRequest(request.title().trim(),
                    request.description().trim(), request.experienceLevel() == null ? null : request.experienceLevel().name()));
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status >= 400 && status < 500 && status != 408 && status != 429) {
                throw new BadRequestException("The job description could not be analysed; shorten or simplify it and try again");
            }
            throw unavailable();
        } catch (RestClientException ex) {
            throw unavailable();
        } catch (IllegalStateException ex) {
            throw unavailable();
        }
        if (response == null) {
            throw unavailable();
        }
        List<CompileRequirementsResponse.CompiledRequirement> requirements = new ArrayList<>();
        for (AiCompileResponse.Requirement item : response.requirements() == null ? List.<AiCompileResponse.Requirement>of() : response.requirements()) {
            if (item == null || item.description() == null || item.description().isBlank()) continue;
            boolean mandatory = item.mandatory() == null || item.mandatory();
            boolean knockout = item.knockout() == null ? mandatory : item.knockout() && mandatory;
            String description = item.description().trim();
            requirements.add(new CompileRequirementsResponse.CompiledRequirement(requirementType(item.type()),
                    description.length() > 500 ? description.substring(0, 500) : description, mandatory, knockout,
                    item.minimumYears() == null ? null : Math.max(0, Math.min(50, item.minimumYears())),
                    alternatives(item.alternatives()), proficiency(item.proficiency()),
                    item.rationale() == null ? "" : item.rationale()));
        }
        List<CompileRequirementsResponse.CompileWarning> warnings = new ArrayList<>();
        for (AiCompileResponse.Warning warning : response.warnings() == null ? List.<AiCompileResponse.Warning>of() : response.warnings()) {
            if (warning == null || warning.phrase() == null) continue;
            warnings.add(new CompileRequirementsResponse.CompileWarning(warning.phrase(), warning.kind(),
                    warning.suggestion() == null ? "" : warning.suggestion()));
        }
        return new CompileRequirementsResponse(response.engine() == null ? "rules" : response.engine(), requirements, warnings);
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Requirement drafting is temporarily unavailable; please add requirements manually or try again later");
    }

    private static RequirementType requirementType(String value) {
        if (value == null) return RequirementType.OTHER;
        try {
            return RequirementType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return RequirementType.OTHER;
        }
    }

    private static Proficiency proficiency(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Proficiency.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static List<String> alternatives(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values == null) return result;
        for (String value : values) {
            if (value != null && !value.isBlank() && result.size() < 10) {
                String trimmed = value.trim();
                result.add(trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed);
            }
        }
        return result;
    }
}
