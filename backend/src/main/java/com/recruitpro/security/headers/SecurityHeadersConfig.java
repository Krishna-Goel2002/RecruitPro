package com.recruitpro.security.headers;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers {@link ApiSecurityHeadersFilter} right after the request-id filter, before Spring Security. */
@Configuration
public class SecurityHeadersConfig {

    @Bean
    public FilterRegistrationBean<ApiSecurityHeadersFilter> apiSecurityHeadersFilterRegistration() {
        FilterRegistrationBean<ApiSecurityHeadersFilter> registration = new FilterRegistrationBean<>(new ApiSecurityHeadersFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.addUrlPatterns("/*");
        registration.setName("apiSecurityHeadersFilter");
        return registration;
    }
}
