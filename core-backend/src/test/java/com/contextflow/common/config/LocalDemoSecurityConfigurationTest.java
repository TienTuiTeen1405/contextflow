package com.contextflow.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contextflow.common.api.CorrelationIdFilter;
import com.contextflow.identity.UserIdentity;
import com.contextflow.identity.demo.LocalDemoIdentity;
import com.contextflow.identity.demo.LocalDemoUserFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        controllers = LocalDemoSecurityConfigurationTest.ProbeController.class,
        properties = {
            "contextflow.web.allowed-origins=http://localhost:3000",
            "CF_LOCAL_ENV_IMPORT=optional:classpath:/test-no-local-env.properties"
        })
@ActiveProfiles("local-demo")
@Import({
    SecurityConfiguration.class,
    CorrelationIdFilter.class,
    LocalDemoSecurityConfigurationTest.ProbeController.class
})
class LocalDemoSecurityConfigurationTest {
    @Autowired private MockMvc mvc;

    @Test
    void whitelistedMvpRouteUsesFixedDemoPrincipalAndIgnoresForgedHeaders() throws Exception {
        mvc.perform(
                        get("/api/v1/projects")
                                .header("X-User-Id", "11111111-1111-4111-8111-111111111111")
                                .header("Authorization", "Bearer forged-token"))
                .andExpect(status().isOk())
                .andExpect(header().string(LocalDemoUserFilter.MODE_HEADER, "LOCAL_DEMO"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(content().string(LocalDemoIdentity.USER_ID.toString()));
    }

    @Test
    void adminAndAuthRoutesRemainForbiddenInLocalDemoProfile() throws Exception {
        mvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/auth/login"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/projects")
        String projects(Authentication authentication) {
            UserIdentity identity = (UserIdentity) authentication.getPrincipal();
            return identity.getUserId().toString();
        }

        @GetMapping("/api/v1/admin/users")
        String adminUsers() {
            return "blocked";
        }

        @PostMapping("/api/v1/auth/login")
        String login() {
            return "blocked";
        }
    }
}
