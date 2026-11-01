package com.recruitpro;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test to verify that the Spring application context loads correctly.
 */
@SpringBootTest
@ActiveProfiles("test")
class RecruitProApplicationTests {

    @Test
    void contextLoads() {
        // Verifies that the Spring Boot application context starts without errors.
    }
}
