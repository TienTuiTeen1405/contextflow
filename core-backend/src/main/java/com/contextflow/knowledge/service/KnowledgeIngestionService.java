package com.contextflow.knowledge.service;

import com.contextflow.knowledge.dto.KnowledgeIngestionResult;
import com.contextflow.knowledge.dto.TurnIngestionRequest;

public interface KnowledgeIngestionService {
    KnowledgeIngestionResult ingestCommittedMainTurn(TurnIngestionRequest request);
}
