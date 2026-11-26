package com.contextflow.knowledge.service.impl;

import com.contextflow.knowledge.service.IndexGenerationBootstrap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IndexGenerationBootstrapImpl implements IndexGenerationBootstrap {
    private static final String DEFAULT_EMBEDDING_MODEL = "gemini-embedding-2";
    private static final int DEFAULT_OUTPUT_DIMENSIONALITY = 1536;

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public UUID initializeForProject(UUID projectId) {
        UUID generationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.index_generations
                    (id, project_id, embedding_model, output_dimensionality, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """,
                generationId,
                projectId,
                DEFAULT_EMBEDDING_MODEL,
                DEFAULT_OUTPUT_DIMENSIONALITY);
        return generationId;
    }
}
