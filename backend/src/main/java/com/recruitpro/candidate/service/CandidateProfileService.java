package com.recruitpro.candidate.service;

import com.recruitpro.candidate.dto.CandidateProfileResponse;
import com.recruitpro.candidate.dto.CandidateProfileUpdateRequest;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CandidateProfileService {
    private final CandidateProfileRepository profileRepository;
    private final UserRepository userRepository;

    public CandidateProfileResponse getMine() { return CandidateProfileResponse.from(currentProfile()); }

    public CandidateProfileResponse updateMine(CandidateProfileUpdateRequest request) {
        CandidateProfile p = currentProfile();
        p.setDateOfBirth(request.dateOfBirth()); p.setGender(trim(request.gender()));
        p.setAddress(trim(request.address())); p.setCity(trim(request.city())); p.setState(trim(request.state()));
        p.setCountry(trim(request.country())); p.setZipCode(trim(request.zipCode()));
        p.setLinkedinUrl(trim(request.linkedinUrl())); p.setPortfolioUrl(trim(request.portfolioUrl()));
        p.setYearsOfExperience(request.yearsOfExperience()); p.setCurrentCompany(trim(request.currentCompany()));
        p.setCurrentDesignation(trim(request.currentDesignation())); p.setExpectedSalary(request.expectedSalary());
        p.setNoticePeriodDays(request.noticePeriodDays()); p.setProfileSummary(trim(request.profileSummary()));
        return CandidateProfileResponse.from(p);
    }

    public CandidateProfile getOrProvisionMine() { return currentProfile(); }

    private CandidateProfile currentProfile() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return profileRepository.findByUserEmailIgnoreCase(email).orElseGet(() -> {
            User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("Candidate account not found"));
            if (user.getRole() != Role.CANDIDATE) throw new AccessDeniedException("Candidate access required");
            CandidateProfile profile = new CandidateProfile(); profile.setUser(user);
            return profileRepository.save(profile);
        });
    }

    private String trim(String value) { return value == null ? null : value.trim(); }
}
