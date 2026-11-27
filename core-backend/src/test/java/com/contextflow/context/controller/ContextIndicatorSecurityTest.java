package com.contextflow.context.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contextflow.common.api.ApiProblemHandler;
import com.contextflow.common.api.CorrelationIdFilter;
import com.contextflow.common.config.SecurityConfiguration;
import com.contextflow.context.mapper.ContextIndicatorMapper;
import com.contextflow.context.service.ContextCompilerService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = ContextIndicatorController.class,
        properties = {
            "contextflow.web.allowed-origins=http://localhost:3000",
            "CF_LOCAL_ENV_IMPORT=optional:classpath:/test-no-local-env.properties"
        })
@Import({SecurityConfiguration.class, CorrelationIdFilter.class, ApiProblemHandler.class})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class ContextIndicatorSecurityTest {
    private final MockMvc mvc;

    @MockitoBean private ContextCompilerService contextCompilerService;
    @MockitoBean private ContextIndicatorMapper contextIndicatorMapper;

    @Test
    void contextIndicatorRoutesRequireAuthenticationInDefaultProfile() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID convId = UUID.randomUUID();
        UUID stageId = UUID.randomUUID();

        mvc.perform(
                        get(
                                "/api/v1/projects/{projectId}/stages/{stageId}/context-indicator",
                                projectId,
                                stageId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        mvc.perform(
                        get(
                                "/api/v1/projects/{projectId}/conversations/{convId}/stages/{stageId}/context-indicator",
                                projectId,
                                convId,
                                stageId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
}
