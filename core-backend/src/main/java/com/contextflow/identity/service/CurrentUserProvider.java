package com.contextflow.identity.service;

import java.util.UUID;

/** Single seam for resolving the authenticated active user ID across all domain services. */
public interface CurrentUserProvider {
    UUID getCurrentUserId();
}
