package com.contextflow.identity.demo;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local-demo")
@RequiredArgsConstructor
public class LocalDemoUserSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        boolean productionActive =
                Arrays.stream(environment.getActiveProfiles())
                        .anyMatch(
                                profile ->
                                        "prod".equalsIgnoreCase(profile)
                                                || "production".equalsIgnoreCase(profile));
        if (productionActive) {
            throw new IllegalStateException(
                    "local-demo profile cannot be combined with production.");
        }
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.users (id, email, display_name, role, status)
                VALUES (?, ?, ?, 'USER', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """,
                LocalDemoIdentity.USER_ID,
                LocalDemoIdentity.EMAIL,
                LocalDemoIdentity.DISPLAY_NAME);
        Boolean valid =
                jdbcTemplate.queryForObject(
                        """
                        SELECT email = ?
                            AND role = 'USER'
                            AND status = 'ACTIVE'
                            AND password_hash IS NULL
                        FROM contextflow.users
                        WHERE id = ?
                        """,
                        Boolean.class,
                        LocalDemoIdentity.EMAIL,
                        LocalDemoIdentity.USER_ID);
        if (!Boolean.TRUE.equals(valid)) {
            throw new IllegalStateException("Reserved local-demo user conflicts with DB data.");
        }
    }
}
