package com.contextflow.identity;

import java.security.Principal;
import java.util.UUID;

/** Future JWT principal implements this seam without changing scoped domain services. */
public interface UserIdentity extends Principal {
    UUID getUserId();

    @Override
    default String getName() {
        return getUserId().toString();
    }
}
