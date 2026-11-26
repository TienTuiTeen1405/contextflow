package com.contextflow.identity.demo;

import java.util.Arrays;
import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
public class DemoUserSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final Environment environment;

    public DemoUserSeeder(JdbcTemplate jdbc, Environment environment) {
        this.jdbc = jdbc;
        this.environment = environment;
    }

    @Transactional
    public void run(ApplicationArguments arguments) {
        if (Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> p.equals("prod") || p.equals("production")))
            throw new IllegalStateException("Demo profile cannot be combined with production.");
        jdbc.update(
                """
                INSERT INTO contextflow.users(id,email,display_name,role,status)
                VALUES (?,?,'Demo User','USER','ACTIVE') ON CONFLICT (id) DO NOTHING
                """,
                DemoIdentity.USER_ID,
                DemoIdentity.EMAIL);
        Boolean valid =
                jdbc.queryForObject(
                        """
                        SELECT email=? AND role='USER' AND status='ACTIVE' AND password_hash IS NULL
                        FROM contextflow.users WHERE id=?
                        """,
                        Boolean.class,
                        DemoIdentity.EMAIL,
                        DemoIdentity.USER_ID);
        if (!Boolean.TRUE.equals(valid))
            throw new IllegalStateException("Reserved demo user conflicts with existing data.");
    }
}
