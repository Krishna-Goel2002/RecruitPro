package com.recruitpro.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** M2 through the real MVC stack: client mistakes are 4xx with the standard envelope, never 500. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErrorMappingIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void pathVariableTypeMismatchIs400() throws Exception {
        mvc.perform(get("/api/v1/jobs/not-a-number").with(user("candidate@example.test").roles("CANDIDATE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("id")));
    }

    @Test
    void queryParameterTypeMismatchIs400() throws Exception {
        mvc.perform(get("/api/v1/jobs").param("page", "first").with(user("candidate@example.test").roles("CANDIDATE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("page")));
    }

    @Test
    void unsupportedContentTypeIs415() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("email=a&password=b"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownRouteIs404WithEnvelope() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist").with(user("candidate@example.test").roles("CANDIDATE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }
}
