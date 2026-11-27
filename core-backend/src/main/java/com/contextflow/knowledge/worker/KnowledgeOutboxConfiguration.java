package com.contextflow.knowledge.worker;

import com.contextflow.knowledge.port.EmbeddingCredentialProvider;
import java.time.Clock;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@EnableConfigurationProperties(KnowledgeOutboxProperties.class)
public class KnowledgeOutboxConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock knowledgeOutboxClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(TokenBucketTpmLimiter.class)
    TokenBucketTpmLimiter tokenBucketTpmLimiter(KnowledgeOutboxProperties properties, Clock clock) {
        return new TokenBucketTpmLimiter(properties.getTokensPerMinute(), clock);
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingCredentialProvider.class)
    EmbeddingCredentialProvider defaultEmbeddingCredentialProvider(
            KnowledgeOutboxProperties properties, Environment environment) {
        return projectId -> {
            String configured = properties.getPlatformEmbeddingCredential();
            if (configured != null && !configured.isBlank()) {
                return Optional.of(configured.trim());
            }
            String fromEnv = environment.getProperty("CF_PLATFORM_EMBEDDING_CREDENTIAL");
            if (fromEnv != null && !fromEnv.isBlank()) {
                return Optional.of(fromEnv.trim());
            }
            return Optional.empty();
        };
    }
}
