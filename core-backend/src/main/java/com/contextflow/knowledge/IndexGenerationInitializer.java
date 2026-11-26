package com.contextflow.knowledge;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IndexGenerationInitializer {
    private final JdbcTemplate jdbc;

    public IndexGenerationInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void initialize(UUID project) {
        jdbc.update(
                """
                INSERT INTO contextflow.index_generations(id,project_id,embedding_model,output_dimensionality,status)
                VALUES (?,?,'gemini-embedding-2',1536,'ACTIVE')
                """,
                UUID.randomUUID(),
                project);
    }
}
