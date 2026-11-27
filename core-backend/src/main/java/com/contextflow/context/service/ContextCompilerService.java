package com.contextflow.context.service;

import com.contextflow.context.dto.CompiledContextResult;
import com.contextflow.context.dto.ContextCompileRequest;
import java.util.UUID;

/**
 * Compiles 3-layer context, continuity active window, and Block D sources into a PromptEnvelope.
 */
public interface ContextCompilerService {
    CompiledContextResult compile(ContextCompileRequest request);

    CompiledContextResult compileForStageIndicator(
            UUID projectId, UUID conversationId, UUID stageId, UUID subBranchId);
}
