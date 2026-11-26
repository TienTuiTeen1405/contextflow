package com.contextflow.conversation;

import com.contextflow.contracts.generated.PublicApi.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {
    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String VIEW =
            """
            SELECT c.*, (SELECT s.id FROM contextflow.main_stages s
                WHERE s.project_id=c.project_id AND s.conversation_id=c.id
                ORDER BY s.stage_order DESC,s.id LIMIT 1) AS main_stage_id
            FROM contextflow.conversations c JOIN contextflow.projects p ON p.id=c.project_id
            """;

    private ConversationView map(ResultSet row, int index) throws SQLException {
        return new ConversationView(
                row.getObject("id", UUID.class),
                row.getObject("project_id", UUID.class),
                row.getString("title"),
                ConversationStatus.valueOf(row.getString("status")),
                row.getObject("main_stage_id", UUID.class),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }

    public List<ConversationView> list(
            UUID user, UUID project, String status, int size, long offset) {
        return jdbc.query(
                VIEW
                        + """
                        WHERE p.owner_user_id=? AND c.project_id=? AND c.status <> 'DELETING'
                        AND (?::text IS NULL OR c.status=?) ORDER BY c.updated_at DESC,c.id LIMIT ? OFFSET ?
                        """,
                this::map,
                user,
                project,
                status,
                status,
                size,
                offset);
    }

    public long count(UUID user, UUID project, String status) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM contextflow.conversations c JOIN contextflow.projects p ON p.id=c.project_id
                WHERE p.owner_user_id=? AND c.project_id=? AND c.status <> 'DELETING'
                AND (?::text IS NULL OR c.status=?)
                """,
                Long.class,
                user,
                project,
                status,
                status);
    }

    public Optional<ConversationView> find(UUID user, UUID project, UUID id, boolean lock) {
        return jdbc
                .query(
                        VIEW
                                + " WHERE p.owner_user_id=? AND c.project_id=? AND c.id=?"
                                + (lock ? " FOR UPDATE OF c" : ""),
                        this::map,
                        user,
                        project,
                        id)
                .stream()
                .findFirst();
    }

    public void create(UUID project, UUID conversation, String title, UUID stage) {
        jdbc.update(
                "INSERT INTO contextflow.conversations(id,project_id,title) VALUES (?,?,?)",
                conversation,
                project,
                title);
        jdbc.update(
                """
                INSERT INTO contextflow.main_stages(id,project_id,conversation_id,stage_order,title)
                VALUES (?,?,?,1,'Main 1')
                """,
                stage,
                project,
                conversation);
    }

    public void rename(UUID user, UUID project, UUID id, String title) {
        jdbc.update(
                """
                UPDATE contextflow.conversations c SET title=?,updated_at=now()
                WHERE c.id=? AND c.project_id=?
                    AND EXISTS(SELECT 1 FROM contextflow.projects p WHERE p.id=c.project_id AND p.owner_user_id=?)
                """,
                title,
                id,
                project,
                user);
    }
}
