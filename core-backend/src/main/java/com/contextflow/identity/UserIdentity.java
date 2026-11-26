package com.contextflow.identity;

import java.security.Principal;
import java.util.UUID;

/** Future JWT principal can implement this identity seam without changing scoped repositories. */
public interface UserIdentity extends Principal {
    UUID userId();

    default String getName() {
        return userId().toString();
    }
}
