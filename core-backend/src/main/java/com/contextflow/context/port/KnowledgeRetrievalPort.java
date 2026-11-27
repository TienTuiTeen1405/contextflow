package com.contextflow.context.port;

import com.contextflow.context.dto.KnowledgeRetrievalRequest;
import com.contextflow.context.dto.KnowledgeRetrievalResult;

/** Extension port for plugging Project KB Hybrid Retrieval (Branch 6) into Block D. */
public interface KnowledgeRetrievalPort {
    KnowledgeRetrievalResult retrieve(KnowledgeRetrievalRequest request);
}
