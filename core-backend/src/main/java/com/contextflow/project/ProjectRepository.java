package com.contextflow.project;

import com.contextflow.contracts.generated.PublicApi.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {
    private final JdbcTemplate jdbc;

    public ProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private ProjectView map(ResultSet row, int index) throws SQLException {
        return new ProjectView(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getString("description"),
                row.getBoolean("project_intelligence_enabled"),
                ProjectStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }

    public Optional<ProjectView> find(UUID user, UUID id, boolean lock) {
        return jdbc
                .query(
                        "SELECT * FROM contextflow.projects WHERE owner_user_id=? AND id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        this::map,
                        user,
                        id)
                .stream()
                .findFirst();
    }

    public List<ProjectView> list(UUID user, String status, int size, long offset) {
        return jdbc.query(
                """
                SELECT * FROM contextflow.projects WHERE owner_user_id=? AND status IN ('ACTIVE','ARCHIVED')
                AND (?::text IS NULL OR status=?) ORDER BY updated_at DESC,id LIMIT ? OFFSET ?
                """,
                this::map,
                user,
                status,
                status,
                size,
                offset);
    }

    public long count(UUID user, String status) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM contextflow.projects WHERE owner_user_id=? AND status IN ('ACTIVE','ARCHIVED')
                AND (?::text IS NULL OR status=?)
                """,
                Long.class,
                user,
                status,
                status);
    }

    public void insert(UUID user, UUID id, String name, String description) {
        jdbc.update(
                "INSERT INTO contextflow.projects(id,owner_user_id,name,description) VALUES (?,?,?,?)",
                id,
                user,
                name,
                description);
    }

    public void update(UUID user, UUID id, String name, String description) {
        jdbc.update(
                "UPDATE contextflow.projects SET name=?,description=?,updated_at=now() WHERE owner_user_id=? AND id=?",
                name,
                description,
                user,
                id);
    }
}
