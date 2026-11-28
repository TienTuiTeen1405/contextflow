package com.contextflow.conversation.dto;

/**
 * Values accepted by the {@code status} filter of the conversation list (contract enum {@code
 * ProjectWritableStatus}). {@code DELETING} conversations are never listed.
 */
public enum ConversationStatusFilter {
    ACTIVE(ConversationStatus.ACTIVE),
    ARCHIVED(ConversationStatus.ARCHIVED);

    private final ConversationStatus status;

    ConversationStatusFilter(ConversationStatus status) {
        this.status = status;
    }

    public ConversationStatus toConversationStatus() {
        return status;
    }
}
