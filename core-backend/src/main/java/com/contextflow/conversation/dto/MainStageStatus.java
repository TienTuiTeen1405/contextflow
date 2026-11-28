package com.contextflow.conversation.dto;

/** Lifecycle states stored in {@code contextflow.main_stages.status}. */
public enum MainStageStatus {
    ACTIVE,
    LOCKED_READONLY,
    DELETION_PREPARING,
    DELETING
}
