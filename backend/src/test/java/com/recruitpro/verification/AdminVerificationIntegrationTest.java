package com.recruitpro.verification;

import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.evidence.entity.CandidateClaim;
import com.recruitpro.evidence.entity.ClaimType;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobStatus;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.ScreeningFixtures;
import com.recruitpro.user.entity.Role;
import com.recruitpro.verification.entity.CandidateVerdictStatus;
import com.recruitpro.verification.entity.ClaimVerdict;
import com.recruitpro.verification.entity.VerdictValue;
import com.recruitpro.verification.entity.VerificationSource;
import com.recruitpro.verification.entity.VerificationSourceKind;
import com.recruitpro.verification.repository.OrganizationVerificationSettingsRepository;
import com.recruitpro.verification.service.VerificationAuditActions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin settings, the shadow labelling queue and the evaluation metrics (plan v2 section 5). */
class AdminVerificationIntegrationTest extends VerificationIntegrationTestBase {

    private static final String BASE = "/api/v1/admin/verification";

    @Autowired ScreeningFixtures screening;
    @Autowired AuditEventRepository audit;
    @Autowired OrganizationVerificationSettingsRepository settings;

    Organization organization;
    ClaimVerdict sharedVerified;
    ClaimVerdict sharedPartly;

    @BeforeEach
    void setUp() {
        organization = fixtures.organization("Admin Org");
        fixtures.staff(organization, "admin@admin-org.test", Role.ADMIN);
        HRProfile hr = fixtures.hrMember(organization, "hr@admin-org.test", Role.HR_MANAGER);
        fixtures.staff(organization, "recruiter@admin-org.test", Role.RECRUITER);
        CandidateProfile applicant = fixtures.candidate("applicant@cand.test");
        CandidateProfile outsider = fixtures.candidate("outsider@cand.test");
        Job job = fixtures.job(hr, "Data Engineer", JobStatus.PUBLISHED);
        Resume resume = screening.resume(applicant, "cv.pdf");
        screening.application(applicant, job, resume, UtcTime.now());

        VerificationSource source = verification.source(applicant, VerificationSourceKind.GITHUB, "applicant", true);
        CandidateClaim python = verification.claim(applicant, ClaimType.SKILL, "Python");
        CandidateClaim spark = verification.claim(applicant, ClaimType.SKILL, "Spark");
        sharedVerified = verification.verdict(source, python, VerdictValue.VERIFIED, true, CandidateVerdictStatus.SHARED);
        sharedPartly = verification.verdict(source, spark, VerdictValue.PARTLY_VERIFIED, true, CandidateVerdictStatus.SHARED);
        verification.verdict(source, spark, VerdictValue.NOT_FOUND, true, CandidateVerdictStatus.HIDDEN);
        VerificationSource outsiderSource = verification.source(outsider, VerificationSourceKind.GITHUB, "outsider", true);
        verification.verdict(outsiderSource, verification.claim(outsider, ClaimType.SKILL, "Rust"), VerdictValue.VERIFIED, true,
                CandidateVerdictStatus.SHARED);
    }

    @Test
    void settingsDefaultToShadowAndOnlyAdminsChangeThem() throws Exception {
        mvc.perform(get(BASE + "/settings").with(staff("admin@admin-org.test", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode", is("SHADOW")))
                .andExpect(jsonPath("$.data.connectors.GITHUB", is(true)))
                .andExpect(jsonPath("$.data.connectors.REFERENCE", is(true)))
                .andExpect(jsonPath("$.data.updatedAt", nullValue()));
        mvc.perform(put(BASE + "/settings").with(staff("hr@admin-org.test", "HR_MANAGER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"VISIBLE\"}")).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/settings").with(staff("recruiter@admin-org.test", "RECRUITER"))).andExpect(status().isForbidden());
        mvc.perform(put(BASE + "/settings").with(staff("admin@admin-org.test", "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"LOUD\"}")).andExpect(status().isBadRequest());
        mvc.perform(put(BASE + "/settings").with(staff("admin@admin-org.test", "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"VISIBLE\",\"connectors\":{\"FAX\":true}}")).andExpect(status().isBadRequest());
        mvc.perform(put(BASE + "/settings").with(staff("admin@admin-org.test", "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"VISIBLE\",\"connectors\":{\"DOCUMENT\":false}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode", is("VISIBLE")))
                .andExpect(jsonPath("$.data.connectors.DOCUMENT", is(false)))
                .andExpect(jsonPath("$.data.connectors.GITHUB", is(true)))
                .andExpect(jsonPath("$.data.updatedByName", is("Test User")));
        assertEquals(1, audit.findAllByActionOrderByIdAsc(VerificationAuditActions.SETTINGS_CHANGED).size());
        assertEquals(organization.getId(), audit.findAllByActionOrderByIdAsc(VerificationAuditActions.SETTINGS_CHANGED).get(0).getOrganizationId());
        assertEquals(1, settings.count());
    }

    @Test
    void theShadowQueueLabelsAndMetrics() throws Exception {
        mvc.perform(get(BASE + "/shadow-queue").with(staff("hr@admin-org.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", is(2)))
                .andExpect(jsonPath("$.data.content[0].verdictId", is(sharedPartly.getId().intValue())))
                .andExpect(jsonPath("$.data.content[0].revealed", is(false)))
                .andExpect(jsonPath("$.data.content[0].evidence", nullValue()))
                .andExpect(jsonPath("$.data.content[0].sourceKind", is("GITHUB")))
                .andExpect(jsonPath("$.data.content[0].myLabel", nullValue()));
        mvc.perform(get(BASE + "/shadow-queue").with(staff("recruiter@admin-org.test", "RECRUITER"))).andExpect(status().isForbidden());

        mvc.perform(post(BASE + "/verdicts/{id}/label", sharedPartly.getId()).with(staff("hr@admin-org.test", "HR_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"labelVerdict\":\"PARTLY_VERIFIED\",\"note\":\"agree\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labelVerdict", is("PARTLY_VERIFIED")));
        // The labelled verdict moves behind the unlabelled one.
        mvc.perform(get(BASE + "/shadow-queue").with(staff("hr@admin-org.test", "HR_MANAGER")))
                .andExpect(jsonPath("$.data.content[0].verdictId", is(sharedVerified.getId().intValue())))
                .andExpect(jsonPath("$.data.content[1].myLabel.labelVerdict", is("PARTLY_VERIFIED")))
                .andExpect(jsonPath("$.data.content[1].labelCount", is(1)));
        mvc.perform(post(BASE + "/verdicts/{id}/label", sharedVerified.getId()).with(staff("admin@admin-org.test", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"labelVerdict\":\"PARTLY_VERIFIED\"}")).andExpect(status().isOk());
        mvc.perform(post(BASE + "/verdicts/{id}/label", sharedVerified.getId()).with(staff("admin@admin-org.test", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"labelVerdict\":\"MAYBE\"}")).andExpect(status().isBadRequest());

        // 2 labels: one agreement (PARTLY/PARTLY), one false corroboration (VERIFIED predicted, PARTLY gold).
        mvc.perform(get(BASE + "/metrics").with(staff("hr@admin-org.test", "HR_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labelled", is(2)))
                .andExpect(jsonPath("$.data.agreement", is(0.5)))
                .andExpect(jsonPath("$.data.falseCorroborationRate", is(0.5)))
                .andExpect(jsonPath("$.data.kappa", is(0.0)))
                .andExpect(jsonPath("$.data.byVerifier", hasSize(1)))
                .andExpect(jsonPath("$.data.byVerifier[0].verifier", is("code")))
                .andExpect(jsonPath("$.data.byVerdict", hasSize(5)))
                .andExpect(jsonPath("$.data.readyForVisible", is(false)));
    }
}
