package com.contextflow.conversation.controller;

import com.contextflow.conversation.dto.ConversationCreateRequest;
import com.contextflow.conversation.dto.ConversationOutlineResponse;
import com.contextflow.conversation.dto.ConversationPatchRequest;
import com.contextflow.conversation.dto.ConversationResponse;
import com.contextflow.conversation.dto.ConversationStatusFilter;
import com.contextflow.conversation.dto.ConversationsPageResponse;
import com.contextflow.conversation.dto.TurnsPageResponse;
import com.contextflow.conversation.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@RequiredArgsConstructor
@Tag(name = "Conversations", description = "Conversation CRUD, Main Stage turns and outline")
public class ConversationController {
    private final ConversationService conversationService;

    @GetMapping("/conversations")
    @Operation(summary = "List conversations of a project (DELETING hidden)")
    public ConversationsPageResponse listConversations(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) ConversationStatusFilter status) {
        return conversationService.listConversations(projectId, status, page, size);
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a conversation with Main 1 and its initial continuity epoch")
    public ConversationResponse createConversation(
            @PathVariable UUID projectId, @Valid @RequestBody ConversationCreateRequest request) {
        return conversationService.createConversation(projectId, request);
    }

    @PatchMapping("/conversations/{conversationId}")
    @Operation(summary = "Rename an ACTIVE conversation")
    public ConversationResponse updateConversation(
            @PathVariable UUID projectId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody ConversationPatchRequest request) {
        return conversationService.updateConversation(projectId, conversationId, request);
    }

    @GetMapping("/conversations/{conversationId}/outline")
    @Operation(summary = "Outline of a conversation: Main Stages by stage order")
    public ConversationOutlineResponse getConversationOutline(
            @PathVariable UUID projectId, @PathVariable UUID conversationId) {
        return conversationService.getConversationOutline(projectId, conversationId);
    }

    @GetMapping("/stages/{stageId}/turns")
    @Operation(summary = "Committed Main-route turns of a Main Stage, ordered by sequence")
    public TurnsPageResponse listStageTurns(
            @PathVariable UUID projectId,
            @PathVariable UUID stageId,
            @Parameter(description = "Return turns with sequenceNo greater than this value")
                    @RequestParam(required = false)
                    @PositiveOrZero
                    Long afterSequence,
            @Parameter(description = "Return turns with sequenceNo lower than this value")
                    @RequestParam(required = false)
                    @PositiveOrZero
                    Long beforeSequence,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return conversationService.listMainStageTurns(
                projectId, stageId, afterSequence, beforeSequence, limit);
    }
}
