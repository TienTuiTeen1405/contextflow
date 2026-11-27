package com.contextflow.context.controller;

import com.contextflow.context.dto.CompiledContextResult;
import com.contextflow.context.dto.ContextIndicatorResponse;
import com.contextflow.context.mapper.ContextIndicatorMapper;
import com.contextflow.context.service.ContextCompilerService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exposes real-time compiled context budget, toggle, rule/skill, and privacy tier metrics. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@RequiredArgsConstructor
public class ContextIndicatorController {
    private final ContextCompilerService contextCompilerService;
    private final ContextIndicatorMapper contextIndicatorMapper;

    @GetMapping("/stages/{stageId}/context-indicator")
    public ContextIndicatorResponse getStageContextIndicator(
            @PathVariable UUID projectId,
            @PathVariable UUID stageId,
            @RequestParam(required = false) UUID subBranchId) {
        CompiledContextResult compiled =
                contextCompilerService.compileForStageIndicator(
                        projectId, null, stageId, subBranchId);
        return contextIndicatorMapper.toResponse(compiled);
    }

    @GetMapping("/conversations/{convId}/stages/{stageId}/context-indicator")
    public ContextIndicatorResponse getConversationStageContextIndicator(
            @PathVariable UUID projectId,
            @PathVariable UUID convId,
            @PathVariable UUID stageId,
            @RequestParam(required = false) UUID subBranchId) {
        CompiledContextResult compiled =
                contextCompilerService.compileForStageIndicator(
                        projectId, convId, stageId, subBranchId);
        return contextIndicatorMapper.toResponse(compiled);
    }
}
