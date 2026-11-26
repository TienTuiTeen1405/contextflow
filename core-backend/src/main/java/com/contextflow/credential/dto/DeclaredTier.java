package com.contextflow.credential.dto;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum DeclaredTier {
    FREE,
    PAID,
    UNKNOWN;

    @JsonCreator
    public static DeclaredTier fromWire(String value) {
        return valueOf(value);
    }

    public boolean requiresDisclosure() {
        return this == FREE || this == UNKNOWN;
    }
}
