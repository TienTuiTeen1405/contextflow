package com.contextflow.knowledge.port;

import com.contextflow.knowledge.dto.EmbeddingBatchRequest;
import com.contextflow.knowledge.dto.EmbeddingBatchResponse;

public interface EmbeddingClient {
    EmbeddingBatchResponse embedBatch(EmbeddingBatchRequest request, String providerCredential);
}
