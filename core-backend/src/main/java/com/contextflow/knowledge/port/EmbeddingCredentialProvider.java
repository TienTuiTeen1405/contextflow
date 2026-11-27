package com.contextflow.knowledge.port;

import java.util.Optional;
import java.util.UUID;

public interface EmbeddingCredentialProvider {
    Optional<String> resolvePlatformCredential(UUID projectId);
}
