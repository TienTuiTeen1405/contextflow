package com.contextflow.conversation.repository;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Minimal read model of {@code contextflow.projects} used to scope conversation access. */
@Getter
@AllArgsConstructor
public class ProjectAccessRecord {
    private static final String STATUS_DELETING = "DELETING";
    private static final String STATUS_DELETED = "DELETED";

    private final UUID id;
    private final UUID ownerUserId;
    private final String status;

    /** Projects of other users and DELETED projects are reported as missing (404). */
    public boolean isVisibleTo(UUID userId) {
        return ownerUserId.equals(userId) && !STATUS_DELETED.equals(status);
    }

    public boolean isDeleting() {
        return STATUS_DELETING.equals(status);
    }
}
