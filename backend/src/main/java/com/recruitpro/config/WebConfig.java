package com.recruitpro.config;

import com.recruitpro.auth.web.Portal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * CORS for the candidate and HR portals. Credentials are allowed (the refresh token travels in an httpOnly
 * cookie, D11), so origins must be listed explicitly; a wildcard is ignored.
 */
@Configuration
public class WebConfig {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:5174}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .filter(origin -> {
                    if ("*".equals(origin)) {
                        log.warn("Ignoring '*' in app.cors.allowed-origins: credentialed CORS needs explicit origins");
                        return false;
                    }
                    return true;
                })
                .toList();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // X-RecruitPro-Portal selects the per-portal refresh cookie (auth.web.Portal).
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With", Portal.HEADER));
        config.setExposedHeaders(List.of("Content-Disposition", "Retry-After", "X-Export-Truncated"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
