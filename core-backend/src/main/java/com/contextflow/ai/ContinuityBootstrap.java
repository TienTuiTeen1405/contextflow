package com.contextflow.ai;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ContinuityBootstrap {
    private final JdbcTemplate jdbc;

    public ContinuityBootstrap(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void initializeDemoMain(UUID project, UUID conversation, UUID stage) {
        byte[] compatibility;
        try {
            compatibility =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    "DEMO|MOCK|INTERACTIONS|mock|v1"
                                            .getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        jdbc.update(
                """
                INSERT INTO contextflow.continuity_epochs(id,project_id,conversation_id,main_stage_id,
                    route_kind,route_id,epoch_no,status,provider,api_interface,model,compatibility_key)
                VALUES (?,?,?,?,'MAIN',?,1,'ACTIVE','MOCK','INTERACTIONS','mock',?)
                """,
                UUID.randomUUID(),
                project,
                conversation,
                stage,
                stage,
                compatibility);
    }
}
