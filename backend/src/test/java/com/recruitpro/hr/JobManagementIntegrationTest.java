package com.recruitpro.hr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.repository.HRProfileRepository;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobManagementIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HRProfileRepository profiles;
    @Autowired ObjectMapper mapper;
    private String email;

    @BeforeEach
    void setUp() {
        email = "hr-" + UUID.randomUUID() + "@example.test";
        User user = users.save(User.builder().email(email).passwordHash("test").firstName("Test")
                .lastName("HR").role(Role.HR_MANAGER).enabled(true).build());
        HRProfile profile = new HRProfile(); profile.setUser(user); profile.setDepartment("People"); profiles.save(profile);
    }

    private String jobJson() {
        return """
                {"title":"Backend Engineer","description":"Build recruitment services","department":"Engineering",
                "location":"Remote","jobType":"FULL_TIME","experienceLevel":"MID","minimumSalary":50000,
                "maximumSalary":90000,"vacancies":2}
                """;
    }

    @Test
    @WithMockUser(username="ignored", roles="HR_MANAGER")
    void roleEmailOverridesMockNameAndSupportsDraftLifecycleAndRequirements() throws Exception {
        // Use the provisioned account as the authenticated principal for HRProfile resolution.
        var auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(email).roles("HR_MANAGER");
        String uniqueJob = jobJson().replace("Backend Engineer", "Backend Engineer " + UUID.randomUUID());
        String created = mvc.perform(post("/api/v1/jobs").with(auth).contentType(MediaType.APPLICATION_JSON).content(uniqueJob))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("DRAFT")))
                .andExpect(jsonPath("$.data.createdByEmail", is(email))).andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(created).path("data").path("id").asLong();

        String requirement = "{\"type\":\"SKILL\",\"description\":\"Java\",\"mandatory\":true}";
        String reqResponse = mvc.perform(post("/api/v1/jobs/{id}/requirements", id).with(auth)
                        .contentType(MediaType.APPLICATION_JSON).content(requirement))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.description", is("Java")))
                .andReturn().getResponse().getContentAsString();
        long requirementId = mapper.readTree(reqResponse).path("data").path("id").asLong();

        mvc.perform(get("/api/v1/jobs/{id}", id).with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requirements[0].id", is((int) requirementId)));
        mvc.perform(get("/api/v1/jobs").param("department", "engineering")
                        .param("search", mapper.readTree(created).path("data").path("title").asText()).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements", is(1)));

        mvc.perform(put("/api/v1/jobs/{id}/requirements/{rid}", id, requirementId).with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SKILL\",\"description\":\"Java 17\",\"mandatory\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.description", is("Java 17")));
        mvc.perform(delete("/api/v1/jobs/{id}/requirements/{rid}", id, requirementId).with(auth)).andExpect(status().isOk());

        mvc.perform(put("/api/v1/jobs/{id}", id).with(auth).contentType(MediaType.APPLICATION_JSON).content(uniqueJob))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/jobs/{id}/status", id).with(auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("PUBLISHED")));
        mvc.perform(delete("/api/v1/jobs/{id}", id).with(auth)).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/jobs/{id}/status", id).with(auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DRAFT\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles="RECRUITER")
    void recruiterCanReadButCannotMutate() throws Exception {
        var auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(email).roles("HR_MANAGER");
        String result = mvc.perform(post("/api/v1/jobs").with(auth).contentType(MediaType.APPLICATION_JSON).content(jobJson()))
                .andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(result).path("data").path("id").asLong();
        mvc.perform(get("/api/v1/jobs/{id}", id).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("recruiter").roles("RECRUITER")))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/jobs/{id}", id).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("recruiter").roles("RECRUITER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherHrManagerCannotMutateOrCloseAJobTheyDoNotOwn() throws Exception {
        var owner = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(email).roles("HR_MANAGER");
        String created = mvc.perform(post("/api/v1/jobs").with(owner).contentType(MediaType.APPLICATION_JSON).content(jobJson()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(created).path("data").path("id").asLong();
        var otherManager = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user("other-manager@example.test").roles("HR_MANAGER");

        mvc.perform(put("/api/v1/jobs/{id}", id).with(otherManager)
                        .contentType(MediaType.APPLICATION_JSON).content(jobJson()))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/jobs/{id}/status", id).with(otherManager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/jobs/{id}/requirements", id).with(otherManager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SKILL\",\"description\":\"Java\",\"mandatory\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/jobs/{id}", id).with(otherManager))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/jobs/{id}", id).with(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is("DRAFT")));
    }

    @Test
    @WithMockUser(roles="INTERVIEWER")
    void interviewerIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/jobs")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/jobs")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles="ADMIN")
    void missingProfileReturnsClearBusinessErrorAndInvalidPayloadIsRejected() throws Exception {
        var auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("no-profile@example.test").roles("ADMIN");
        mvc.perform(post("/api/v1/jobs").with(auth).contentType(MediaType.APPLICATION_JSON).content(jobJson()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("does not have an HR profile")));
        var hr = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(email).roles("HR_MANAGER");
        mvc.perform(post("/api/v1/jobs").with(hr).contentType(MediaType.APPLICATION_JSON)
                        .content(jobJson().replace("\"vacancies\":2", "\"vacancies\":0")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/jobs").with(hr).contentType(MediaType.APPLICATION_JSON)
                        .content(jobJson().replace("\"minimumSalary\":50000", "\"minimumSalary\":95000")))
                .andExpect(status().isBadRequest());
        String valid = mvc.perform(post("/api/v1/jobs").with(hr).contentType(MediaType.APPLICATION_JSON).content(jobJson()))
                .andReturn().getResponse().getContentAsString();
        long jobId = mapper.readTree(valid).path("data").path("id").asLong();
        mvc.perform(post("/api/v1/jobs/{id}/requirements", jobId).with(hr).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPERIENCE\",\"description\":\"Experience\",\"minimumYears\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requirementCannotBeModifiedThroughAnotherJobAndMissingJobReturns404() throws Exception {
        var hr = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(email).roles("HR_MANAGER");
        String first = mvc.perform(post("/api/v1/jobs").with(hr).contentType(MediaType.APPLICATION_JSON).content(jobJson()))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/v1/jobs").with(hr).contentType(MediaType.APPLICATION_JSON).content(jobJson()))
                .andReturn().getResponse().getContentAsString();
        long job1 = mapper.readTree(first).path("data").path("id").asLong();
        long job2 = mapper.readTree(second).path("data").path("id").asLong();
        String req = mvc.perform(post("/api/v1/jobs/{id}/requirements", job1).with(hr).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPERIENCE\",\"description\":\"Two years\",\"minimumYears\":2}"))
                .andReturn().getResponse().getContentAsString();
        long reqId = mapper.readTree(req).path("data").path("id").asLong();
        mvc.perform(delete("/api/v1/jobs/{id}/requirements/{rid}", job2, reqId).with(hr))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/jobs/{id}", 99999999L).with(hr)).andExpect(status().isNotFound());
    }

    @Test
    void adminCanManageAndLifecycleCanHoldResumeAndClose() throws Exception {
        // Associate this ADMIN account with a provisioned HR profile, as required for job ownership.
        String adminEmail = "admin-" + UUID.randomUUID() + "@example.test";
        User admin = users.save(User.builder().email(adminEmail).passwordHash("test").firstName("Test")
                .lastName("Admin").role(Role.ADMIN).enabled(true).build());
        HRProfile profile = new HRProfile(); profile.setUser(admin); profiles.save(profile);
        var auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(adminEmail).roles("ADMIN");
        String result = mvc.perform(post("/api/v1/jobs").with(auth).contentType(MediaType.APPLICATION_JSON).content(jobJson()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(result).path("data").path("id").asLong();
        for (String state : new String[]{"PUBLISHED", "ON_HOLD", "PUBLISHED", "CLOSED"}) {
            mvc.perform(patch("/api/v1/jobs/{id}/status", id).with(auth).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"" + state + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status", is(state)));
        }
    }
}
