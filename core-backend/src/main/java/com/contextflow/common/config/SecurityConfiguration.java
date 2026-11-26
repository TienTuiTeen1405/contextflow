package com.contextflow.common.config;

import com.contextflow.common.api.ApiError;
import com.contextflow.common.api.CorrelationIdFilter;
import com.contextflow.common.api.ErrorCode;
import com.contextflow.common.api.ErrorEnvelope;
import com.contextflow.identity.demo.LocalDemoUserFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfiguration {
    @Bean
    @Profile("!local-demo")
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper)
            throws Exception {
        return buildFilterChain(http, mapper, false);
    }

    @Bean
    @Profile("local-demo")
    SecurityFilterChain localDemoSecurityFilterChain(HttpSecurity http, ObjectMapper mapper)
            throws Exception {
        http.addFilterBefore(new LocalDemoUserFilter(), AnonymousAuthenticationFilter.class);
        return buildFilterChain(http, mapper, true);
    }

    private SecurityFilterChain buildFilterChain(
            HttpSecurity http, ObjectMapper mapper, boolean localDemo) throws Exception {
        return http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                                    HttpMethod.GET,
                                    "/actuator/health",
                                    "/actuator/health/**",
                                    "/swagger-ui.html",
                                    "/swagger-ui/**",
                                    "/v3/api-docs",
                                    "/v3/api-docs/**")
                            .permitAll();
                    if (localDemo) {
                        auth.requestMatchers(
                                        HttpMethod.GET,
                                        "/api/v1/me",
                                        "/api/v1/me/personal-intelligence",
                                        "/api/v1/me/credential",
                                        "/api/v1/me/usage-summary",
                                        "/api/v1/projects",
                                        "/api/v1/projects/*",
                                        "/api/v1/projects/*/intelligence",
                                        "/api/v1/projects/*/conversations",
                                        "/api/v1/projects/*/conversations/*/outline",
                                        "/api/v1/projects/*/stages/*/turns",
                                        "/api/v1/ai/requests/*",
                                        "/api/v1/ai/requests/*/events")
                                .authenticated();
                        auth.requestMatchers(
                                        HttpMethod.POST,
                                        "/api/v1/me/personal-skills",
                                        "/api/v1/me/credential/verify",
                                        "/api/v1/me/privacy-acknowledgements",
                                        "/api/v1/projects",
                                        "/api/v1/projects/*/rules",
                                        "/api/v1/projects/*/skills",
                                        "/api/v1/projects/*/conversations",
                                        "/api/v1/ai/chat-requests",
                                        "/api/v1/ai/requests/*/cancel")
                                .authenticated();
                        auth.requestMatchers(
                                        HttpMethod.PUT,
                                        "/api/v1/me/personal-intelligence",
                                        "/api/v1/me/credential",
                                        "/api/v1/projects/*/intelligence")
                                .authenticated();
                        auth.requestMatchers(
                                        HttpMethod.PATCH,
                                        "/api/v1/me",
                                        "/api/v1/me/personal-skills/*",
                                        "/api/v1/projects/*",
                                        "/api/v1/projects/*/rules/*",
                                        "/api/v1/projects/*/skills/*",
                                        "/api/v1/projects/*/conversations/*")
                                .authenticated();
                        auth.requestMatchers(
                                        HttpMethod.DELETE,
                                        "/api/v1/me/personal-skills/*",
                                        "/api/v1/me/credential",
                                        "/api/v1/projects/*/rules/*",
                                        "/api/v1/projects/*/skills/*")
                                .authenticated();
                    }
                    auth.anyRequest().denyAll();
                })
                .exceptionHandling(errors ->
                        errors.authenticationEntryPoint((request, response, exception) ->
                                        writeError(
                                                mapper,
                                                request,
                                                response,
                                                401,
                                                ErrorCode.UNAUTHENTICATED,
                                                "Authentication is required."))
                                .accessDeniedHandler((request, response, exception) ->
                                        writeError(
                                                mapper,
                                                request,
                                                response,
                                                403,
                                                ErrorCode.FORBIDDEN,
                                                "Access is not allowed.")))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(WebProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "Idempotency-Key", "Last-Event-ID"));
        config.setExposedHeaders(List.of("X-Correlation-Id", LocalDemoUserFilter.MODE_HEADER));
        config.setAllowCredentials(false);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static void writeError(
            ObjectMapper mapper,
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            ErrorCode code,
            String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(
                response.getOutputStream(),
                new ErrorEnvelope(
                        new ApiError(
                                code,
                                message,
                                code.retryable(),
                                UUID.fromString(
                                        (String)
                                                request.getAttribute(
                                                        CorrelationIdFilter.ATTRIBUTE)),
                                null)));
    }
}
