package com.contextflow.conversation;

import com.contextflow.common.api.RequestFields;
import com.contextflow.contracts.generated.PublicApi.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("demo")
@Validated
@RequestMapping("/api/v1/projects/{projectId}/conversations")
public class ConversationController {
    private final ConversationService conversations;
    private final ObjectMapper mapper;
    private final Validator validator;

    public ConversationController(
            ConversationService conversations, ObjectMapper mapper, Validator validator) {
        this.conversations = conversations;
        this.mapper = mapper;
        this.validator = validator;
    }

    @GetMapping
    public ConversationsPage list(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) ProjectWritableStatus status) {
        return conversations.list(projectId, page, size, status);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView create(
            @PathVariable UUID projectId, @Valid @RequestBody TitleCreateInput input) {
        return conversations.create(projectId, input);
    }

    @PatchMapping("/{conversationId}")
    public ConversationView patch(
            @PathVariable UUID projectId,
            @PathVariable UUID conversationId,
            @RequestBody ObjectNode body) {
        return conversations.patch(
                projectId,
                conversationId,
                RequestFields.read(body, ConversationPatchInput.class, mapper, validator));
    }
}
