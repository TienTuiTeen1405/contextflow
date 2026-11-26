package com.contextflow.identity;

import com.contextflow.contracts.generated.PublicApi.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserProfile> find(UUID id) {
        return jdbc
                .query(
                        """
                        SELECT id, email, display_name, role, status, theme_preference,
                            password_hash IS NOT NULL AS has_local_password, personal_intelligence_enabled
                        FROM contextflow.users WHERE id = ?
                        """,
                        this::profile,
                        id)
                .stream()
                .findFirst();
    }

    private UserProfile profile(ResultSet row, int index) throws SQLException {
        return new UserProfile(
                row.getObject("id", UUID.class),
                row.getString("email"),
                row.getString("display_name"),
                UserRole.valueOf(row.getString("role")),
                UserStatus.valueOf(row.getString("status")),
                ThemePreference.valueOf(row.getString("theme_preference")),
                row.getBoolean("has_local_password"),
                row.getBoolean("personal_intelligence_enabled"));
    }

    public void update(UUID id, String displayName, ThemePreference theme) {
        jdbc.update(
                "UPDATE contextflow.users SET display_name=COALESCE(?,display_name), theme_preference=COALESCE(?,theme_preference), updated_at=now() WHERE id=?",
                displayName,
                theme == null ? null : theme.name(),
                id);
    }

    public record Intelligence(String profile, String instructions) {}

    public Intelligence intelligence(UUID id) {
        return jdbc.queryForObject(
                "SELECT personal_profile_text, personal_instructions FROM contextflow.users WHERE id=?",
                (row, index) -> new Intelligence(row.getString(1), row.getString(2)),
                id);
    }
}
