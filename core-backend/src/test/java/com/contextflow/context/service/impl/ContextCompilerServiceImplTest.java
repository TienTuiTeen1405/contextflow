package com.contextflow.context.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.contextflow.common.api.ApiProblem;
import com.contextflow.common.api.ErrorCode;
import com.contextflow.context.budget.ContextBudgetManager;
import com.contextflow.context.budget.LocalTokenEstimator;
import com.contextflow.context.compiler.ContextDeduplicator;
import com.contextflow.context.dto.CompiledContextResult;
import com.contextflow.context.dto.ContentBlockDto;
import com.contextflow.context.dto.ContextCompileRequest;
import com.contextflow.context.dto.DegradedComponentDto;
import com.contextflow.context.dto.DegradedComponentStatus;
import com.contextflow.context.dto.DegradedComponentType;
import com.contextflow.context.dto.EvidenceSufficiency;
import com.contextflow.context.dto.ExplicitReferenceInputDto;
import com.contextflow.context.dto.InstructionItemDto;
import com.contextflow.context.dto.KnowledgeCandidateDto;
import com.contextflow.context.dto.KnowledgeRetrievalResult;
import com.contextflow.context.dto.PrivacyTier;
import com.contextflow.context.dto.RetrievalStatus;
import com.contextflow.context.dto.RuleEnforcementLevel;
import com.contextflow.context.dto.SourceOfTruthRecordDto;
import com.contextflow.context.dto.SourceOfTruthResult;
import com.contextflow.context.port.KnowledgeRetrievalPort;
import com.contextflow.context.port.SourceOfTruthPort;
import com.contextflow.context.repository.ActiveWindowTurnRecord;
import com.contextflow.context.repository.ContextSourceRepository;
import com.contextflow.context.repository.CredentialTierSnapshotRecord;
import com.contextflow.context.repository.HistoricalQuoteRecord;
import com.contextflow.context.repository.ProjectScopeRecord;
import com.contextflow.context.repository.RuleRecord;
import com.contextflow.context.repository.SkillRecord;
import com.contextflow.context.repository.StageScopeRecord;
import com.contextflow.context.repository.UserIntelligenceRecord;
import com.contextflow.identity.service.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContextCompilerServiceImplTest {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID CONV_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID STAGE_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");

    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private ContextSourceRepository contextSourceRepository;
    @Mock private KnowledgeRetrievalPort knowledgeRetrievalPort;
    @Mock private SourceOfTruthPort sourceOfTruthPort;

    private ContextCompilerServiceImpl service;

    @BeforeEach
    void setUp() {
        LocalTokenEstimator tokenEstimator = new LocalTokenEstimator(new ObjectMapper());
        service =
                new ContextCompilerServiceImpl(
                        currentUserProvider,
                        contextSourceRepository,
                        knowledgeRetrievalPort,
                        sourceOfTruthPort,
                        new ContextDeduplicator(),
                        new ContextBudgetManager(tokenEstimator));
    }

    @Test
    void enforcesOwnershipAndDeletingLifecycle() {
        when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
        when(contextSourceRepository.findProjectScope(PROJECT_ID))
                .thenReturn(
                        Optional.of(
                                ProjectScopeRecord.builder()
                                        .id(PROJECT_ID)
                                        .ownerUserId(OTHER_USER_ID)
                                        .projectIntelligenceEnabled(true)
                                        .status("ACTIVE")
                                        .build()));

        ApiProblem crossUser =
                assertThrows(
                        ApiProblem.class,
                        () ->
                                service.compile(
                                        ContextCompileRequest.builder()
                                                .projectId(PROJECT_ID)
                                                .mainStageId(STAGE_ID)
                                                .build()));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, crossUser.code());

        when(contextSourceRepository.findProjectScope(PROJECT_ID))
                .thenReturn(
                        Optional.of(
                                ProjectScopeRecord.builder()
                                        .id(PROJECT_ID)
                                        .ownerUserId(USER_ID)
                                        .projectIntelligenceEnabled(true)
                                        .status("DELETING")
                                        .build()));

        ApiProblem deleting =
                assertThrows(
                        ApiProblem.class,
                        () ->
                                service.compile(
                                        ContextCompileRequest.builder()
                                                .projectId(PROJECT_ID)
                                                .mainStageId(STAGE_ID)
                                                .build()));
        assertEquals(ErrorCode.PROJECT_DELETING, deleting.code());
    }

    @Test
    void toggleIsolationExcludesDisabledLayerWithoutTouchingOtherLayerOrRepositoryMutations() {
        stubBaseScope(false, true);
        when(contextSourceRepository.findEnabledProjectRules(PROJECT_ID))
                .thenReturn(
                        List.of(
                                RuleRecord.builder()
                                        .id(UUID.randomUUID())
                                        .name("Rule M")
                                        .content("Must cite sources")
                                        .enforcementLevel(RuleEnforcementLevel.MANDATORY)
                                        .priority(10)
                                        .createdAt(Instant.parse("2026-11-26T00:00:00Z"))
                                        .build()));
        when(contextSourceRepository.findEnabledProjectSkills(PROJECT_ID))
                .thenReturn(
                        List.of(
                                SkillRecord.builder()
                                        .id(UUID.randomUUID())
                                        .name("Project Skill")
                                        .content("Use domain glossary")
                                        .priority(5)
                                        .createdAt(Instant.parse("2026-11-26T00:00:00Z"))
                                        .build()));

        CompiledContextResult result =
                service.compile(
                        ContextCompileRequest.builder()
                                .projectId(PROJECT_ID)
                                .conversationId(CONV_ID)
                                .mainStageId(STAGE_ID)
                                .build());

        assertFalse(result.isPersonalIntelligenceEnabled());
        assertTrue(result.isProjectIntelligenceEnabled());
        assertNull(
                result.getPromptEnvelope()
                        .getIntelligenceContext()
                        .getBackground()
                        .getPersonalProfile());
        assertNull(
                result.getPromptEnvelope()
                        .getIntelligenceContext()
                        .getInstructions()
                        .getPersonalInstructions());
        assertEquals(
                "Project background",
                result.getPromptEnvelope()
                        .getIntelligenceContext()
                        .getBackground()
                        .getProjectInformation());
        assertEquals(1, result.getMandatoryRulesCount());
        assertEquals(1, result.getActiveSkillsCount());
        verify(contextSourceRepository, never()).findEnabledPersonalSkills(any());

        // Flip toggles: personal ON, project OFF
        stubBaseScope(true, false);
        when(contextSourceRepository.findEnabledPersonalSkills(USER_ID))
                .thenReturn(
                        List.of(
                                SkillRecord.builder()
                                        .id(UUID.randomUUID())
                                        .name("Personal Skill")
                                        .content("Reply concisely")
                                        .priority(1)
                                        .createdAt(Instant.parse("2026-11-26T00:00:00Z"))
                                        .build()));

        CompiledContextResult flipped =
                service.compile(
                        ContextCompileRequest.builder()
                                .projectId(PROJECT_ID)
                                .conversationId(CONV_ID)
                                .mainStageId(STAGE_ID)
                                .build());

        assertTrue(flipped.isPersonalIntelligenceEnabled());
        assertFalse(flipped.isProjectIntelligenceEnabled());
        assertNull(
                flipped.getPromptEnvelope()
                        .getIntelligenceContext()
                        .getBackground()
                        .getProjectInformation());
        assertEquals(0, flipped.getMandatoryRulesCount());
        assertEquals(0, flipped.getOverridableRulesCount());
        assertEquals(1, flipped.getActiveSkillsCount());
        assertEquals(
                "Personal profile",
                flipped.getPromptEnvelope()
                        .getIntelligenceContext()
                        .getBackground()
                        .getPersonalProfile());
        assertEquals(
                "Personal instructions",
                flipped.getPromptEnvelope()
                        .getIntelligenceContext()
                        .getInstructions()
                        .getPersonalInstructions());
        verify(contextSourceRepository, times(1)).findEnabledProjectRules(any());
        verify(contextSourceRepository, times(1)).findEnabledProjectSkills(any());
    }

    @Test
    void separatesAndDeterministicallySortsMandatoryAndOverridableRules() {
        stubBaseScope(true, true);
        UUID idB = UUID.fromString("00000000-0000-4000-8000-00000000000b");
        UUID idA = UUID.fromString("00000000-0000-4000-8000-00000000000a");
        Instant t1 = Instant.parse("2026-11-26T00:00:01Z");
        Instant t2 = Instant.parse("2026-11-26T00:00:02Z");

        when(contextSourceRepository.findEnabledProjectRules(PROJECT_ID))
                .thenReturn(
                        List.of(
                                RuleRecord.builder()
                                        .id(idB)
                                        .name("M-late")
                                        .content("M priority 10 t2")
                                        .enforcementLevel(RuleEnforcementLevel.MANDATORY)
                                        .priority(10)
                                        .createdAt(t2)
                                        .build(),
                                RuleRecord.builder()
                                        .id(idB)
                                        .name("O-tie-b")
                                        .content("O priority 5 t1 idB")
                                        .enforcementLevel(RuleEnforcementLevel.OVERRIDABLE)
                                        .priority(5)
                                        .createdAt(t1)
                                        .build(),
                                RuleRecord.builder()
                                        .id(idA)
                                        .name("M-early")
                                        .content("M priority 10 t1")
                                        .enforcementLevel(RuleEnforcementLevel.MANDATORY)
                                        .priority(10)
                                        .createdAt(t1)
                                        .build(),
                                RuleRecord.builder()
                                        .id(idA)
                                        .name("O-tie-a")
                                        .content("O priority 5 t1 idA")
                                        .enforcementLevel(RuleEnforcementLevel.OVERRIDABLE)
                                        .priority(5)
                                        .createdAt(t1)
                                        .build(),
                                RuleRecord.builder()
                                        .id(UUID.randomUUID())
                                        .name("M-top")
                                        .content("M priority 1")
                                        .enforcementLevel(RuleEnforcementLevel.MANDATORY)
                                        .priority(1)
                                        .createdAt(t2)
                                        .build()));

        CompiledContextResult result =
                service.compile(
                        ContextCompileRequest.builder()
                                .projectId(PROJECT_ID)
                                .conversationId(CONV_ID)
                                .mainStageId(STAGE_ID)
                                .build());

        List<InstructionItemDto> mandatory =
                result.getPromptEnvelope()
                        .getIntelligenceContext()
                        .getInstructions()
                        .getMandatoryRules();
        List<InstructionItemDto> overridable =
                result.getPromptEnvelope()
                        .getIntelligenceContext()
                        .getInstructions()
                        .getOverridableRules();

        assertEquals(
                List.of("M priority 1", "M priority 10 t1", "M priority 10 t2"),
                mandatory.stream().map(InstructionItemDto::getContent).toList());
        assertEquals(
                List.of("O priority 5 t1 idA", "O priority 5 t1 idB"),
                overridable.stream().map(InstructionItemDto::getContent).toList());
        mandatory.forEach(item ->
                assertThrows(IllegalArgumentException.class, () -> UUID.fromString(item.getId())));
    }

    @Test
    void activeWindowCapsAtEightTurnsAndPreservesThreeElementAllowlistWithLocalAliases() {
        stubBaseScope(false, false);

        List<ActiveWindowTurnRecord> tenTurns = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            UUID turnId = UUID.randomUUID();
            UUID quoteId = UUID.randomUUID();
            tenTurns.add(
                    ActiveWindowTurnRecord.builder()
                            .turnId(turnId)
                            .sequenceNo(i)
                            .ordinal(i)
                            .sourceRevision(1)
                            .rawUserContentBlocks(
                                    List.of(
                                            ContentBlockDto.textBlock("Prompt " + i),
                                            ContentBlockDto.quoteRefBlock(quoteId.toString())))
                            .historicalQuotes(
                                    List.of(
                                            HistoricalQuoteRecord.builder()
                                                    .id(quoteId)
                                                    .turnId(turnId)
                                                    .sourceKind("MAIN_TURN")
                                                    .snapshotText("Snapshot " + i)
                                                    .build()))
                            .nativeOutputSteps(
                                    List.of(
                                            Map.of(
                                                    "type",
                                                    "text",
                                                    "text",
                                                    "Answer " + i,
                                                    "thought_signature",
                                                    "sig-" + i)))
                            .build());
        }
        when(contextSourceRepository.findActiveWindowTurns(PROJECT_ID, CONV_ID, STAGE_ID, null, 8))
                .thenReturn(tenTurns);

        CompiledContextResult result =
                service.compile(
                        ContextCompileRequest.builder()
                                .projectId(PROJECT_ID)
                                .conversationId(CONV_ID)
                                .mainStageId(STAGE_ID)
                                .build());

        assertEquals(8, result.getActiveWindowTurnsCount());
        var continuity = result.getPromptEnvelope().getContinuity();
        assertEquals(8, continuity.size());
        assertEquals(List.of("Prompt 3"), continuity.getFirst().getUserTextBlocks());
        assertEquals(List.of("Prompt 10"), continuity.getLast().getUserTextBlocks());
        assertEquals(1, continuity.getFirst().getHistoricalQuotes().size());
        assertEquals(
                "Snapshot 3",
                continuity.getFirst().getHistoricalQuotes().getFirst().getSnapshotText());
        assertEquals(
                "sig-3",
                continuity.getFirst().getNativeOutputSteps().getFirst().get("thought_signature"));
        assertThrows(
                IllegalArgumentException.class,
                () -> UUID.fromString(continuity.getFirst().getTurnRef()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UUID.fromString(
                                continuity
                                        .getFirst()
                                        .getHistoricalQuotes()
                                        .getFirst()
                                        .getQuoteRefId()));
        assertEquals(
                continuity.getFirst().getHistoricalQuotes().getFirst().getQuoteRefId(),
                continuity.getFirst().getUserContentBlocks().get(1).getQuoteRefId());
    }

    @Test
    void integratesKnowledgeAndSotExtensionPortsIntoBlockDAndBlockE() {
        stubBaseScope(false, false);
        UUID kbItemId = UUID.randomUUID();
        UUID sotId = UUID.randomUUID();

        when(knowledgeRetrievalPort.retrieve(any()))
                .thenReturn(
                        KnowledgeRetrievalResult.builder()
                                .items(
                                        List.of(
                                                KnowledgeCandidateDto.builder()
                                                        .knowledgeItemId(kbItemId)
                                                        .sourceType("CONDENSED_CONTEXT_NOTE")
                                                        .content("Project KB note")
                                                        .sourceRevision("2")
                                                        .linkStatus("AVAILABLE")
                                                        .relevanceScore(0.92)
                                                        .build()))
                                .retrievalStatus(RetrievalStatus.PARTIAL)
                                .evidenceSufficiency(EvidenceSufficiency.PARTIAL)
                                .degradedComponents(
                                        List.of(
                                                DegradedComponentDto.builder()
                                                        .component(
                                                                DegradedComponentType.VECTOR_SEARCH)
                                                        .status(DegradedComponentStatus.UNAVAILABLE)
                                                        .reasonCode("QUERY_EMBEDDING_UNAVAILABLE")
                                                        .build()))
                                .build());

        when(sourceOfTruthPort.retrieve(any()))
                .thenReturn(
                        SourceOfTruthResult.builder()
                                .records(
                                        List.of(
                                                SourceOfTruthRecordDto.builder()
                                                        .decisionId(sotId)
                                                        .title("DB Choice")
                                                        .scopeDescription("Storage")
                                                        .decisionContent("Use PostgreSQL 16")
                                                        .status("ACTIVE")
                                                        .sourceRevision("1")
                                                        .linkStatus("AVAILABLE")
                                                        .build()))
                                .build());

        CompiledContextResult result =
                service.compile(
                        ContextCompileRequest.builder()
                                .projectId(PROJECT_ID)
                                .conversationId(CONV_ID)
                                .mainStageId(STAGE_ID)
                                .currentUserContentBlocks(
                                        List.of(ContentBlockDto.textBlock("What DB do we use?")))
                                .build());

        var envelope = result.getPromptEnvelope();
        assertEquals(1, envelope.getKnowledgeAndEvidence().getKnowledgeBase().size());
        assertEquals(
                "PROJECT_KB",
                envelope.getKnowledgeAndEvidence().getKnowledgeBase().getFirst().getAuthority());
        assertEquals(1, envelope.getKnowledgeAndEvidence().getSourceOfTruth().size());
        assertEquals(
                "SOURCE_OF_TRUTH",
                envelope.getKnowledgeAndEvidence().getSourceOfTruth().getFirst().getAuthority());
        assertEquals(
                RetrievalStatus.PARTIAL, envelope.getResponseInstructions().getRetrievalStatus());
        assertEquals(
                EvidenceSufficiency.PARTIAL,
                envelope.getResponseInstructions().getEvidenceSufficiency());
        assertEquals(1, envelope.getResponseInstructions().getDegradedComponents().size());
        assertEquals(List.of(kbItemId), result.getKnowledgeItemIds());
        assertEquals(List.of(sotId), result.getSotDecisionIds());
    }

    @Test
    void compileRewritesDeduplicatedQuoteAliasesInCurrentUserRequest() {
        stubBaseScope(false, false);
        UUID sourceTurnId = UUID.randomUUID();
        List<ContentBlockDto> inputBlocks =
                List.of(
                        ContentBlockDto.textBlock("Compare "),
                        ContentBlockDto.quoteRefBlock("q1"),
                        ContentBlockDto.textBlock(" and "),
                        ContentBlockDto.quoteRefBlock("q2"));

        CompiledContextResult result =
                service.compile(
                        ContextCompileRequest.builder()
                                .projectId(PROJECT_ID)
                                .conversationId(CONV_ID)
                                .mainStageId(STAGE_ID)
                                .currentUserContentBlocks(inputBlocks)
                                .explicitReferences(
                                        List.of(
                                                ExplicitReferenceInputDto.builder()
                                                        .kind("QUOTE")
                                                        .localId("q1")
                                                        .sourceType("MAIN_TURN")
                                                        .sourceId(sourceTurnId)
                                                        .snapshotText("Same excerpt")
                                                        .sourceRevision("1")
                                                        .authority("EXPLICIT_REFERENCE")
                                                        .build(),
                                                ExplicitReferenceInputDto.builder()
                                                        .kind("QUOTE")
                                                        .localId("q2")
                                                        .sourceType("MAIN_TURN")
                                                        .sourceId(sourceTurnId)
                                                        .snapshotText("  same   excerpt ")
                                                        .sourceRevision("1")
                                                        .authority("EXPLICIT_REFERENCE")
                                                        .build()))
                                .build());

        var envelope = result.getPromptEnvelope();
        assertEquals(1, envelope.getKnowledgeAndEvidence().getExplicitReferences().size());
        assertEquals(
                "q1",
                envelope.getKnowledgeAndEvidence().getExplicitReferences().getFirst().getId());
        List<String> quoteRefIds =
                envelope.getCurrentUserRequest().getContentBlocks().stream()
                        .filter(b -> "QUOTE_REF".equals(b.getType()))
                        .map(ContentBlockDto::getQuoteRefId)
                        .toList();
        assertEquals(List.of("q1", "q1"), quoteRefIds);
    }

    @Test
    void compileEnforcesFourTierDedupAnd32KBudgetPreservingProtectedBlocks() {
        stubBaseScope(false, true);
        UUID sharedTurnId = UUID.randomUUID();
        when(contextSourceRepository.findEnabledProjectRules(PROJECT_ID))
                .thenReturn(
                        List.of(
                                RuleRecord.builder()
                                        .id(UUID.randomUUID())
                                        .name("Protected Mandatory")
                                        .content("MANDATORY_RULE_CONTENT")
                                        .enforcementLevel(RuleEnforcementLevel.MANDATORY)
                                        .priority(1)
                                        .createdAt(Instant.parse("2026-11-26T00:00:00Z"))
                                        .build()));
        when(contextSourceRepository.findActiveWindowTurns(PROJECT_ID, CONV_ID, STAGE_ID, null, 8))
                .thenReturn(
                        List.of(
                                ActiveWindowTurnRecord.builder()
                                        .turnId(sharedTurnId)
                                        .sequenceNo(10)
                                        .ordinal(1)
                                        .sourceRevision(1)
                                        .rawUserContentBlocks(
                                                List.of(ContentBlockDto.textBlock("Active prompt")))
                                        .nativeOutputSteps(List.of())
                                        .build()));
        when(contextSourceRepository.findNonActiveHistoryTurns(any(), isNull(), anySet()))
                .thenReturn(
                        List.of(
                                com.contextflow.context.repository.HistoryTurnRecord.builder()
                                        .turnId(UUID.randomUUID())
                                        .mainStageId(STAGE_ID)
                                        .stageOrder(1)
                                        .sequenceNo(1)
                                        .sourceRevision(1)
                                        .userText("h".repeat(80_000))
                                        .assistantText("a".repeat(80_000))
                                        .createdAt(Instant.parse("2026-11-26T00:00:00Z"))
                                        .build()));
        when(knowledgeRetrievalPort.retrieve(any()))
                .thenReturn(
                        KnowledgeRetrievalResult.builder()
                                .items(
                                        List.of(
                                                KnowledgeCandidateDto.builder()
                                                        .knowledgeItemId(UUID.randomUUID())
                                                        .sourceTurnId(sharedTurnId)
                                                        .itemType("MAIN_TURN")
                                                        .sourceType("MAIN_TURN")
                                                        .content("Duplicate active projection")
                                                        .relevanceScore(0.99)
                                                        .build(),
                                                KnowledgeCandidateDto.builder()
                                                        .knowledgeItemId(UUID.randomUUID())
                                                        .itemType("CONDENSED_CONTEXT_NOTE")
                                                        .sourceType("CONDENSED_CONTEXT_NOTE")
                                                        .content("k".repeat(40_000))
                                                        .relevanceScore(0.4)
                                                        .build()))
                                .build());

        CompiledContextResult result =
                service.compile(
                        ContextCompileRequest.builder()
                                .projectId(PROJECT_ID)
                                .conversationId(CONV_ID)
                                .mainStageId(STAGE_ID)
                                .currentUserContentBlocks(
                                        List.of(ContentBlockDto.textBlock("USER_REQUEST_STAY")))
                                .build());

        assertTrue(result.getEstimatedTokensBeforeTruncation() > 32_000);
        assertTrue(result.getEstimatedInputTokens() <= 32_000);
        assertTrue(result.isHistoryTruncated());
        assertTrue(result.isRolloverSuggested());
        assertEquals(1, result.getMandatoryRulesCount());
        assertEquals(
                "USER_REQUEST_STAY",
                result.getPromptEnvelope()
                        .getCurrentUserRequest()
                        .getContentBlocks()
                        .getFirst()
                        .getText());
        assertEquals(
                "MANDATORY_RULE_CONTENT",
                result.getPromptEnvelope()
                        .getIntelligenceContext()
                        .getInstructions()
                        .getMandatoryRules()
                        .getFirst()
                        .getContent());
    }

    private void stubBaseScope(boolean personalEnabled, boolean projectEnabled) {
        lenient().when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
        lenient()
                .when(contextSourceRepository.findProjectScope(PROJECT_ID))
                .thenReturn(
                        Optional.of(
                                ProjectScopeRecord.builder()
                                        .id(PROJECT_ID)
                                        .ownerUserId(USER_ID)
                                        .projectInformation("Project background")
                                        .projectIntelligenceEnabled(projectEnabled)
                                        .status("ACTIVE")
                                        .build()));
        lenient()
                .when(contextSourceRepository.findStageScope(PROJECT_ID, CONV_ID, STAGE_ID, null))
                .thenReturn(
                        Optional.of(
                                StageScopeRecord.builder()
                                        .id(STAGE_ID)
                                        .projectId(PROJECT_ID)
                                        .conversationId(CONV_ID)
                                        .stageOrder(1)
                                        .title("Main 1")
                                        .stageStatus("ACTIVE")
                                        .conversationStatus("ACTIVE")
                                        .build()));
        lenient()
                .when(contextSourceRepository.findUserIntelligence(USER_ID))
                .thenReturn(
                        Optional.of(
                                UserIntelligenceRecord.builder()
                                        .userId(USER_ID)
                                        .personalIntelligenceEnabled(personalEnabled)
                                        .personalProfileText("Personal profile")
                                        .personalInstructions("Personal instructions")
                                        .build()));
        lenient()
                .when(contextSourceRepository.findEnabledPersonalSkills(USER_ID))
                .thenReturn(List.of());
        lenient()
                .when(contextSourceRepository.findEnabledProjectRules(PROJECT_ID))
                .thenReturn(List.of());
        lenient()
                .when(contextSourceRepository.findEnabledProjectSkills(PROJECT_ID))
                .thenReturn(List.of());
        lenient()
                .when(
                        contextSourceRepository.findActiveWindowTurns(
                                eq(PROJECT_ID), eq(CONV_ID), eq(STAGE_ID), isNull(), anyInt()))
                .thenReturn(List.of());
        lenient()
                .when(contextSourceRepository.findNonActiveHistoryTurns(any(), isNull(), anySet()))
                .thenReturn(List.of());
        lenient()
                .when(contextSourceRepository.findActiveCredentialTiers(USER_ID))
                .thenReturn(
                        CredentialTierSnapshotRecord.builder()
                                .userTier(PrivacyTier.FREE)
                                .platformTier(PrivacyTier.FREE)
                                .build());
        lenient()
                .when(contextSourceRepository.findConfiguredSoftCapTokens())
                .thenReturn(Optional.empty());
        lenient()
                .when(knowledgeRetrievalPort.retrieve(any()))
                .thenReturn(KnowledgeRetrievalResult.empty());
        lenient().when(sourceOfTruthPort.retrieve(any())).thenReturn(SourceOfTruthResult.empty());
    }
}
