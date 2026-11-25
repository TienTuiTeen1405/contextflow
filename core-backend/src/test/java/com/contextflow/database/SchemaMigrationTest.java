package com.contextflow.database;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class SchemaMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.1-pg16")
                            .asCompatibleSubstituteFor("postgres"));

    private static Flyway flyway;
    private Connection connection;
    private UUID user;
    private UUID project;
    private UUID otherProject;
    private UUID conversation;
    private UUID otherConversation;
    private UUID stage;
    private UUID otherStage;

    @BeforeAll
    static void migrateEmptyDatabase() {
        flyway =
                Flyway.configure()
                        .dataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .defaultSchema("contextflow")
                        .schemas("contextflow")
                        .cleanDisabled(true)
                        .load();
        assertEquals(5, flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @BeforeEach
    void createIsolatedFixtures() throws SQLException {
        connection =
                DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setAutoCommit(false);
        execute("SET LOCAL search_path TO contextflow, public");
        user = UUID.randomUUID();
        project = UUID.randomUUID();
        otherProject = UUID.randomUUID();
        conversation = UUID.randomUUID();
        otherConversation = UUID.randomUUID();
        stage = UUID.randomUUID();
        otherStage = UUID.randomUUID();
        execute(
                "INSERT INTO users(id, email, display_name) VALUES (?, ?, 'Schema test')",
                user,
                user + "@example.test");
        execute(
                "INSERT INTO projects(id, owner_user_id, name) VALUES (?, ?, 'Project'), (?, ?, 'Other')",
                project,
                user,
                otherProject,
                user);
        execute(
                "INSERT INTO conversations(id, project_id, title) VALUES (?, ?, 'Main'), (?, ?, 'Other')",
                conversation,
                project,
                otherConversation,
                otherProject);
        execute(
                """
                INSERT INTO main_stages(id, project_id, conversation_id, stage_order, title)
                VALUES (?, ?, ?, 1, 'Main'), (?, ?, ?, 1, 'Other')
                """,
                stage,
                project,
                conversation,
                otherStage,
                otherProject,
                otherConversation);
    }

    @AfterEach
    void discardFixtures() throws SQLException {
        if (connection != null) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    @Test
    @DisplayName("DB-IT-01: five Flyway migrations, 43 tables, seed and FTS trigger")
    void migrationCreatesCompleteSchema() throws SQLException {
        assertEquals(
                43,
                number(
                        """
                        SELECT count(*) FROM pg_tables
                        WHERE schemaname = 'contextflow' AND tablename <> 'flyway_schema_history'
                        """));
        assertEquals(
                5,
                number(
                        "SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success"));
        assertEquals(
                "s",
                value(
                        """
                        SELECT provolatile::text FROM pg_proc
                        WHERE oid = 'public.unaccent(text)'::regprocedure
                        """));
        assertEquals(
                "0.8.1", value("SELECT extversion FROM pg_extension WHERE extname = 'vector'"));
        assertEquals(10, number("SELECT count(*) FROM task_model_registry"));
        assertEquals(
                8,
                number(
                        """
                        SELECT count(*) FROM task_model_registry
                        WHERE api_interface = 'INTERACTIONS' AND primary_model = 'gemini-3.8-flash'
                            AND fallback_model IS NULL
                        """));
        UUID generation = generation(project);
        UUID item = item(project);
        UUID chunk = chunk(project, item, generation);
        assertEquals(
                1,
                number(
                        """
                        SELECT count(*) FROM knowledge_chunks
                        WHERE id = ? AND search_document @@ websearch_to_tsquery('public.cf_vi', 'du lieu')
                        """,
                        chunk));
        execute(
                "UPDATE knowledge_chunks SET search_text = 'Kiến trúc hệ thống' WHERE id = ?",
                chunk);
        assertEquals(
                1,
                number(
                        """
                        SELECT count(*) FROM knowledge_chunks
                        WHERE id = ? AND search_document @@ websearch_to_tsquery('public.cf_vi', 'kien truc')
                        """,
                        chunk));
    }

    @Test
    @DisplayName(
            "DB-IT-02: composite foreign keys reject cross-project quote, continuity and outbox")
    void compositeKeysKeepProjectsIsolated() throws SQLException {
        UUID turn = turn(project, conversation, stage, 1);
        UUID foreignTurn = turn(otherProject, otherConversation, otherStage, 1);
        UUID epoch = epoch(null);
        rejects(
                "23503",
                """
                INSERT INTO turn_quote_references(
                    id, project_id, conversation_id, turn_id, source_kind, source_id,
                    source_revision, locator, content_hash, snapshot_text)
                VALUES (?, ?, ?, ?, 'MAIN_TURN', ?, '1', '{}', decode(repeat('ab', 32), 'hex'), 'Quote')
                """,
                UUID.randomUUID(),
                project,
                conversation,
                foreignTurn,
                turn);
        rejects(
                "23503",
                """
                INSERT INTO continuity_turn_entries(
                    project_id, conversation_id, epoch_id, ordinal, turn_id, native_output_steps)
                VALUES (?, ?, ?, 1, ?, '[]')
                """,
                project,
                conversation,
                epoch,
                foreignTurn);
        UUID generation = generation(project);
        UUID item = item(project);
        UUID chunk = chunk(project, item, generation);
        rejects(
                "23503",
                """
                INSERT INTO embedding_outbox(
                    id, project_id, knowledge_item_id, chunk_id, index_generation_id,
                    source_revision, idempotency_key, status)
                VALUES (?, ?, ?, ?, ?, 1, ?, 'PENDING')
                """,
                UUID.randomUUID(),
                otherProject,
                item,
                chunk,
                generation,
                UUID.randomUUID().toString());
    }

    @Test
    @DisplayName("DB-IT-03: one active user credential and one active platform credential")
    void activeCredentialUniqueness() throws SQLException {
        credential("USER", user);
        rejects("23505", credentialSql(), UUID.randomUUID(), "USER", user);
        credential("PLATFORM", null);
        rejects("23505", credentialSql(), UUID.randomUUID(), "PLATFORM", null);
    }

    @Test
    @DisplayName(
            "DB-IT-04: epochs stay on their branch and completed requests survive epoch deletion")
    void epochRouteAndSelectiveSetNull() throws SQLException {
        UUID anchor = turn(project, conversation, stage, 1);
        UUID branchA = branch(anchor);
        UUID branchB = branch(anchor);
        UUID epochA = epoch(branchA);
        epoch(branchB);
        rejects(
                "23503",
                requestSql(),
                UUID.randomUUID(),
                user,
                project,
                conversation,
                stage,
                branchB,
                epochA,
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "COMPLETED");
        UUID request = request(project, conversation, stage, branchA, epochA, "COMPLETED");
        execute("UPDATE continuity_epochs SET status = 'INVALIDATED' WHERE id = ?", epochA);
        execute("DELETE FROM continuity_epochs WHERE id = ?", epochA);
        assertNull(value("SELECT epoch_id FROM ai_requests WHERE id = ?", request));
        assertEquals(project, value("SELECT project_id FROM ai_requests WHERE id = ?", request));
    }

    @Test
    @DisplayName("DB-IT-05: one open chat per route, independent branches and one terminal event")
    void chatSlotsAndTerminalEvents() throws SQLException {
        UUID mainEpoch = epoch(null);
        UUID request = request(project, conversation, stage, null, mainEpoch, "ACCEPTED");
        rejects(
                "23505",
                requestSql(),
                UUID.randomUUID(),
                user,
                project,
                conversation,
                stage,
                null,
                mainEpoch,
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "ACCEPTED");
        UUID anchor = turn(project, conversation, stage, 1);
        UUID branchA = branch(anchor);
        UUID branchB = branch(anchor);
        request(project, conversation, stage, branchA, epoch(branchA), "ACCEPTED");
        request(project, conversation, stage, branchB, epoch(branchB), "ACCEPTED");
        assertEquals(3, number("SELECT count(*) FROM ai_requests WHERE status = 'ACCEPTED'"));
        execute(
                """
                INSERT INTO ai_request_events(request_id, event_id, event_type, payload)
                VALUES (?, 1, 'assistant.completed', '{}')
                """,
                request);
        rejects(
                "23505",
                """
                INSERT INTO ai_request_events(request_id, event_id, event_type, payload)
                VALUES (?, 2, 'request.cancelled', '{}')
                """,
                request);
    }

    @Test
    @DisplayName("DB-IT-06: skipped deletion stages require warning acknowledgement")
    void deletionSkipRequiresAcknowledgement() throws SQLException {
        UUID session = UUID.randomUUID();
        execute(
                """
                INSERT INTO deletion_sessions(id, project_id, initiated_by_user_id, target_type, target_id, status)
                VALUES (?, ?, ?, 'PROJECT', ?, 'PREPARING')
                """,
                session,
                project,
                user,
                project);
        String insert =
                """
                INSERT INTO deletion_stage_items(id, deletion_session_id, project_id, main_stage_id,
                    previous_stage_status, stage_status, skip_warning_acknowledged)
                VALUES (?, ?, ?, ?, 'ACTIVE', 'SKIPPED', ?)
                """;
        rejects("23514", insert, UUID.randomUUID(), session, project, stage, false);
        execute(insert, UUID.randomUUID(), session, project, stage, true);
    }

    @Test
    @DisplayName("DB-IT-07: a SoT decision has at most one successor")
    void decisionHasOneSuccessor() throws SQLException {
        String insert =
                """
                INSERT INTO sot_decisions(id, project_id, title, scope_description, decision_content,
                    supersedes_decision_id, confirmed_by_user_id)
                VALUES (?, ?, 'Decision', 'Scope', 'Content', ?, ?)
                """;
        UUID original = UUID.randomUUID();
        execute(insert, original, project, null, user);
        execute(insert, UUID.randomUUID(), project, original, user);
        rejects("23505", insert, UUID.randomUUID(), project, original, user);
    }

    @Test
    @DisplayName(
            "DB-IT-08: conversation deletion retains usage; project deletion retains user counters")
    void usageRetentionAndProjectCascade() throws SQLException {
        UUID call = usage();
        execute(
                """
                INSERT INTO user_usage_counters(user_id, period_key, total_requests)
                VALUES (?, '2026-11', 1)
                """,
                user);
        execute("DELETE FROM conversations WHERE id = ?", conversation);
        assertEquals(
                1,
                number("SELECT count(*) FROM ai_usage_records WHERE provider_call_id = ?", call));
        execute("DELETE FROM projects WHERE id = ?", project);
        assertEquals(
                0,
                number("SELECT count(*) FROM ai_usage_records WHERE provider_call_id = ?", call));
        assertEquals(
                1,
                number("SELECT total_requests FROM user_usage_counters WHERE user_id = ?", user));
        assertEquals(1, number("SELECT count(*) FROM projects WHERE id = ?", otherProject));
    }

    @Test
    @DisplayName("DB-IT-09: scoped vector search and Vietnamese FTS amid 10,000 foreign chunks")
    void hybridSearchFiltersProjectAndInactiveSources() throws SQLException {
        UUID generation = generation(project);
        UUID item = item(project);
        UUID expected = chunk(project, item, generation);
        UUID secondExpected = chunk(project, item, generation);
        execute(
                "UPDATE knowledge_chunks SET embedding = CAST(? AS public.vector(1536)) WHERE id = ?",
                "[0.9,0.1" + ",0".repeat(1534) + "]",
                secondExpected);
        UUID inactiveChunk = chunk(project, item, generation);
        execute("UPDATE knowledge_chunks SET is_active = false WHERE id = ?", inactiveChunk);
        UUID inactiveItem = item(project);
        chunk(project, inactiveItem, generation);
        execute(
                "UPDATE knowledge_items SET is_active = false, soft_deleted_at = now() WHERE id = ?",
                inactiveItem);
        UUID stale = chunk(project, item, generation);
        execute("UPDATE knowledge_chunks SET source_revision = 2 WHERE id = ?", stale);
        UUID oldGeneration = UUID.randomUUID();
        execute(
                """
                INSERT INTO index_generations(id, project_id, embedding_model, output_dimensionality, status)
                VALUES (?, ?, 'mock', 1536, 'SUPERSEDED')
                """,
                oldGeneration,
                project);
        chunk(project, item, oldGeneration);
        execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
        String search =
                """
                WITH candidates AS MATERIALIZED (
                    SELECT c.id, c.embedding <=> CAST(? AS public.vector(1536)) AS distance
                    FROM knowledge_chunks c
                    JOIN knowledge_items i ON i.project_id = c.project_id
                        AND i.id = c.knowledge_item_id AND i.source_revision = c.source_revision
                    JOIN index_generations g ON g.project_id = c.project_id AND g.id = c.index_generation_id
                    WHERE c.project_id = ? AND c.is_active AND i.is_active AND g.status = 'ACTIVE'
                        AND c.embedding IS NOT NULL
                    ORDER BY c.embedding <=> CAST(? AS public.vector(1536)) LIMIT 20
                )
                SELECT id FROM candidates ORDER BY distance + 0, id
                """;
        List<UUID> expectedIds = List.of(expected, secondExpected);
        assertEquals(expectedIds, ids(search, vector(), project, vector()));
        UUID foreignGeneration = generation(otherProject);
        UUID foreignItem = item(otherProject);
        execute(
                """
                INSERT INTO knowledge_chunks(id, project_id, knowledge_item_id, index_generation_id,
                    source_type, source_revision, chunk_index, content, search_text, embedding)
                SELECT gen_random_uuid(), ?, ?, ?, 'MAIN_TURN', 1, n, 'Other', 'Dữ liệu',
                    CAST(? AS public.vector(1536))
                FROM generate_series(1, 10000) AS n
                """,
                otherProject,
                foreignItem,
                foreignGeneration,
                "[0,1" + ",0".repeat(1534) + "]");
        execute("ANALYZE knowledge_chunks");
        List<UUID> approximateIds = ids(search, vector(), project, vector());
        // ANN may return fewer candidates; every returned row must still belong to the valid set.
        assertTrue(expectedIds.containsAll(approximateIds), approximateIds.toString());
        assertEquals(approximateIds.size(), Set.copyOf(approximateIds).size());
        assertEquals(
                expectedIds,
                ids(
                        """
                        WITH scoped AS MATERIALIZED (
                            SELECT c.id, c.embedding FROM knowledge_chunks c
                            JOIN knowledge_items i ON i.project_id = c.project_id
                                AND i.id = c.knowledge_item_id AND i.source_revision = c.source_revision
                            JOIN index_generations g ON g.project_id = c.project_id AND g.id = c.index_generation_id
                            WHERE c.project_id = ? AND c.index_generation_id = ? AND c.is_active
                                AND i.is_active AND g.status = 'ACTIVE' AND c.embedding IS NOT NULL
                        )
                        SELECT id FROM scoped ORDER BY embedding <=> CAST(? AS public.vector(1536)), id LIMIT 20
                        """,
                        project,
                        generation,
                        vector()));
        assertEquals(
                Set.copyOf(expectedIds),
                Set.copyOf(
                        ids(
                                """
                                SELECT c.id FROM knowledge_chunks c
                                JOIN knowledge_items i ON i.project_id = c.project_id
                                    AND i.id = c.knowledge_item_id AND i.source_revision = c.source_revision
                                JOIN index_generations g ON g.project_id = c.project_id AND g.id = c.index_generation_id
                                WHERE c.project_id = ? AND c.is_active AND i.is_active AND g.status = 'ACTIVE'
                                    AND c.search_document @@ websearch_to_tsquery('public.cf_vi', 'du lieu')
                                """,
                                project)));
        assertNotNull(value("EXPLAIN (ANALYZE, BUFFERS) " + search, vector(), project, vector()));
    }

    @Test
    @DisplayName("DB-IT-10: replayed usage accounting adds only actual deltas")
    void usageDeltaIsIdempotent() throws Exception {
        UUID call = usage();
        String deltaSql =
                new String(
                        getClass()
                                .getResourceAsStream("/db/queries/usage_delta.sql")
                                .readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        // The first call accounts 100/20 tokens; replay changes nothing.
        execute(deltaSql, call, 100L, 20L, "2026-11");
        execute(deltaSql, call, 100L, 20L, "2026-11");
        assertEquals(
                100,
                number(
                        "SELECT total_input_tokens FROM user_usage_counters WHERE user_id = ?",
                        user));
        assertEquals(
                20,
                number(
                        "SELECT total_output_tokens FROM user_usage_counters WHERE user_id = ?",
                        user));
        assertEquals(
                1,
                number("SELECT total_requests FROM user_usage_counters WHERE user_id = ?", user));
        execute(deltaSql, call, 125L, 25L, "2026-11");
        assertEquals(
                125,
                number(
                        "SELECT total_input_tokens FROM user_usage_counters WHERE user_id = ?",
                        user));
        assertEquals(
                25,
                number(
                        "SELECT total_output_tokens FROM user_usage_counters WHERE user_id = ?",
                        user));
        assertEquals(
                1,
                number("SELECT total_requests FROM user_usage_counters WHERE user_id = ?", user));
    }

    private UUID epoch(UUID branch) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                """
                INSERT INTO continuity_epochs(id, project_id, conversation_id, main_stage_id,
                    sub_branch_id, epoch_no, status, provider, api_interface, model, compatibility_key)
                VALUES (?, ?, ?, ?, ?, 1, 'ACTIVE', 'MOCK', 'INTERACTIONS', 'mock',
                    decode(repeat('ab', 32), 'hex'))
                """,
                id,
                project,
                conversation,
                stage,
                branch);
        return id;
    }

    private UUID branch(UUID anchor) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                """
                INSERT INTO sub_branches(id, project_id, conversation_id, main_stage_id, anchor_turn_id, title)
                VALUES (?, ?, ?, ?, ?, 'Branch')
                """,
                id,
                project,
                conversation,
                stage,
                anchor);
        return id;
    }

    private String requestSql() {
        return """
        INSERT INTO ai_requests(id, user_id, project_id, conversation_id, main_stage_id,
            sub_branch_id, epoch_id, idempotency_key, correlation_id, status,
            payload_hash, queued_input, finished_at)
        SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, status,
            decode(repeat('ab', 32), 'hex'),
            CASE WHEN status = 'ACCEPTED' THEN '{}'::jsonb END,
            CASE WHEN status = 'COMPLETED' THEN now() END
        FROM (SELECT CAST(? AS text) AS status) AS input
        """;
    }

    private UUID request(UUID p, UUID c, UUID s, UUID branch, UUID epoch, String status)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                requestSql(),
                id,
                user,
                p,
                c,
                s,
                branch,
                epoch,
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                status);
        return id;
    }

    private UUID turn(UUID p, UUID c, UUID s, int sequence) throws SQLException {
        UUID request = request(p, c, s, null, null, "COMPLETED");
        UUID id = UUID.randomUUID();
        execute(
                """
                INSERT INTO turns(id, project_id, conversation_id, main_stage_id, request_id,
                    sequence_no, user_content_blocks, assistant_text)
                VALUES (?, ?, ?, ?, ?, ?, '[{"type":"text","text":"Question"}]', 'Answer')
                """,
                id,
                p,
                c,
                s,
                request,
                sequence);
        return id;
    }

    private String credentialSql() {
        return """
        INSERT INTO ai_credentials(id, owner_scope, owner_user_id, credential_version,
            status, masked_label, encrypted_secret, wrapped_dek, kek_version, declared_tier)
        VALUES (?, ?, ?, 1, 'ACTIVE', 'test-only', decode('ab', 'hex'), decode('cd', 'hex'), 'v1', 'UNKNOWN')
        """;
    }

    private void credential(String scope, UUID owner) throws SQLException {
        execute(credentialSql(), UUID.randomUUID(), scope, owner);
    }

    private UUID generation(UUID p) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                """
                INSERT INTO index_generations(id, project_id, embedding_model, output_dimensionality, status)
                VALUES (?, ?, 'mock', 1536, 'ACTIVE')
                """,
                id,
                p);
        return id;
    }

    private UUID item(UUID p) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                "INSERT INTO knowledge_items(id, project_id, item_type, content) VALUES (?, ?, 'MAIN_TURN', 'Dữ liệu')",
                id,
                p);
        return id;
    }

    private UUID chunk(UUID p, UUID item, UUID generation) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                """
                INSERT INTO knowledge_chunks(id, project_id, knowledge_item_id, index_generation_id,
                    source_type, source_revision, chunk_index, content, search_text, embedding)
                VALUES (?, ?, ?, ?, 'MAIN_TURN', 1,
                    (SELECT count(*)::integer FROM knowledge_chunks WHERE knowledge_item_id = ?),
                    'Dữ liệu', 'Dữ liệu tiếng Việt', CAST(? AS public.vector(1536)))
                """,
                id,
                p,
                item,
                generation,
                item,
                vector());
        return id;
    }

    private UUID usage() throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                """
                INSERT INTO ai_usage_records(provider_call_id, correlation_id, contextflow_user_id,
                    contextflow_project_id, source_conversation_id, task_type, provider, api_interface,
                    model, credential_id, credential_version, owner_scope, credential_tier,
                    attempt_number, occurred_at, execution_status, usage_status)
                VALUES (?, ?, ?, ?, ?, 'CHAT_MAIN', 'MOCK', 'INTERACTIONS', 'mock', ?, 1,
                    'USER', 'UNKNOWN', 1, now(), 'COMPLETED', 'AVAILABLE')
                """,
                id,
                UUID.randomUUID(),
                user,
                project,
                conversation,
                UUID.randomUUID());
        return id;
    }

    private static String vector() {
        return "[1" + ",0".repeat(1535) + "]";
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(sql, parameters)) {
            statement.execute();
        }
    }

    private Object value(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(sql, parameters);
                ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getObject(1) : null;
        }
    }

    private long number(String sql, Object... parameters) throws SQLException {
        return ((Number) value(sql, parameters)).longValue();
    }

    private List<UUID> ids(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(sql, parameters);
                ResultSet result = statement.executeQuery()) {
            List<UUID> ids = new ArrayList<>();
            while (result.next()) {
                ids.add(result.getObject(1, UUID.class));
            }
            return ids;
        }
    }

    private PreparedStatement prepare(String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
        return statement;
    }

    private void rejects(String sqlState, String sql, Object... parameters) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            SQLException exception =
                    assertThrows(
                            SQLException.class,
                            () -> {
                                execute(sql, parameters);
                                execute("SET CONSTRAINTS ALL IMMEDIATE");
                            });
            assertEquals(sqlState, exception.getSQLState(), exception.getMessage());
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }
}
