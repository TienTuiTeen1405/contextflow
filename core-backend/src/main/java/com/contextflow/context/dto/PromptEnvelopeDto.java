package com.contextflow.context.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptEnvelopeDto {
    @JsonProperty("platform_policy")
    private String platformPolicy;

    @JsonProperty("current_user_request")
    private CurrentUserRequestDto currentUserRequest;

    @JsonProperty("intelligence_context")
    private IntelligenceContextDto intelligenceContext;

    @JsonProperty("knowledge_and_evidence")
    private KnowledgeAndEvidenceDto knowledgeAndEvidence;

    @JsonProperty("response_instructions")
    private ResponseInstructionsDto responseInstructions;

    @Builder.Default private List<ContinuityTurnDto> continuity = new ArrayList<>();
}
