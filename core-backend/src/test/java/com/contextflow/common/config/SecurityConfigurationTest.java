package com.contextflow.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.contextflow.common.api.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        controllers = SecurityConfigurationTest.ProbeController.class,
        properties = "contextflow.web.allowed-origins=http://localhost:3000")
@Import({
    SecurityConfiguration.class,
    CorrelationIdFilter.class,
    SecurityConfigurationTest.ProbeController.class
})
class SecurityConfigurationTest {
    @Autowired private MockMvc mvc;

    @Test
    void businessRoutesStayClosedUntilAuthenticationIsImplemented() throws Exception {
        mvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.correlationId").isNotEmpty());
    }

    @Test
    void untrustedOriginsCannotReadResponses() throws Exception {
        mvc.perform(get("/api/v1/projects").header(HttpHeaders.ORIGIN, "https://untrusted.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/projects")
        String projects() {
            return "must not be exposed";
        }
    }
}
