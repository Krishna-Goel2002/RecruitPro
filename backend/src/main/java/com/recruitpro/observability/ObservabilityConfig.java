package com.recruitpro.observability;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers {@link RequestIdFilter} before every other filter (including Spring Security's chain). */
@Configuration
public class ObservabilityConfig {

    public static final String JOB_TIMER = "recruitpro.job.handle";
    public static final String AI_TIMER = "recruitpro.ai.call";

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        registration.setName("requestIdFilter");
        return registration;
    }
}
