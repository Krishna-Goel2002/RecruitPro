package com.recruitpro.candidate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.candidate.storage.ResumeStorage;
import com.recruitpro.hr.entity.*;
import com.recruitpro.hr.repository.HRProfileRepository;
import com.recruitpro.hr.repository.JobRepository;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CandidatePortalIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CandidateProfileRepository candidates;
    @Autowired HRProfileRepository hrProfiles;
    @Autowired JobRepository jobs;
    @Autowired ResumeRepository resumes;
    @Autowired ApplicationRepository applications;
    @Autowired ResumeStorage resumeStorage;
    String candidateEmail, otherEmail;
    CandidateProfile candidate, other;
    Job published, draft, closed;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        candidateEmail = "candidate-" + suffix + "@example.test";
        otherEmail = "other-" + suffix + "@example.test";
        candidate = createCandidate(candidateEmail);
        other = createCandidate(otherEmail);
        User hr = user("hr-" + suffix + "@example.test", Role.HR_MANAGER);
        HRProfile profile = new HRProfile(); profile.setUser(hr); hrProfiles.save(profile);
        published = createJob(profile, JobStatus.PUBLISHED, "Published " + suffix);
        draft = createJob(profile, JobStatus.DRAFT, "Draft " + suffix);
        closed = createJob(profile, JobStatus.CLOSED, "Closed " + suffix);
    }

    @Test
    void candidateProfileIsProvisionedDuringRegistrationAndSelfManaged() throws Exception {
        String email = "new-" + UUID.randomUUID() + "@example.test";
        String body = "{\"firstName\":\"New\",\"lastName\":\"Candidate\",\"email\":\"" + email + "\",\"password\":\"StrongPassword123!\"}";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.user.role", is("CANDIDATE")));
        mvc.perform(get("/api/v1/candidates/me").with(candidateAuth(email)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.email", is(email)));
        mvc.perform(put("/api/v1/candidates/me").with(candidateAuth(email)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + other.getUser().getId() + ",\"city\":\"Pune\",\"yearsOfExperience\":4,\"linkedinUrl\":\"https://linkedin.com/in/example\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.city", is("Pune")))
                .andExpect(jsonPath("$.data.email", is(email)));
        mvc.perform(put("/api/v1/candidates/me").with(candidateAuth(email)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"yearsOfExperience\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/candidates/me").with(SecurityMockMvcRequestPostProcessors.user("hr").roles("HR_MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonCandidateRolesCannotAccessPrivateCandidateResources() throws Exception {
        for (Role role : new Role[]{Role.ADMIN, Role.HR_MANAGER, Role.RECRUITER, Role.INTERVIEWER}) {
            var auth = SecurityMockMvcRequestPostProcessors.user(role.name().toLowerCase() + "@example.test")
                    .roles(role.name());
            mvc.perform(get("/api/v1/candidates/me").with(auth)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/candidates/me/resumes").with(auth)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/candidates/me/applications").with(auth)).andExpect(status().isForbidden());
        }
    }

    @Test
    void resumeUploadHistoryOwnershipAndContentValidation() throws Exception {
        var auth = candidateAuth(candidateEmail);
        String idJson = mvc.perform(multipart("/api/v1/candidates/me/resumes").file(pdf("first.pdf")).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.originalFilename", is("first.pdf")))
                .andReturn().getResponse().getContentAsString();
        assertFalse(idJson.contains("storageKey"));
        assertFalse(idJson.contains("test-uploads"));
        long resumeId = mapper.readTree(idJson).path("data").path("id").asLong();
        mvc.perform(get("/api/v1/candidates/me/resumes/{id}", resumeId).with(candidateAuth(otherEmail)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/candidates/me/resumes/{id}/download", resumeId).with(candidateAuth(otherEmail)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/candidates/me/resumes/{id}/download", resumeId).with(auth))
                .andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options", "nosniff"));
        Resume stored = resumes.findById(resumeId).orElseThrow();
        resumeStorage.delete(stored.getStorageKey());
        mvc.perform(get("/api/v1/candidates/me/resumes/{id}/download", resumeId).with(auth))
                .andExpect(status().isNotFound());
        mvc.perform(multipart("/api/v1/candidates/me/resumes").file(new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0])).with(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/candidates/me/resumes").file(new MockMultipartFile("file", "bad.pdf", "application/pdf", "not a pdf".getBytes())).with(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/candidates/me/resumes").file(new MockMultipartFile("file", "bad.exe", "application/octet-stream", new byte[]{1})).with(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/candidates/me/resumes").file(new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[10 * 1024 * 1024 + 1])).with(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/candidates/me/resumes").file(docx("second.docx")).with(auth))
                .andExpect(status().isOk());
        mvc.perform(multipart("/api/v1/candidates/me/resumes").file(doc("third.doc")).with(auth))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/candidates/me/resumes").with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(3)));
    }

    @Test
    void publishedJobVisibilityAndApplicationsRetainExactResumeAndEnforceOwnership() throws Exception {
        var auth = candidateAuth(candidateEmail);
        mvc.perform(get("/api/v1/jobs").with(auth).param("status", "DRAFT").param("search", published.getTitle()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()", is(1)))
                .andExpect(jsonPath("$.data.content[0].status", is("PUBLISHED")));
        mvc.perform(get("/api/v1/jobs/{id}", draft.getId()).with(auth)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/jobs/{id}", closed.getId()).with(auth)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/jobs/{id}/applications", published.getId()).with(auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        var otherAuth = candidateAuth(otherEmail);
        long otherResume = uploadPdf(otherAuth, "other-candidate.pdf");
        mvc.perform(post("/api/v1/jobs/{id}/applications", published.getId()).with(auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resumeId\":" + otherResume + "}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/jobs/{id}/applications", draft.getId()).with(auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resumeId\":" + uploadPdf(auth, "draft-application.pdf") + "}"))
                .andExpect(status().isBadRequest());
        Job expired = createJob(published.getCreatedBy(), JobStatus.PUBLISHED, "Expired");
        expired.setDeadline(LocalDateTime.now().minusDays(1)); jobs.save(expired);
        mvc.perform(post("/api/v1/jobs/{id}/applications", expired.getId()).with(auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resumeId\":" + uploadPdf(auth, "expired-application.pdf") + "}"))
                .andExpect(status().isBadRequest());
        long resumeId = uploadPdf(auth, "original.pdf");
        String applied = mvc.perform(post("/api/v1/jobs/{id}/applications", published.getId()).with(auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resumeId\":" + resumeId + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("SUBMITTED")))
                .andReturn().getResponse().getContentAsString();
        long appId = mapper.readTree(applied).path("data").path("id").asLong();
        mvc.perform(post("/api/v1/jobs/{id}/applications", published.getId()).with(auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resumeId\":" + resumeId + "}"))
                .andExpect(status().isConflict());
        Application duplicate = new Application();
        duplicate.setCandidate(candidate);
        duplicate.setJob(published);
        duplicate.setResume(resumes.findById(resumeId).orElseThrow());
        duplicate.setAppliedAt(LocalDateTime.now());
        assertThrows(DataIntegrityViolationException.class, () -> applications.saveAndFlush(duplicate));
        assertThrows(DataIntegrityViolationException.class, () -> resumes.deleteById(resumeId));
        mvc.perform(multipart("/api/v1/candidates/me/resumes").file(pdf("replacement.pdf")).with(auth)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/candidates/me/applications/{id}", appId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resumeId", is((int) resumeId)));
        mvc.perform(patch("/api/v1/candidates/me/applications/{id}", appId).with(auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"WITHDRAWN\"}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/v1/candidates/me/applications/{id}", appId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("SUBMITTED")));
        mvc.perform(get("/api/v1/candidates/me/applications/{id}", appId).with(candidateAuth(otherEmail)))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/candidates/me/applications/{id}/withdraw", appId).with(candidateAuth(otherEmail)))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/candidates/me/applications/{id}/withdraw", appId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("WITHDRAWN")));
        mvc.perform(patch("/api/v1/candidates/me/applications/{id}/withdraw", appId).with(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/candidates/me/applications").with(candidateAuth(otherEmail)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements", is(0)));
    }

    private long uploadPdf(org.springframework.test.web.servlet.request.RequestPostProcessor auth, String name) throws Exception {
        String json = mvc.perform(multipart("/api/v1/candidates/me/resumes").file(pdf(name)).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data").path("id").asLong();
    }
    private MockMultipartFile pdf(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "%PDF-1.7\nminimal test data\n%%EOF".getBytes());
    }
    private MockMultipartFile doc(String name) {
        return new MockMultipartFile("file", name, "application/msword",
                new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});
    }
    private MockMultipartFile docx(String name) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml")); zip.write("<Types/>".getBytes()); zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml")); zip.write("<document/>".getBytes()); zip.closeEntry();
        }
        return new MockMultipartFile("file", name, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes.toByteArray());
    }
    private CandidateProfile createCandidate(String email) {
        User user = user(email, Role.CANDIDATE);
        CandidateProfile p = new CandidateProfile(); p.setUser(user); return candidates.save(p);
    }
    private User user(String email, Role role) {
        return users.save(User.builder().email(email).passwordHash("test").firstName("Test").lastName("User").role(role).enabled(true).build());
    }
    private Job createJob(HRProfile profile, JobStatus status, String title) {
        Job j = new Job(); j.setCreatedBy(profile); j.setTitle(title); j.setDescription("Description"); j.setDepartment("Engineering");
        j.setLocation("Remote"); j.setJobType(JobType.FULL_TIME); j.setExperienceLevel(ExperienceLevel.MID);
        j.setMinimumSalary(new BigDecimal("50000")); j.setMaximumSalary(new BigDecimal("90000")); j.setVacancies(1);
        j.setStatus(status); if (status == JobStatus.PUBLISHED) j.setPublishedAt(LocalDateTime.now());
        return jobs.save(j);
    }
    private SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor candidateAuth(String email) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles("CANDIDATE");
    }
}
