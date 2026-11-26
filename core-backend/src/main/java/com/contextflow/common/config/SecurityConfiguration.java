package com.contextflow.common.config;

import com.contextflow.common.api.CorrelationIdFilter;
import com.contextflow.contracts.generated.PublicApi.ApiError;
import com.contextflow.contracts.generated.PublicApi.ErrorCode;
import com.contextflow.contracts.generated.PublicApi.ErrorEnvelope;
import com.contextflow.identity.demo.DemoUserFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
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
    @Profile("!demo")
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper)
            throws Exception {
        return chain(http, mapper, false);
    }

    @Bean
    @Profile("demo")
    SecurityFilterChain demoSecurityFilterChain(HttpSecurity http, ObjectMapper mapper)
            throws Exception {
        http.addFilterBefore(new DemoUserFilter(), AnonymousAuthenticationFilter.class);
        return chain(http, mapper, true);
    }

    private SecurityFilterChain chain(HttpSecurity http, ObjectMapper mapper, boolean demo)
            throws Exception {
        return http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                            .permitAll();
                    if (demo) {
                        auth.requestMatchers(
                                        HttpMethod.GET,
                                        "/api/v1/me",
                                        "/api/v1/me/personal-intelligence",
                                        "/api/v1/projects",
                                        "/api/v1/projects/*",
                                        "/api/v1/projects/*/conversations")
                                .authenticated();
                        auth.requestMatchers(
                                        HttpMethod.POST,
                                        "/api/v1/projects",
                                        "/api/v1/me/personal-skills",
                                        "/api/v1/projects/*/conversations")
                                .authenticated();
                        auth.requestMatchers(
                                        HttpMethod.PATCH,
                                        "/api/v1/me",
                                        "/api/v1/me/personal-skills/*",
                                        "/api/v1/projects/*",
                                        "/api/v1/projects/*/conversations/*")
                                .authenticated();
                        auth.requestMatchers(HttpMethod.DELETE, "/api/v1/me/personal-skills/*")
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
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "Idempotency-Key", "Last-Event-ID"));
        config.setExposedHeaders(List.of("X-Correlation-Id", "X-ContextFlow-Mode"));
        config.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
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
                                java.util.UUID.fromString(
                                        (String)
                                                request.getAttribute(
                                                        CorrelationIdFilter.ATTRIBUTE)),
                                null)));
    }
}
