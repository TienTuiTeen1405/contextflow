package com.contextflow.conversation.service;

import com.contextflow.conversation.dto.ConversationCreateRequest;
import com.contextflow.conversation.dto.ConversationPatchRequest;
import com.contextflow.conversation.dto.ConversationResponse;
import com.contextflow.conversation.dto.ConversationStatusFilter;
import com.contextflow.conversation.dto.ConversationsPageResponse;
import java.util.UUID;

/** Conversation use cases, always scoped to the current user's Project. */
public interface ConversationService {
    ConversationsPageResponse listConversations(
            UUID projectId, ConversationStatusFilter status, int page, int size);

    /** Creates the Conversation, its {@code Main 1} and the initial continuity epoch atomically. */
    ConversationResponse createConversation(UUID projectId, ConversationCreateRequest request);

    ConversationResponse updateConversation(
            UUID projectId, UUID conversationId, ConversationPatchRequest request);
}
