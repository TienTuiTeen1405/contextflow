package com.contextflow.context.port;

import static org.junit.jupiter.api.Assertions.*;

import com.contextflow.context.dto.DegradedComponentDto;
import com.contextflow.context.dto.DegradedComponentStatus;
import com.contextflow.context.dto.DegradedComponentType;
import com.contextflow.context.dto.EvidenceSufficiency;
import com.contextflow.context.dto.KnowledgeCandidateDto;
import com.contextflow.context.dto.KnowledgeRetrievalRequest;
import com.contextflow.context.dto.KnowledgeRetrievalResult;
import com.contextflow.context.dto.RetrievalStatus;
import com.contextflow.context.dto.SourceOfTruthRecordDto;
import com.contextflow.context.dto.SourceOfTruthRequest;
import com.contextflow.context.dto.SourceOfTruthResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ContextPortsConfigurationTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ContextPortsConfiguration.class));

    @Test
    void registersSafeEmptyFallbacksWhenNoProviderBeansPresent() {
        contextRunner.run(context -> {
            KnowledgeRetrievalPort kbPort = context.getBean(KnowledgeRetrievalPort.class);
            SourceOfTruthPort sotPort = context.getBean(SourceOfTruthPort.class);

            KnowledgeRetrievalResult kbResult =
                    kbPort.retrieve(
                            KnowledgeRetrievalRequest.builder()
                                    .projectId(UUID.randomUUID())
                                    .queryText("test")
                                    .build());
            SourceOfTruthResult sotResult =
                    sotPort.retrieve(
                            SourceOfTruthRequest.builder()
                                    .projectId(UUID.randomUUID())
                                    .queryText("test")
                                    .build());

            assertTrue(kbResult.getItems().isEmpty());
            assertEquals(RetrievalStatus.UNAVAILABLE, kbResult.getRetrievalStatus());
            assertEquals(EvidenceSufficiency.INSUFFICIENT, kbResult.getEvidenceSufficiency());
            assertEquals(
                    List.of(
                            DegradedComponentType.VECTOR_SEARCH,
                            DegradedComponentType.KEYWORD_SEARCH),
                    kbResult.getDegradedComponents().stream()
                            .map(DegradedComponentDto::getComponent)
                            .toList());
            assertTrue(
                    kbResult.getDegradedComponents().stream()
                            .allMatch(c ->
                                    c.getStatus() == DegradedComponentStatus.UNAVAILABLE
                                            && "RETRIEVAL_NOT_CONFIGURED"
                                                    .equals(c.getReasonCode())));
            assertTrue(sotResult.getRecords().isEmpty());
            assertTrue(sotResult.isDegraded());
            assertEquals("SOT_NOT_CONFIGURED", sotResult.getDegradedReasonCode());
        });
    }

    @Test
    void backsOffWhenBranch6OrSotRegistersCustomBeans() {
        contextRunner
                .withUserConfiguration(CustomPortsConfig.class)
                .run(context -> {
                    KnowledgeRetrievalPort kbPort = context.getBean(KnowledgeRetrievalPort.class);
                    SourceOfTruthPort sotPort = context.getBean(SourceOfTruthPort.class);

                    KnowledgeRetrievalResult kbResult =
                            kbPort.retrieve(KnowledgeRetrievalRequest.builder().build());
                    SourceOfTruthResult sotResult =
                            sotPort.retrieve(SourceOfTruthRequest.builder().build());

                    assertEquals(1, kbResult.getItems().size());
                    assertEquals(1, sotResult.getRecords().size());
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPortsConfig {
        @Bean
        KnowledgeRetrievalPort customKbPort() {
            return request ->
                    KnowledgeRetrievalResult.builder()
                            .items(
                                    List.of(
                                            KnowledgeCandidateDto.builder()
                                                    .knowledgeItemId(UUID.randomUUID())
                                                    .content("custom kb")
                                                    .build()))
                            .build();
        }

        @Bean
        SourceOfTruthPort customSotPort() {
            return request ->
                    SourceOfTruthResult.builder()
                            .records(
                                    List.of(
                                            SourceOfTruthRecordDto.builder()
                                                    .decisionId(UUID.randomUUID())
                                                    .title("custom sot")
                                                    .decisionContent("custom")
                                                    .build()))
                            .build();
        }
    }
}
