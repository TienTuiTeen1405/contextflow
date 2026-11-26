package com.contextflow.identity;

import com.contextflow.contracts.generated.PublicApi.SkillView;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PersonalSkillRepository {
    private final JdbcTemplate jdbc;

    public PersonalSkillRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private SkillView map(ResultSet row, int index) throws SQLException {
        return new SkillView(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getString("description"),
                row.getString("content"),
                row.getLong("priority"),
                row.getBoolean("is_enabled"));
    }

    public List<SkillView> list(UUID user) {
        return jdbc.query(
                "SELECT * FROM contextflow.personal_skills WHERE user_id=? ORDER BY priority,id",
                this::map,
                user);
    }

    public Optional<SkillView> find(UUID user, UUID id) {
        return jdbc
                .query(
                        "SELECT * FROM contextflow.personal_skills WHERE user_id=? AND id=? FOR UPDATE",
                        this::map,
                        user,
                        id)
                .stream()
                .findFirst();
    }

    public void insert(UUID user, SkillView value) {
        jdbc.update(
                """
                INSERT INTO contextflow.personal_skills(id,user_id,name,description,content,priority,is_enabled)
                VALUES (?,?,?,?,?,?,?)
                """,
                value.id(),
                user,
                value.name(),
                value.description(),
                value.content(),
                value.priority(),
                value.isEnabled());
    }

    public void update(UUID user, SkillView value) {
        jdbc.update(
                """
                UPDATE contextflow.personal_skills SET name=?,description=?,content=?,priority=?,is_enabled=?,updated_at=now()
                WHERE user_id=? AND id=?
                """,
                value.name(),
                value.description(),
                value.content(),
                value.priority(),
                value.isEnabled(),
                user,
                value.id());
    }

    public int delete(UUID user, UUID id) {
        return jdbc.update(
                "DELETE FROM contextflow.personal_skills WHERE user_id=? AND id=?", user, id);
    }
}
