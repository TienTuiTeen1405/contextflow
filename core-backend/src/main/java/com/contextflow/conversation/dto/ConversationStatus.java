package com.contextflow.conversation.dto;

/** Lifecycle states stored in {@code contextflow.conversations.status}. */
public enum ConversationStatus {
    ACTIVE,
    ARCHIVED,
    LOCKED_READONLY,
    DELETING
}
