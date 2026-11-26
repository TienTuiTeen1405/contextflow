package com.contextflow.knowledge.service;

import java.util.UUID;

/** Initializes the active embedding index generation when a new Project is created. */
public interface IndexGenerationBootstrap {
    UUID initializeForProject(UUID projectId);
}
