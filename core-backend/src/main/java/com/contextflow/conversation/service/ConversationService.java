package com.contextflow.conversation.service;

import com.contextflow.conversation.dto.ConversationCreateRequest;
import com.contextflow.conversation.dto.ConversationOutlineResponse;
import com.contextflow.conversation.dto.ConversationPatchRequest;
import com.contextflow.conversation.dto.ConversationResponse;
import com.contextflow.conversation.dto.ConversationStatusFilter;
import com.contextflow.conversation.dto.ConversationsPageResponse;
import com.contextflow.conversation.dto.TurnsPageResponse;
import java.util.UUID;

/** Conversation use cases, always scoped to the current user's Project. */
public interface ConversationService {
    ConversationsPageResponse listConversations(
            UUID projectId, ConversationStatusFilter status, int page, int size);

    /** Creates the Conversation, its {@code Main 1} and the initial continuity epoch atomically. */
    ConversationResponse createConversation(UUID projectId, ConversationCreateRequest request);

    ConversationResponse updateConversation(
            UUID projectId, UUID conversationId, ConversationPatchRequest request);

    /**
     * Reads committed Main-route Turns of a Main Stage in {@code sequence_no} order. At most one of
     * {@code afterSequence} and {@code beforeSequence} may be given.
     */
    TurnsPageResponse listMainStageTurns(
            UUID projectId, UUID stageId, Long afterSequence, Long beforeSequence, int limit);

    ConversationOutlineResponse getConversationOutline(UUID projectId, UUID conversationId);
}
