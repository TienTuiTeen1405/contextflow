package com.contextflow.conversation.repository;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only lookup of a Project's owner and lifecycle status. The {@code projects} table belongs to
 * the project domain, so it is not mapped as a JPA entity here; switch to the project service once
 * PR-3A is merged.
 */
@Repository
@RequiredArgsConstructor
public class ProjectAccessRepository {
    private static final String SELECT_PROJECT =
            """
            SELECT id, owner_user_id, status
            FROM contextflow.projects
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public Optional<ProjectAccessRecord> findById(UUID projectId) {
        return query(SELECT_PROJECT, projectId);
    }

    /**
     * Same lookup holding a {@code FOR SHARE} row lock until the surrounding write transaction
     * ends, so the Project cannot switch to {@code DELETING} while children are being written.
     */
    public Optional<ProjectAccessRecord> findByIdForShare(UUID projectId) {
        return query(SELECT_PROJECT + "FOR SHARE", projectId);
    }

    private Optional<ProjectAccessRecord> query(String sql, UUID projectId) {
        return jdbcTemplate
                .query(
                        sql,
                        (rs, index) ->
                                new ProjectAccessRecord(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("owner_user_id", UUID.class),
                                        rs.getString("status")),
                        projectId)
                .stream()
                .findFirst();
    }
}
