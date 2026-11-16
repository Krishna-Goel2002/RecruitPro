package com.recruitpro.auth;

import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.audit.service.AuditActions;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.support.TestDataFactory;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Completeness sweep: anyone can fix their own name (PATCH /api/v1/me); it is trimmed, validated and audited. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeNameIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestDataFactory fixtures;
    @Autowired UserRepository users;
    @Autowired AuditEventRepository auditEvents;

    User staff;

    @BeforeEach
    void setUp() {
        fixtures.cleanDatabase();
        Organization organization = fixtures.organization("Name Org");
        staff = fixtures.staff(organization, "recruiter@name.test", Role.RECRUITER);
        fixtures.candidate("cand@name.test");
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanDatabase();
    }

    @Test
    void staffAndCandidatesCanCorrectTheirOwnName() throws Exception {
        mvc.perform(patch("/api/v1/me").with(SecurityMockMvcRequestPostProcessors.user("recruiter@name.test").roles("RECRUITER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"  Ria \",\"lastName\":\"Reviewer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstName", is("Ria")))
                .andExpect(jsonPath("$.data.lastName", is("Reviewer")));
        User updated = users.findById(staff.getId()).orElseThrow();
        assertEquals("Ria", updated.getFirstName());
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.ACCOUNT_NAME_CHANGED).size());

        // The same name again changes nothing and records nothing.
        mvc.perform(patch("/api/v1/me").with(SecurityMockMvcRequestPostProcessors.user("recruiter@name.test").roles("RECRUITER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"Ria\",\"lastName\":\"Reviewer\"}")).andExpect(status().isOk());
        assertEquals(1, auditEvents.findAllByActionOrderByIdAsc(AuditActions.ACCOUNT_NAME_CHANGED).size());

        mvc.perform(patch("/api/v1/me").with(SecurityMockMvcRequestPostProcessors.user("cand@name.test").roles("CANDIDATE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"Meera\",\"lastName\":\"Iyengar\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastName", is("Iyengar")));

        mvc.perform(patch("/api/v1/me").with(SecurityMockMvcRequestPostProcessors.user("cand@name.test").roles("CANDIDATE"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"\",\"lastName\":\"Iyengar\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/me").contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"A\",\"lastName\":\"B\"}")).andExpect(status().isUnauthorized());
    }
}
