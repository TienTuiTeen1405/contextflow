package com.contextflow.context.repository;

import com.contextflow.context.dto.ContentBlockDto;
import com.contextflow.context.dto.PrivacyTier;
import com.contextflow.context.dto.RuleEnforcementLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only JDBC repository querying V001-V003 tables directly without introducing cross-PR JPA
 * entity mappings.
 */
@Repository
@RequiredArgsConstructor
public class ContextSourceRepository {
    private static final TypeReference<List<Map<String, Object>>> NATIVE_STEPS_TYPE =
            new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public Optional<ProjectScopeRecord> findProjectScope(UUID projectId) {
        List<ProjectScopeRecord> rows =
                jdbcTemplate.query(
                        """
                        SELECT id, owner_user_id, project_information, project_intelligence_enabled, status
                        FROM contextflow.projects
                        WHERE id = ?
                        """,
                        (rs, index) ->
                                ProjectScopeRecord.builder()
                                        .id(rs.getObject("id", UUID.class))
                                        .ownerUserId(rs.getObject("owner_user_id", UUID.class))
                                        .projectInformation(rs.getString("project_information"))
                                        .projectIntelligenceEnabled(
                                                rs.getBoolean("project_intelligence_enabled"))
                                        .status(rs.getString("status"))
                                        .build(),
                        projectId);
        return rows.stream().findFirst();
    }

    public Optional<StageScopeRecord> findStageScope(
            UUID projectId, UUID expectedConversationId, UUID stageId, UUID subBranchId) {
        List<StageScopeRecord> rows =
                jdbcTemplate.query(
                        """
                        SELECT ms.id,
                               ms.project_id,
                               ms.conversation_id,
                               ms.parent_stage_id,
                               ms.ancestor_stage_ids,
                               ms.stage_order,
                               ms.title,
                               ms.status AS stage_status,
                               c.status AS conversation_status
                        FROM contextflow.main_stages ms
                        JOIN contextflow.conversations c
                          ON c.project_id = ms.project_id
                         AND c.id = ms.conversation_id
                        WHERE ms.project_id = ?
                          AND ms.id = ?
                        """,
                        (rs, index) ->
                                StageScopeRecord.builder()
                                        .id(rs.getObject("id", UUID.class))
                                        .projectId(rs.getObject("project_id", UUID.class))
                                        .conversationId(rs.getObject("conversation_id", UUID.class))
                                        .parentStageId(rs.getObject("parent_stage_id", UUID.class))
                                        .ancestorStageIds(
                                                readUuidArray(rs.getArray("ancestor_stage_ids")))
                                        .stageOrder(rs.getInt("stage_order"))
                                        .title(rs.getString("title"))
                                        .stageStatus(rs.getString("stage_status"))
                                        .conversationStatus(rs.getString("conversation_status"))
                                        .build(),
                        projectId,
                        stageId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StageScopeRecord stage = rows.getFirst();
        if (expectedConversationId != null
                && !expectedConversationId.equals(stage.getConversationId())) {
            return Optional.empty();
        }
        if (subBranchId != null) {
            List<UUID> anchorIds =
                    jdbcTemplate.query(
                            """
                            SELECT anchor_turn_id
                            FROM contextflow.sub_branches
                            WHERE project_id = ?
                              AND conversation_id = ?
                              AND main_stage_id = ?
                              AND id = ?
                            """,
                            (rs, index) -> rs.getObject("anchor_turn_id", UUID.class),
                            projectId,
                            stage.getConversationId(),
                            stageId,
                            subBranchId);
            if (anchorIds.isEmpty()) {
                return Optional.empty();
            }
            stage.setAnchorTurnId(anchorIds.getFirst());
        }
        return Optional.of(stage);
    }

    public Optional<UserIntelligenceRecord> findUserIntelligence(UUID userId) {
        List<UserIntelligenceRecord> rows =
                jdbcTemplate.query(
                        """
                        SELECT id, personal_intelligence_enabled, personal_profile_text, personal_instructions
                        FROM contextflow.users
                        WHERE id = ?
                        """,
                        (rs, index) ->
                                UserIntelligenceRecord.builder()
                                        .userId(rs.getObject("id", UUID.class))
                                        .personalIntelligenceEnabled(
                                                rs.getBoolean("personal_intelligence_enabled"))
                                        .personalProfileText(rs.getString("personal_profile_text"))
                                        .personalInstructions(rs.getString("personal_instructions"))
                                        .build(),
                        userId);
        return rows.stream().findFirst();
    }

    public List<SkillRecord> findEnabledPersonalSkills(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT id, name, description, content, priority, created_at
                FROM contextflow.personal_skills
                WHERE user_id = ?
                  AND is_enabled = true
                ORDER BY priority ASC, created_at ASC, id ASC
                """,
                this::mapSkill,
                userId);
    }

    public List<RuleRecord> findEnabledProjectRules(UUID projectId) {
        return jdbcTemplate.query(
                """
                SELECT id, name, content, enforcement_level, priority, created_at
                FROM contextflow.project_rules
                WHERE project_id = ?
                  AND is_enabled = true
                ORDER BY priority ASC, created_at ASC, id ASC
                """,
                (rs, index) ->
                        RuleRecord.builder()
                                .id(rs.getObject("id", UUID.class))
                                .name(rs.getString("name"))
                                .content(rs.getString("content"))
                                .enforcementLevel(
                                        RuleEnforcementLevel.valueOf(
                                                rs.getString("enforcement_level")))
                                .priority(rs.getInt("priority"))
                                .createdAt(readInstant(rs, "created_at"))
                                .build(),
                projectId);
    }

    public List<SkillRecord> findEnabledProjectSkills(UUID projectId) {
        return jdbcTemplate.query(
                """
                SELECT id, name, description, content, priority, created_at
                FROM contextflow.project_skills
                WHERE project_id = ?
                  AND is_enabled = true
                ORDER BY priority ASC, created_at ASC, id ASC
                """,
                this::mapSkill,
                projectId);
    }

    public List<ActiveWindowTurnRecord> findActiveWindowTurns(
            UUID projectId, UUID conversationId, UUID mainStageId, UUID subBranchId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<ActiveWindowTurnRecord> descending;
        if (subBranchId == null) {
            descending =
                    jdbcTemplate.query(
                            """
                            SELECT t.id AS turn_id,
                                   t.sequence_no,
                                   t.source_revision,
                                   t.user_content_blocks::text AS user_content_blocks_json,
                                   t.created_at,
                                   cte.ordinal,
                                   cte.native_output_steps::text AS native_output_steps_json
                            FROM contextflow.continuity_epochs ce
                            JOIN contextflow.continuity_turn_entries cte
                              ON cte.project_id = ce.project_id
                             AND cte.conversation_id = ce.conversation_id
                             AND cte.epoch_id = ce.id
                            JOIN contextflow.turns t
                              ON t.project_id = cte.project_id
                             AND t.conversation_id = cte.conversation_id
                             AND t.id = cte.turn_id
                            WHERE ce.project_id = ?
                              AND ce.conversation_id = ?
                              AND ce.main_stage_id = ?
                              AND ce.sub_branch_id IS NULL
                              AND ce.status = 'ACTIVE'
                              AND t.main_stage_id = ?
                              AND t.sub_branch_id IS NULL
                            ORDER BY cte.ordinal DESC
                            LIMIT ?
                            """,
                            this::mapActiveWindowTurn,
                            projectId,
                            conversationId,
                            mainStageId,
                            mainStageId,
                            limit);
        } else {
            descending =
                    jdbcTemplate.query(
                            """
                            SELECT t.id AS turn_id,
                                   t.sequence_no,
                                   t.source_revision,
                                   t.user_content_blocks::text AS user_content_blocks_json,
                                   t.created_at,
                                   cte.ordinal,
                                   cte.native_output_steps::text AS native_output_steps_json
                            FROM contextflow.continuity_epochs ce
                            JOIN contextflow.continuity_turn_entries cte
                              ON cte.project_id = ce.project_id
                             AND cte.conversation_id = ce.conversation_id
                             AND cte.epoch_id = ce.id
                            JOIN contextflow.turns t
                              ON t.project_id = cte.project_id
                             AND t.conversation_id = cte.conversation_id
                             AND t.id = cte.turn_id
                            WHERE ce.project_id = ?
                              AND ce.conversation_id = ?
                              AND ce.main_stage_id = ?
                              AND ce.sub_branch_id = ?
                              AND ce.status = 'ACTIVE'
                            ORDER BY cte.ordinal DESC
                            LIMIT ?
                            """,
                            this::mapActiveWindowTurn,
                            projectId,
                            conversationId,
                            mainStageId,
                            subBranchId,
                            limit);
        }
        if (descending.isEmpty()) {
            return List.of();
        }
        List<ActiveWindowTurnRecord> chronological = new ArrayList<>(descending);
        Collections.reverse(chronological);

        List<UUID> turnIds = chronological.stream().map(ActiveWindowTurnRecord::getTurnId).toList();
        Map<UUID, List<HistoricalQuoteRecord>> quotesByTurn =
                findQuotesByTurnIds(projectId, conversationId, turnIds);
        for (ActiveWindowTurnRecord turn : chronological) {
            turn.setHistoricalQuotes(quotesByTurn.getOrDefault(turn.getTurnId(), List.of()));
        }
        return chronological;
    }

    public List<HistoryTurnRecord> findNonActiveHistoryTurns(
            StageScopeRecord stage, UUID subBranchId, Set<UUID> activeWindowTurnIds) {
        List<HistoryTurnRecord> results = new ArrayList<>();
        Set<UUID> excluded = activeWindowTurnIds == null ? Set.of() : activeWindowTurnIds;
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);

        if (stage.getAncestorStageIds() != null && !stage.getAncestorStageIds().isEmpty()) {
            MapSqlParameterSource params =
                    new MapSqlParameterSource()
                            .addValue("projectId", stage.getProjectId())
                            .addValue("conversationId", stage.getConversationId())
                            .addValue("ancestorIds", stage.getAncestorStageIds());
            List<HistoryTurnRecord> ancestorTurns =
                    namedJdbc.query(
                            """
                            SELECT t.id AS turn_id,
                                   t.main_stage_id,
                                   ms.stage_order,
                                   t.chapter_id,
                                   t.sequence_no,
                                   t.source_revision,
                                   t.user_content_blocks::text AS user_content_blocks_json,
                                   t.assistant_text,
                                   t.created_at
                            FROM contextflow.turns t
                            JOIN contextflow.main_stages ms
                              ON ms.project_id = t.project_id
                             AND ms.conversation_id = t.conversation_id
                             AND ms.id = t.main_stage_id
                            WHERE t.project_id = :projectId
                              AND t.conversation_id = :conversationId
                              AND t.main_stage_id IN (:ancestorIds)
                              AND t.sub_branch_id IS NULL
                              AND ms.status IN ('ACTIVE', 'LOCKED_READONLY')
                            ORDER BY ms.stage_order ASC, t.sequence_no ASC, t.id ASC
                            """,
                            params,
                            this::mapHistoryTurn);
            for (HistoryTurnRecord turn : ancestorTurns) {
                if (!excluded.contains(turn.getTurnId())) {
                    results.add(turn);
                }
            }
        }

        if (subBranchId == null) {
            List<HistoryTurnRecord> currentStageTurns =
                    jdbcTemplate.query(
                            """
                            SELECT t.id AS turn_id,
                                   t.main_stage_id,
                                   ? AS stage_order,
                                   t.chapter_id,
                                   t.sequence_no,
                                   t.source_revision,
                                   t.user_content_blocks::text AS user_content_blocks_json,
                                   t.assistant_text,
                                   t.created_at
                            FROM contextflow.turns t
                            WHERE t.project_id = ?
                              AND t.conversation_id = ?
                              AND t.main_stage_id = ?
                              AND t.sub_branch_id IS NULL
                            ORDER BY t.sequence_no ASC, t.id ASC
                            """,
                            this::mapHistoryTurn,
                            stage.getStageOrder(),
                            stage.getProjectId(),
                            stage.getConversationId(),
                            stage.getId());
            for (HistoryTurnRecord turn : currentStageTurns) {
                if (!excluded.contains(turn.getTurnId())) {
                    results.add(turn);
                }
            }
        } else {
            Long anchorSeq = findAnchorSequence(stage);
            if (anchorSeq != null) {
                List<HistoryTurnRecord> parentAnchorWindow =
                        jdbcTemplate.query(
                                """
                                SELECT t.id AS turn_id,
                                       t.main_stage_id,
                                       ? AS stage_order,
                                       t.chapter_id,
                                       t.sequence_no,
                                       t.source_revision,
                                       t.user_content_blocks::text AS user_content_blocks_json,
                                       t.assistant_text,
                                       t.created_at
                                FROM contextflow.turns t
                                WHERE t.project_id = ?
                                  AND t.conversation_id = ?
                                  AND t.main_stage_id = ?
                                  AND t.sub_branch_id IS NULL
                                  AND t.sequence_no <= ?
                                ORDER BY t.sequence_no ASC, t.id ASC
                                """,
                                this::mapHistoryTurn,
                                stage.getStageOrder(),
                                stage.getProjectId(),
                                stage.getConversationId(),
                                stage.getId(),
                                anchorSeq);
                for (HistoryTurnRecord turn : parentAnchorWindow) {
                    if (!excluded.contains(turn.getTurnId())) {
                        results.add(turn);
                    }
                }
            }
            List<HistoryTurnRecord> branchTurns =
                    jdbcTemplate.query(
                            """
                            SELECT t.id AS turn_id,
                                   t.main_stage_id,
                                   ? AS stage_order,
                                   t.chapter_id,
                                   t.sequence_no,
                                   t.source_revision,
                                   t.user_content_blocks::text AS user_content_blocks_json,
                                   t.assistant_text,
                                   t.created_at
                            FROM contextflow.turns t
                            WHERE t.project_id = ?
                              AND t.conversation_id = ?
                              AND t.main_stage_id = ?
                              AND t.sub_branch_id = ?
                            ORDER BY t.sequence_no ASC, t.id ASC
                            """,
                            this::mapHistoryTurn,
                            stage.getStageOrder(),
                            stage.getProjectId(),
                            stage.getConversationId(),
                            stage.getId(),
                            subBranchId);
            Set<UUID> seen = new HashSet<>();
            results.forEach(item -> seen.add(item.getTurnId()));
            for (HistoryTurnRecord turn : branchTurns) {
                if (!excluded.contains(turn.getTurnId()) && seen.add(turn.getTurnId())) {
                    results.add(turn);
                }
            }
        }
        return results;
    }

    public List<ChapterSummaryRecord> findApprovedAncestorChapterSummaries(StageScopeRecord stage) {
        if (stage.getAncestorStageIds() == null || stage.getAncestorStageIds().isEmpty()) {
            return List.of();
        }
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("projectId", stage.getProjectId())
                        .addValue("conversationId", stage.getConversationId())
                        .addValue("ancestorIds", stage.getAncestorStageIds());
        return namedJdbc.query(
                """
                SELECT cs.id AS summary_id,
                       cs.chapter_id,
                       cs.main_stage_id,
                       ms.stage_order,
                       ch.start_sequence_no,
                       ch.end_sequence_no,
                       ch.title AS chapter_title,
                       cs.summary_text,
                       cs.approval_status,
                       cs.source_revision_snapshot,
                       cs.created_at
                FROM contextflow.chapter_summaries cs
                JOIN contextflow.chapters ch
                  ON ch.project_id = cs.project_id
                 AND ch.conversation_id = cs.conversation_id
                 AND ch.main_stage_id = cs.main_stage_id
                 AND ch.id = cs.chapter_id
                JOIN contextflow.main_stages ms
                  ON ms.project_id = ch.project_id
                 AND ms.conversation_id = ch.conversation_id
                 AND ms.id = ch.main_stage_id
                WHERE cs.project_id = :projectId
                  AND cs.conversation_id = :conversationId
                  AND cs.main_stage_id IN (:ancestorIds)
                  AND cs.approval_status IN ('USER_APPROVED', 'USER_EDITED')
                  AND ms.status IN ('ACTIVE', 'LOCKED_READONLY')
                ORDER BY ms.stage_order ASC, ch.display_order ASC, cs.version DESC, cs.id ASC
                """,
                params,
                (rs, index) ->
                        ChapterSummaryRecord.builder()
                                .summaryId(rs.getObject("summary_id", UUID.class))
                                .chapterId(rs.getObject("chapter_id", UUID.class))
                                .mainStageId(rs.getObject("main_stage_id", UUID.class))
                                .stageOrder(rs.getInt("stage_order"))
                                .startSequenceNo(rs.getObject("start_sequence_no", Long.class))
                                .endSequenceNo(rs.getObject("end_sequence_no", Long.class))
                                .chapterTitle(rs.getString("chapter_title"))
                                .summaryText(rs.getString("summary_text"))
                                .approvalStatus(rs.getString("approval_status"))
                                .sourceRevisionSnapshot(rs.getLong("source_revision_snapshot"))
                                .createdAt(readInstant(rs, "created_at"))
                                .build());
    }

    public CredentialTierSnapshotRecord findActiveCredentialTiers(UUID userId) {
        CredentialTierSnapshotRecord result = new CredentialTierSnapshotRecord();
        jdbcTemplate.query(
                """
                SELECT owner_scope, declared_tier
                FROM contextflow.ai_credentials
                WHERE status = 'ACTIVE'
                  AND ((owner_scope = 'USER' AND owner_user_id = ?) OR owner_scope = 'PLATFORM')
                """,
                rs -> {
                    String scope = rs.getString("owner_scope");
                    PrivacyTier tier = PrivacyTier.valueOf(rs.getString("declared_tier"));
                    if ("USER".equals(scope)) {
                        result.setUserTier(tier);
                    } else if ("PLATFORM".equals(scope)) {
                        result.setPlatformTier(tier);
                    }
                },
                userId);
        return result;
    }

    public Optional<Integer> findConfiguredSoftCapTokens() {
        List<String> rows =
                jdbcTemplate.query(
                        """
                        SELECT setting_value::text AS value_text
                        FROM contextflow.platform_settings
                        WHERE setting_key = 'max_input_tokens_soft_cap'
                        """,
                        (rs, index) -> rs.getString("value_text"));
        if (rows.isEmpty() || rows.getFirst() == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(rows.getFirst());
            if (node.isNumber()) {
                int value = node.asInt();
                return value > 0 ? Optional.of(value) : Optional.empty();
            }
            if (node.hasNonNull("value") && node.get("value").isNumber()) {
                int value = node.get("value").asInt();
                return value > 0 ? Optional.of(value) : Optional.empty();
            }
        } catch (JsonProcessingException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private Long findAnchorSequence(StageScopeRecord stage) {
        if (stage.getAnchorTurnId() == null) {
            return null;
        }
        List<Long> seqs =
                jdbcTemplate.query(
                        """
                        SELECT sequence_no
                        FROM contextflow.turns
                        WHERE project_id = ?
                          AND conversation_id = ?
                          AND main_stage_id = ?
                          AND id = ?
                        """,
                        (rs, index) -> rs.getLong("sequence_no"),
                        stage.getProjectId(),
                        stage.getConversationId(),
                        stage.getId(),
                        stage.getAnchorTurnId());
        return seqs.isEmpty() ? null : seqs.getFirst();
    }

    private Map<UUID, List<HistoricalQuoteRecord>> findQuotesByTurnIds(
            UUID projectId, UUID conversationId, List<UUID> turnIds) {
        if (turnIds.isEmpty()) {
            return Map.of();
        }
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("projectId", projectId)
                        .addValue("conversationId", conversationId)
                        .addValue("turnIds", turnIds);
        List<HistoricalQuoteRecord> quotes =
                namedJdbc.query(
                        """
                        SELECT id, turn_id, source_kind, snapshot_text, created_at
                        FROM contextflow.turn_quote_references
                        WHERE project_id = :projectId
                          AND conversation_id = :conversationId
                          AND turn_id IN (:turnIds)
                        ORDER BY created_at ASC, id ASC
                        """,
                        params,
                        (rs, index) ->
                                HistoricalQuoteRecord.builder()
                                        .id(rs.getObject("id", UUID.class))
                                        .turnId(rs.getObject("turn_id", UUID.class))
                                        .sourceKind(rs.getString("source_kind"))
                                        .snapshotText(rs.getString("snapshot_text"))
                                        .createdAt(readInstant(rs, "created_at"))
                                        .build());
        Map<UUID, List<HistoricalQuoteRecord>> grouped = new HashMap<>();
        for (HistoricalQuoteRecord quote : quotes) {
            grouped.computeIfAbsent(quote.getTurnId(), key -> new ArrayList<>()).add(quote);
        }
        return grouped;
    }

    private SkillRecord mapSkill(ResultSet rs, int index) throws SQLException {
        return SkillRecord.builder()
                .id(rs.getObject("id", UUID.class))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .content(rs.getString("content"))
                .priority(rs.getInt("priority"))
                .createdAt(readInstant(rs, "created_at"))
                .build();
    }

    private ActiveWindowTurnRecord mapActiveWindowTurn(ResultSet rs, int index)
            throws SQLException {
        return ActiveWindowTurnRecord.builder()
                .turnId(rs.getObject("turn_id", UUID.class))
                .sequenceNo(rs.getLong("sequence_no"))
                .sourceRevision(rs.getLong("source_revision"))
                .ordinal(rs.getLong("ordinal"))
                .rawUserContentBlocks(parseContentBlocks(rs.getString("user_content_blocks_json")))
                .nativeOutputSteps(parseNativeSteps(rs.getString("native_output_steps_json")))
                .createdAt(readInstant(rs, "created_at"))
                .build();
    }

    private HistoryTurnRecord mapHistoryTurn(ResultSet rs, int index) throws SQLException {
        List<ContentBlockDto> blocks = parseContentBlocks(rs.getString("user_content_blocks_json"));
        StringBuilder userText = new StringBuilder();
        for (ContentBlockDto block : blocks) {
            if ("TEXT".equals(block.getType()) && block.getText() != null) {
                if (!userText.isEmpty()) {
                    userText.append(' ');
                }
                userText.append(block.getText());
            } else if ("QUOTE_REF".equals(block.getType()) && block.getQuoteRefId() != null) {
                if (!userText.isEmpty()) {
                    userText.append(' ');
                }
                userText.append("[quote]");
            }
        }
        return HistoryTurnRecord.builder()
                .turnId(rs.getObject("turn_id", UUID.class))
                .mainStageId(rs.getObject("main_stage_id", UUID.class))
                .stageOrder(rs.getInt("stage_order"))
                .chapterId(rs.getObject("chapter_id", UUID.class))
                .sequenceNo(rs.getLong("sequence_no"))
                .sourceRevision(rs.getLong("source_revision"))
                .userText(userText.toString())
                .assistantText(rs.getString("assistant_text"))
                .createdAt(readInstant(rs, "created_at"))
                .build();
    }

    private List<ContentBlockDto> parseContentBlocks(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arrayNode = objectMapper.readTree(json);
            if (!arrayNode.isArray()) {
                return List.of();
            }
            List<ContentBlockDto> blocks = new ArrayList<>();
            for (JsonNode item : arrayNode) {
                String type = item.path("type").asText("");
                if ("TEXT".equals(type)) {
                    blocks.add(ContentBlockDto.textBlock(item.path("text").asText("")));
                } else if ("QUOTE_REF".equals(type)) {
                    String quoteRefId =
                            item.hasNonNull("quoteRefId")
                                    ? item.get("quoteRefId").asText()
                                    : item.path("quote_ref_id").asText("");
                    if (!quoteRefId.isBlank()) {
                        blocks.add(ContentBlockDto.quoteRefBlock(quoteRefId));
                    }
                }
            }
            return blocks;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid user_content_blocks JSON in turns table", ex);
        }
    }

    private List<Map<String, Object>> parseNativeSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, NATIVE_STEPS_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Invalid native_output_steps JSON in continuity_turn_entries table", ex);
        }
    }

    private static List<UUID> readUuidArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        try {
            Object raw = array.getArray();
            if (!(raw instanceof Object[] elements)) {
                return List.of();
            }
            List<UUID> ids = new ArrayList<>(elements.length);
            for (Object element : elements) {
                if (element instanceof UUID uuid) {
                    ids.add(uuid);
                } else if (element != null) {
                    ids.add(UUID.fromString(element.toString()));
                }
            }
            return ids;
        } finally {
            array.free();
        }
    }

    private static Instant readInstant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
