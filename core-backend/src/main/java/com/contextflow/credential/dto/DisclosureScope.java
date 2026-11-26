package com.contextflow.credential.dto;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum DisclosureScope {
    CREDENTIAL_SETUP,
    WORKSPACE_OPERATION;

    @JsonCreator
    public static DisclosureScope fromWire(String value) {
        return valueOf(value);
    }
}
