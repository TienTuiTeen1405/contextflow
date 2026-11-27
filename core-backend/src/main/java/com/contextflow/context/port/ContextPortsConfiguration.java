package com.contextflow.context.port;

import com.contextflow.context.dto.KnowledgeRetrievalResult;
import com.contextflow.context.dto.SourceOfTruthResult;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Provides safe empty fallback implementations for Block D extension ports so the context compiler
 * works standalone prior to Branch 6 and SoT integration. Registered as AutoConfiguration so
 * user/domain beans in any package (e.g. retrieval, sot) always take precedence regardless of scan
 * order.
 */
@AutoConfiguration
public class ContextPortsConfiguration {
    static final String RETRIEVAL_NOT_CONFIGURED_REASON = "RETRIEVAL_NOT_CONFIGURED";
    static final String SOT_NOT_CONFIGURED_REASON = "SOT_NOT_CONFIGURED";

    @Bean
    @ConditionalOnMissingBean(KnowledgeRetrievalPort.class)
    KnowledgeRetrievalPort defaultKnowledgeRetrievalPort() {
        return request -> KnowledgeRetrievalResult.unavailable(RETRIEVAL_NOT_CONFIGURED_REASON);
    }

    @Bean
    @ConditionalOnMissingBean(SourceOfTruthPort.class)
    SourceOfTruthPort defaultSourceOfTruthPort() {
        return request -> SourceOfTruthResult.unavailable(SOT_NOT_CONFIGURED_REASON);
    }
}
