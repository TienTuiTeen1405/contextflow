package com.contextflow.conversation.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contextflow.common.api.ApiProblemHandler;
import com.contextflow.common.api.CorrelationIdFilter;
import com.contextflow.common.config.SecurityConfiguration;
import com.contextflow.conversation.service.ConversationService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Without the local-demo profile every conversation route stays closed (401). */
@WebMvcTest(
        controllers = ConversationController.class,
        properties = {
            "contextflow.web.allowed-origins=http://localhost:3000",
            "CF_LOCAL_ENV_IMPORT=optional:classpath:/test-no-local-env.properties"
        })
@Import({SecurityConfiguration.class, CorrelationIdFilter.class, ApiProblemHandler.class})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class ConversationSecurityTest {
    private final MockMvc mvc;

    @MockitoBean private ConversationService conversationService;

    @Test
    void conversationRoutesRequireAuthenticationInDefaultProfile() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();

        expectUnauthorized(get("/api/v1/projects/{projectId}/conversations", projectId));
        expectUnauthorized(
                post("/api/v1/projects/{projectId}/conversations", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hội thoại\"}"));
        expectUnauthorized(
                patch(
                                "/api/v1/projects/{projectId}/conversations/{conversationId}",
                                projectId,
                                conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Tên mới\"}"));
        verifyNoInteractions(conversationService);
    }

    private void expectUnauthorized(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
}
