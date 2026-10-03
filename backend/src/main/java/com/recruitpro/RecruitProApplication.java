package com.recruitpro;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main entry point for the AI-Powered Recruitment Platform.
 *
 * <p>This Spring Boot application serves as a modular monolith containing
 * all recruitment lifecycle modules: authentication, HR management,
 * candidate management, applications, assessments, interviews,
 * hiring decisions, onboarding, and analytics.</p>
 *
 * <p>The application communicates with an external Python FastAPI AI service
 * for resume parsing, candidate-job matching, and AI-powered recommendations.</p>
 */
@SpringBootApplication
public class RecruitProApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecruitProApplication.class, args);
    }
}
