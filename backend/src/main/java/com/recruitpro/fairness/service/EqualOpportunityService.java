package com.recruitpro.fairness.service;

import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.service.CandidateProfileService;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.fairness.dto.EqualOpportunityRequest;
import com.recruitpro.fairness.dto.EqualOpportunityResponse;
import com.recruitpro.fairness.entity.EeoResponse;
import com.recruitpro.fairness.entity.EqualOpportunityValues;
import com.recruitpro.fairness.repository.EeoResponseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * The candidate's voluntary equal-opportunity survey (D14, plan 5.5). Stored only with explicit consent, replaced on
 * every save (consent time refreshed), deletable at any time. No staff endpoint ever returns it. Not read-only: the
 * candidate profile may be provisioned on first use.
 */
@Service
@Transactional
public class EqualOpportunityService {

    private final CandidateProfileService profileService;
    private final EeoResponseRepository repository;

    public EqualOpportunityService(CandidateProfileService profileService, EeoResponseRepository repository) {
        this.profileService = profileService;
        this.repository = repository;
    }

    /** GET: the stored answers; 404 when nothing is stored. */
    public EqualOpportunityResponse get() {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        return repository.findByCandidateId(candidate.getId()).map(EqualOpportunityResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("No equal-opportunity answers are stored"));
    }

    /** PUT: consent is required; values must be the plan 5.5 codes (null or blank: not answered). */
    public EqualOpportunityResponse save(EqualOpportunityRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.consent())) {
            throw new BadRequestException("Your consent is required to store these answers");
        }
        String gender = value(request.gender(), EqualOpportunityValues.GENDERS, "gender");
        String ageBand = value(request.ageBand(), EqualOpportunityValues.AGE_BANDS, "ageBand");
        String disability = value(request.disability(), EqualOpportunityValues.DISABILITIES, "disability");
        String socialCategory = value(request.socialCategory(), EqualOpportunityValues.SOCIAL_CATEGORIES, "socialCategory");
        CandidateProfile candidate = profileService.getOrProvisionMine();
        EeoResponse response = repository.findByCandidateId(candidate.getId()).orElseGet(EeoResponse::new);
        response.setCandidateId(candidate.getId());
        response.setGender(gender);
        response.setAgeBand(ageBand);
        response.setDisability(disability);
        response.setSocialCategory(socialCategory);
        response.setConsentGivenAt(UtcTime.now());
        return EqualOpportunityResponse.from(repository.saveAndFlush(response));
    }

    /** DELETE: removes the stored answers (idempotent). */
    public void delete() {
        CandidateProfile candidate = profileService.getOrProvisionMine();
        repository.deleteAllByCandidate(candidate.getId());
    }

    static String value(String raw, List<String> allowed, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BadRequestException(field + " must be one of " + String.join(", ", allowed));
        }
        return normalized;
    }
}
