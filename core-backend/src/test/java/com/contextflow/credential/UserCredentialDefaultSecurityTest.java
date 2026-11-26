package com.contextflow.credential;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.contextflow.common.api.CorrelationIdFilter;
import com.contextflow.common.config.SecurityConfiguration;
import com.contextflow.credential.controller.UserCredentialController;
import com.contextflow.credential.service.UserCredentialService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = UserCredentialController.class,
        properties = "CF_LOCAL_ENV_IMPORT=optional:classpath:/test-no-local-env.properties")
@Import({SecurityConfiguration.class, CorrelationIdFilter.class})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class UserCredentialDefaultSecurityTest {
    private static final String CREDENTIAL = "/api/v1/me/credential";
    private final MockMvc mvc;

    @MockitoBean private UserCredentialService service;

    @Test
    void defaultProfileDeniesAllCredentialRoutes() throws Exception {
        for (var request :
                List.of(
                        get(CREDENTIAL),
                        put(CREDENTIAL),
                        delete(CREDENTIAL),
                        post(CREDENTIAL + "/verify"),
                        post("/api/v1/me/privacy-acknowledgements"))) {
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists("X-Correlation-Id"))
                    .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        }
        verifyNoInteractions(service);
    }
}
