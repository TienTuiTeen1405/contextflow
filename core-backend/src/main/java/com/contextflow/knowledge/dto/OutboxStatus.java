package com.contextflow.knowledge.dto;

public enum OutboxStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    CONFIGURATION_BLOCKED,
    HARD_QUOTA_BLOCKED,
    FAILED,
    CANCELLED
}
