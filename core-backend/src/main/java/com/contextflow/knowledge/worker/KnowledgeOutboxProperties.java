package com.contextflow.knowledge.worker;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("contextflow.knowledge.outbox")
public class KnowledgeOutboxProperties {
    private int batchSize = 10;
    private int maxAttempts = 5;
    private int leaseDurationSeconds = 60;
    private int tokensPerMinute = 120_000;
    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 30;
    private String internalServiceToken = "";
    private String platformEmbeddingCredential = "";
}
