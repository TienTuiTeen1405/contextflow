package com.contextflow.conversation.service.impl;

import com.contextflow.ai.service.ContinuityEpochBootstrap;
import com.contextflow.common.api.ApiProblem;
import com.contextflow.common.api.ErrorCode;
import com.contextflow.conversation.dto.ConversationCreateRequest;
import com.contextflow.conversation.dto.ConversationPatchRequest;
import com.contextflow.conversation.dto.ConversationResponse;
import com.contextflow.conversation.dto.ConversationStatus;
import com.contextflow.conversation.dto.ConversationStatusFilter;
import com.contextflow.conversation.dto.ConversationsPageResponse;
import com.contextflow.conversation.dto.MainStageStatus;
import com.contextflow.conversation.entity.Conversation;
import com.contextflow.conversation.entity.MainStage;
import com.contextflow.conversation.mapper.ConversationMapper;
import com.contextflow.conversation.repository.ConversationRepository;
import com.contextflow.conversation.repository.MainStageRepository;
import com.contextflow.conversation.repository.ProjectAccessRecord;
import com.contextflow.conversation.repository.ProjectAccessRepository;
import com.contextflow.conversation.service.ConversationService;
import com.contextflow.identity.service.CurrentUserProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {
    static final String FIRST_MAIN_STAGE_TITLE = "Main 1";
    static final int FIRST_MAIN_STAGE_ORDER = 1;

    /** DELETING conversations are hidden from every read. */
    private static final Set<ConversationStatus> LISTED_STATUSES =
            EnumSet.of(
                    ConversationStatus.ACTIVE,
                    ConversationStatus.ARCHIVED,
                    ConversationStatus.LOCKED_READONLY);

    private static final Sort CONVERSATION_ORDER =
            Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id"));

    private final CurrentUserProvider currentUserProvider;
    private final ProjectAccessRepository projectAccessRepository;
    private final ConversationRepository conversationRepository;
    private final MainStageRepository mainStageRepository;
    private final ContinuityEpochBootstrap continuityEpochBootstrap;
    private final ConversationMapper conversationMapper;

    @Override
    @Transactional(readOnly = true)
    public ConversationsPageResponse listConversations(
            UUID projectId, ConversationStatusFilter status, int page, int size) {
        if ((long) page * size > Integer.MAX_VALUE) {
            throw ApiProblem.invalid("page is out of range.");
        }
        UUID userId = currentUserProvider.getCurrentUserId();
        requireAccessibleProject(userId, projectId, false);

        Collection<ConversationStatus> statuses =
                status == null ? LISTED_STATUSES : List.of(status.toConversationStatus());
        Page<Conversation> result =
                conversationRepository.findByProjectIdAndStatusIn(
                        projectId, statuses, PageRequest.of(page, size, CONVERSATION_ORDER));
        Map<UUID, UUID> currentMainStageIds =
                findCurrentMainStageIds(projectId, result.getContent());
        List<ConversationResponse> items =
                result.getContent().stream()
                        .map(conversation ->
                                conversationMapper.toResponse(
                                        conversation,
                                        currentMainStageIds.get(conversation.getId())))
                        .toList();
        return ConversationsPageResponse.builder()
                .items(items)
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .hasNext(result.hasNext())
                .build();
    }

    @Override
    @Transactional
    public ConversationResponse createConversation(
            UUID projectId, ConversationCreateRequest request) {
        UUID userId = currentUserProvider.getCurrentUserId();
        requireAccessibleProject(userId, projectId, true);

        Instant now = now();
        Conversation conversation =
                conversationRepository.saveAndFlush(
                        Conversation.builder()
                                .id(UUID.randomUUID())
                                .projectId(projectId)
                                .title(request.getTitle())
                                .status(ConversationStatus.ACTIVE)
                                .createdAt(now)
                                .updatedAt(now)
                                .build());
        MainStage mainStage =
                mainStageRepository.saveAndFlush(
                        MainStage.builder()
                                .id(UUID.randomUUID())
                                .projectId(projectId)
                                .conversationId(conversation.getId())
                                .stageOrder(FIRST_MAIN_STAGE_ORDER)
                                .title(FIRST_MAIN_STAGE_TITLE)
                                .status(MainStageStatus.ACTIVE)
                                .createdAt(now)
                                .build());
        // The epoch is inserted through JDBC in this same transaction; both parent rows were
        // flushed above so its foreign keys resolve. Any failure rolls back all three rows.
        continuityEpochBootstrap.initializeForMainStage(
                projectId, conversation.getId(), mainStage.getId());
        return conversationMapper.toResponse(conversation, mainStage.getId());
    }

    @Override
    @Transactional
    public ConversationResponse updateConversation(
            UUID projectId, UUID conversationId, ConversationPatchRequest request) {
        UUID userId = currentUserProvider.getCurrentUserId();
        requireAccessibleProject(userId, projectId, true);

        Conversation conversation =
                conversationRepository
                        .findByIdAndProjectIdForUpdate(conversationId, projectId)
                        .filter(found -> found.getStatus() != ConversationStatus.DELETING)
                        .orElseThrow(ApiProblem::missing);
        if (conversation.getStatus() != ConversationStatus.ACTIVE) {
            throw ApiProblem.invalid("Only ACTIVE conversations can be renamed.");
        }
        conversation.setTitle(request.getTitle());
        conversation.setUpdatedAt(now());

        UUID mainStageId =
                mainStageRepository
                        .findFirstByProjectIdAndConversationIdOrderByStageOrderDesc(
                                projectId, conversationId)
                        .map(MainStage::getId)
                        .orElse(null);
        return conversationMapper.toResponse(conversation, mainStageId);
    }

    /**
     * Missing, foreign and DELETED Projects are all reported as 404 so their existence is not
     * leaked; a DELETING Project is reported as 409. Writes lock the Project row in share mode.
     */
    private void requireAccessibleProject(UUID userId, UUID projectId, boolean forWrite) {
        ProjectAccessRecord project =
                (forWrite
                                ? projectAccessRepository.findByIdForShare(projectId)
                                : projectAccessRepository.findById(projectId))
                        .filter(found -> found.isVisibleTo(userId))
                        .orElseThrow(ApiProblem::missing);
        if (project.isDeleting()) {
            throw new ApiProblem(ErrorCode.PROJECT_DELETING, "Project is being deleted.");
        }
    }

    private Map<UUID, UUID> findCurrentMainStageIds(
            UUID projectId, List<Conversation> conversations) {
        if (conversations.isEmpty()) {
            return Map.of();
        }
        List<UUID> conversationIds = conversations.stream().map(Conversation::getId).toList();
        return mainStageRepository
                .findLatestByProjectIdAndConversationIdIn(projectId, conversationIds)
                .stream()
                .collect(Collectors.toMap(MainStage::getConversationId, MainStage::getId));
    }

    /** PostgreSQL keeps microseconds; truncating keeps responses equal to the stored values. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
