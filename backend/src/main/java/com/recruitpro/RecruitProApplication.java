package com.recruitpro;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

/**
 * Main entry point for the AI-Powered Recruitment Platform.
 *
 * <p>This Spring Boot application is a modular monolith for organisations, authentication, jobs and
 * requirements, candidates, applications and evidence-based screening. It communicates with an external
 * Python FastAPI AI service for resume parsing and requirement matching through background jobs.</p>
 */
@SpringBootApplication
public class RecruitProApplication {

    public static void main(String[] args) {
        // D15: every timestamp is UTC, independent of the host time zone.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(RecruitProApplication.class, args);
    }
}
