// Generated from contracts/*.openapi.json. Do not edit.
package com.contextflow.contracts.generated;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Generated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.contextflow.contracts.CodePointSize;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Generated("scripts/generate-contracts.mjs")
public final class InternalAi {
    private InternalAi() {}

    public record ContinuityTurn(
        @JsonProperty(value = "turn_ref", required = true) @NotNull @CodePointSize(min = 1, max = 2147483647)
        String turnRef,
        @JsonProperty(value = "user_text_blocks", required = true) @NotNull @Valid
        List<String> userTextBlocks,
        @JsonProperty(value = "historical_quotes", required = true) @NotNull @Valid
        List<HistoricalQuote> historicalQuotes,
        @JsonProperty(value = "native_output_steps", required = true) @NotNull @Valid
        List<JsonNode> nativeOutputSteps,
        @JsonProperty(value = "user_content_blocks", required = false) @JsonInclude(JsonInclude.Include.NON_NULL) @Valid
        List<JsonNode> userContentBlocks
    ) {}

    public record CurrentUserRequest(
        @JsonProperty(value = "content_blocks", required = true) @NotNull @Valid
        List<JsonNode> contentBlocks
    ) {}

    public record DegradedComponent(
        @JsonProperty(value = "component", required = true) @NotNull
        DegradedComponentComponent component,
        @JsonProperty(value = "status", required = true) @NotNull
        DegradedComponentStatus status,
        @JsonProperty(value = "reason_code", required = true) @NotNull
        String reasonCode
    ) {}

    public record EmbeddingInput(
        @JsonProperty(value = "correlation_id", required = true) @NotNull
        UUID correlationId,
        @JsonProperty(value = "provider_call_id", required = true) @NotNull
        UUID providerCallId,
        @JsonProperty(value = "task_type", required = true) @NotNull
        EmbeddingInputTaskType taskType,
        @JsonProperty(value = "model", required = true) @NotNull
        String model,
        @JsonProperty(value = "output_dimensionality", required = true) @NotNull @Min(1536) @Max(1536)
        Long outputDimensionality,
        @JsonProperty(value = "texts", required = true) @NotNull @Size(min = 1, max = 10) @Valid
        List<String> texts
    ) {}

    public record EmbeddingResult(
        @JsonProperty(value = "correlation_id", required = true) @NotNull
        UUID correlationId,
        @JsonProperty(value = "provider_call_id", required = true) @NotNull
        UUID providerCallId,
        @JsonProperty(value = "dispatched", required = true) @NotNull
        Boolean dispatched,
        @JsonProperty(value = "occurred_at", required = true)
        Instant occurredAt,
        @JsonProperty(value = "completed_at", required = true) @NotNull
        Instant completedAt,
        @JsonProperty(value = "model", required = true) @NotNull
        String model,
        @JsonProperty(value = "provider_request_id", required = true)
        String providerRequestId,
        @JsonProperty(value = "vectors", required = true) @NotNull @Valid
        List<List<Double>> vectors,
        @JsonProperty(value = "usage_status", required = true) @NotNull
        EmbeddingResultUsageStatus usageStatus,
        @JsonProperty(value = "usage", required = true) @NotNull @Valid
        Usage usage
    ) {}

    public record ExplicitReference(
        @JsonProperty(value = "kind", required = true) @NotNull
        ExplicitReferenceKind kind,
        @JsonProperty(value = "id", required = true) @NotNull @CodePointSize(min = 1, max = 2147483647)
        String id,
        @JsonProperty(value = "source_type", required = true) @NotNull
        String sourceType,
        @JsonProperty(value = "snapshot_text", required = true) @NotNull
        String snapshotText,
        @JsonProperty(value = "source_revision", required = false) @JsonInclude(JsonInclude.Include.NON_NULL)
        String sourceRevision,
        @JsonProperty(value = "authority", required = true) @NotNull
        String authority
    ) {}

    public record GenerateInput(
        @JsonProperty(value = "correlation_id", required = true) @NotNull
        UUID correlationId,
        @JsonProperty(value = "provider_call_id", required = true) @NotNull
        UUID providerCallId,
        @JsonProperty(value = "task_type", required = true) @NotNull
        GenerateInputTaskType taskType,
        @JsonProperty(value = "provider_options", required = true) @NotNull @Valid
        GenerateOptions providerOptions,
        @JsonProperty(value = "prompt", required = true) @NotNull @Valid
        PromptEnvelope prompt,
        @JsonProperty(value = "response_schema", required = false) @JsonInclude(JsonInclude.Include.NON_NULL)
        JsonNode responseSchema
    ) {}

    public record GenerateOptions(
        @JsonProperty(value = "model", required = true) @NotNull
        String model,
        @JsonProperty(value = "api_interface", required = true) @NotNull
        GenerateOptionsApiInterface apiInterface,
        @JsonProperty(value = "store", required = true) @NotNull @AssertFalse
        Boolean store,
        @JsonProperty(value = "stream", required = true) @NotNull @AssertFalse
        Boolean stream,
        @JsonProperty(value = "timeout_seconds", required = true) @NotNull @Min(1)
        Long timeoutSeconds,
        @JsonProperty(value = "max_output_tokens", required = false) @JsonInclude(JsonInclude.Include.NON_NULL) @Min(1)
        Long maxOutputTokens
    ) {}

    public record GenerateResult(
        @JsonProperty(value = "correlation_id", required = true) @NotNull
        UUID correlationId,
        @JsonProperty(value = "provider_call_id", required = true) @NotNull
        UUID providerCallId,
        @JsonProperty(value = "dispatched", required = true) @NotNull
        Boolean dispatched,
        @JsonProperty(value = "occurred_at", required = true)
        Instant occurredAt,
        @JsonProperty(value = "completed_at", required = true) @NotNull
        Instant completedAt,
        @JsonProperty(value = "model", required = true) @NotNull
        String model,
        @JsonProperty(value = "provider_request_id", required = true)
        String providerRequestId,
        @JsonProperty(value = "output_text", required = true) @NotNull
        String outputText,
        @JsonProperty(value = "parsed_json", required = true)
        JsonNode parsedJson,
        @JsonProperty(value = "native_output_steps", required = true) @NotNull @Valid
        List<JsonNode> nativeOutputSteps,
        @JsonProperty(value = "finish_reason", required = true)
        String finishReason,
        @JsonProperty(value = "service_tier", required = true)
        String serviceTier,
        @JsonProperty(value = "usage_status", required = true) @NotNull
        GenerateResultUsageStatus usageStatus,
        @JsonProperty(value = "usage", required = true) @NotNull @Valid
        Usage usage
    ) {}

    public record HistoricalQuote(
        @JsonProperty(value = "quote_ref_id", required = true) @NotNull @CodePointSize(min = 1, max = 2147483647)
        String quoteRefId,
        @JsonProperty(value = "snapshot_text", required = true) @NotNull
        String snapshotText,
        @JsonProperty(value = "source_type", required = true) @NotNull
        String sourceType
    ) {}

    public record Instruction(
        @JsonProperty(value = "id", required = true) @NotNull @CodePointSize(min = 1, max = 2147483647)
        String id,
        @JsonProperty(value = "priority", required = true) @NotNull @Min(0)
        Long priority,
        @JsonProperty(value = "content", required = true) @NotNull
        String content
    ) {}

    public record IntelligenceBackground(
        @JsonProperty(value = "project_information", required = true)
        String projectInformation,
        @JsonProperty(value = "personal_profile", required = true)
        String personalProfile
    ) {}

    public record IntelligenceContext(
        @JsonProperty(value = "background", required = true) @NotNull @Valid
        IntelligenceBackground background,
        @JsonProperty(value = "instructions", required = true) @NotNull @Valid
        IntelligenceInstructions instructions
    ) {}

    public record IntelligenceInstructions(
        @JsonProperty(value = "mandatory_rules", required = true) @NotNull @Valid
        List<Instruction> mandatoryRules,
        @JsonProperty(value = "overridable_rules", required = true) @NotNull @Valid
        List<Instruction> overridableRules,
        @JsonProperty(value = "active_skills", required = true) @NotNull @Valid
        List<Instruction> activeSkills,
        @JsonProperty(value = "personal_instructions", required = true)
        String personalInstructions
    ) {}

    public record IntentContext(
        @JsonProperty(value = "primary_intent", required = true) @NotNull
        String primaryIntent,
        @JsonProperty(value = "time_scope", required = true) @NotNull
        IntentContextTimeScope timeScope,
        @JsonProperty(value = "resolved_entities", required = false) @JsonInclude(JsonInclude.Include.NON_NULL) @Valid
        List<String> resolvedEntities,
        @JsonProperty(value = "exact_lookup", required = false) @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean exactLookup
    ) {}

    public record InternalError(
        @JsonProperty(value = "error", required = true) @NotNull @Valid
        ProviderFailure error
    ) {}

    public record KnowledgeAndEvidence(
        @JsonProperty(value = "history", required = true) @NotNull @Valid
        List<Source> history,
        @JsonProperty(value = "knowledge_base", required = true) @NotNull @Valid
        List<Source> knowledgeBase,
        @JsonProperty(value = "source_of_truth", required = true) @NotNull @Valid
        List<Source> sourceOfTruth,
        @JsonProperty(value = "explicit_references", required = true) @NotNull @Valid
        List<ExplicitReference> explicitReferences
    ) {}

    public record PromptEnvelope(
        @JsonProperty(value = "platform_policy", required = true) @NotNull
        String platformPolicy,
        @JsonProperty(value = "current_user_request", required = true) @NotNull @Valid
        CurrentUserRequest currentUserRequest,
        @JsonProperty(value = "intelligence_context", required = true) @NotNull @Valid
        IntelligenceContext intelligenceContext,
        @JsonProperty(value = "knowledge_and_evidence", required = true) @NotNull @Valid
        KnowledgeAndEvidence knowledgeAndEvidence,
        @JsonProperty(value = "response_instructions", required = true) @NotNull @Valid
        ResponseInstructions responseInstructions,
        @JsonProperty(value = "continuity", required = true) @NotNull @Valid
        List<ContinuityTurn> continuity
    ) {}

    public record ProviderFailure(
        @JsonProperty(value = "correlation_id", required = true) @NotNull
        UUID correlationId,
        @JsonProperty(value = "provider_call_id", required = true) @NotNull
        UUID providerCallId,
        @JsonProperty(value = "dispatched", required = true) @NotNull
        Boolean dispatched,
        @JsonProperty(value = "occurred_at", required = true)
        Instant occurredAt,
        @JsonProperty(value = "completed_at", required = true) @NotNull
        Instant completedAt,
        @JsonProperty(value = "error_category", required = true) @NotNull
        ProviderFailureErrorCategory errorCategory,
        @JsonProperty(value = "error_code", required = true) @NotNull
        String errorCode,
        @JsonProperty(value = "retryable", required = true) @NotNull
        Boolean retryable,
        @JsonProperty(value = "retry_after_seconds", required = true) @Min(0)
        Long retryAfterSeconds,
        @JsonProperty(value = "usage_status", required = true) @NotNull
        ProviderFailureUsageStatus usageStatus,
        @JsonProperty(value = "usage", required = true) @NotNull @Valid
        Usage usage
    ) {}

    public record QuoteRefBlock(
        @JsonProperty(value = "type", required = true) @NotNull @Pattern(regexp = "^QUOTE_REF$")
        String type,
        @JsonProperty(value = "quote_ref_id", required = true) @NotNull @CodePointSize(min = 1, max = 2147483647)
        String quoteRefId
    ) {}

    public record ResponseInstructions(
        @JsonProperty(value = "intent_context", required = true) @NotNull @Valid
        IntentContext intentContext,
        @JsonProperty(value = "evidence_sufficiency", required = true) @NotNull
        ResponseInstructionsEvidenceSufficiency evidenceSufficiency,
        @JsonProperty(value = "retrieval_status", required = true) @NotNull
        ResponseInstructionsRetrievalStatus retrievalStatus,
        @JsonProperty(value = "degraded_components", required = true) @NotNull @Valid
        List<DegradedComponent> degradedComponents
    ) {}

    public record Source(
        @JsonProperty(value = "source_ref", required = true) @NotNull @CodePointSize(min = 1, max = 2147483647)
        String sourceRef,
        @JsonProperty(value = "source_type", required = true) @NotNull
        String sourceType,
        @JsonProperty(value = "content", required = true) @NotNull
        String content,
        @JsonProperty(value = "authority", required = true) @NotNull
        String authority,
        @JsonProperty(value = "source_revision", required = true) @NotNull
        String sourceRevision,
        @JsonProperty(value = "source_link_status", required = true) @NotNull
        SourceSourceLinkStatus sourceLinkStatus
    ) {}

    public record StreamInput(
        @JsonProperty(value = "correlation_id", required = true) @NotNull
        UUID correlationId,
        @JsonProperty(value = "provider_call_id", required = true) @NotNull
        UUID providerCallId,
        @JsonProperty(value = "task_type", required = true) @NotNull
        StreamInputTaskType taskType,
        @JsonProperty(value = "provider_options", required = true) @NotNull @Valid
        StreamOptions providerOptions,
        @JsonProperty(value = "prompt", required = true) @NotNull @Valid
        PromptEnvelope prompt
    ) {}

    public record StreamOptions(
        @JsonProperty(value = "model", required = true) @NotNull
        String model,
        @JsonProperty(value = "api_interface", required = true) @NotNull
        StreamOptionsApiInterface apiInterface,
        @JsonProperty(value = "store", required = true) @NotNull @AssertFalse
        Boolean store,
        @JsonProperty(value = "stream", required = true) @NotNull @AssertTrue
        Boolean stream,
        @JsonProperty(value = "timeout_seconds", required = true) @NotNull @Min(1)
        Long timeoutSeconds,
        @JsonProperty(value = "max_output_tokens", required = false) @JsonInclude(JsonInclude.Include.NON_NULL) @Min(1)
        Long maxOutputTokens
    ) {}

    public record TextBlock(
        @JsonProperty(value = "type", required = true) @NotNull @Pattern(regexp = "^TEXT$")
        String type,
        @JsonProperty(value = "text", required = true) @NotNull
        String text
    ) {}

    public record Usage(
        @JsonProperty(value = "input_tokens", required = true) @Min(0)
        Long inputTokens,
        @JsonProperty(value = "output_tokens", required = true) @Min(0)
        Long outputTokens,
        @JsonProperty(value = "total_tokens", required = true) @Min(0)
        Long totalTokens,
        @JsonProperty(value = "cached_tokens", required = true) @Min(0)
        Long cachedTokens,
        @JsonProperty(value = "reasoning_tokens", required = true) @Min(0)
        Long reasoningTokens
    ) {}

    public record VerifyInput(
        @JsonProperty(value = "correlation_id", required = true) @NotNull
        UUID correlationId,
        @JsonProperty(value = "provider_call_id", required = true) @NotNull
        UUID providerCallId
    ) {}

    public record VerifyResult(
        @JsonProperty(value = "correlation_id", required = true) @NotNull
        UUID correlationId,
        @JsonProperty(value = "provider_call_id", required = true) @NotNull
        UUID providerCallId,
        @JsonProperty(value = "valid", required = true) @NotNull
        Boolean valid,
        @JsonProperty(value = "checked_at", required = true) @NotNull
        Instant checkedAt,
        @JsonProperty(value = "error_code", required = true)
        VerifyResultErrorCode errorCode,
        @JsonProperty(value = "capability_status", required = true) @NotNull @Pattern(regexp = "^NOT_TESTED$")
        String capabilityStatus
    ) {}

    public record ProviderDispatched(
        @JsonProperty(value = "correlation_id", required = true) @NotNull
        UUID correlationId,
        @JsonProperty(value = "provider_call_id", required = true) @NotNull
        UUID providerCallId,
        @JsonProperty(value = "occurred_at", required = true) @NotNull
        Instant occurredAt,
        @JsonProperty(value = "provider_request_id", required = true)
        String providerRequestId
    ) {}

    public record AssistantDelta(
        @JsonProperty(value = "correlation_id", required = true) @NotNull
        UUID correlationId,
        @JsonProperty(value = "provider_call_id", required = true) @NotNull
        UUID providerCallId,
        @JsonProperty(value = "text", required = true) @NotNull
        String text
    ) {}

    public record ProviderCompleted(
        @JsonProperty(value = "correlation_id", required = true) @NotNull
        UUID correlationId,
        @JsonProperty(value = "provider_call_id", required = true) @NotNull
        UUID providerCallId,
        @JsonProperty(value = "assistant_text", required = true) @NotNull
        String assistantText,
        @JsonProperty(value = "native_output_steps", required = true) @NotNull @Valid
        List<JsonNode> nativeOutputSteps,
        @JsonProperty(value = "finish_reason", required = true)
        String finishReason,
        @JsonProperty(value = "service_tier", required = true)
        String serviceTier,
        @JsonProperty(value = "completed_at", required = true) @NotNull
        Instant completedAt,
        @JsonProperty(value = "usage_status", required = true) @NotNull
        ProviderCompletedUsageStatus usageStatus,
        @JsonProperty(value = "usage", required = true) @NotNull @Valid
        Usage usage
    ) {}

    public enum DegradedComponentComponent {
        @JsonProperty("VECTOR_SEARCH") VECTOR_SEARCH,
        @JsonProperty("KEYWORD_SEARCH") KEYWORD_SEARCH,
        @JsonProperty("SOT_LOOKUP") SOT_LOOKUP,
        @JsonProperty("ANCESTOR_HISTORY") ANCESTOR_HISTORY
    }

    public enum DegradedComponentStatus {
        @JsonProperty("PARTIAL") PARTIAL,
        @JsonProperty("UNAVAILABLE") UNAVAILABLE
    }

    public enum EmbeddingInputTaskType {
        @JsonProperty("QUERY_EMBEDDING") QUERY_EMBEDDING,
        @JsonProperty("KB_CHUNK_EMBEDDING") KB_CHUNK_EMBEDDING
    }

    public enum EmbeddingResultUsageStatus {
        @JsonProperty("AVAILABLE") AVAILABLE,
        @JsonProperty("PARTIAL") PARTIAL,
        @JsonProperty("UNAVAILABLE") UNAVAILABLE
    }

    public enum ExplicitReferenceKind {
        @JsonProperty("QUOTE") QUOTE,
        @JsonProperty("MAIN_REF") MAIN_REF
    }

    public enum GenerateInputTaskType {
        @JsonProperty("MAIN_STAGE_CONDENSATION") MAIN_STAGE_CONDENSATION,
        @JsonProperty("CONVERSATION_CONDENSATION") CONVERSATION_CONDENSATION,
        @JsonProperty("SUB_BRANCH_CHERRY_PICK_CONDENSE") SUB_BRANCH_CHERRY_PICK_CONDENSE,
        @JsonProperty("SUB_BRANCH_CONDENSE_ALL") SUB_BRANCH_CONDENSE_ALL,
        @JsonProperty("CHAPTER_TOPIC_DETECTION") CHAPTER_TOPIC_DETECTION,
        @JsonProperty("CHAPTER_SUMMARY_GENERATION") CHAPTER_SUMMARY_GENERATION
    }

    public enum GenerateOptionsApiInterface {
        @JsonProperty("INTERACTIONS") INTERACTIONS,
        @JsonProperty("GENERATE_CONTENT") GENERATE_CONTENT
    }

    public enum GenerateResultUsageStatus {
        @JsonProperty("AVAILABLE") AVAILABLE,
        @JsonProperty("PARTIAL") PARTIAL,
        @JsonProperty("UNAVAILABLE") UNAVAILABLE
    }

    public enum IntentContextTimeScope {
        @JsonProperty("CURRENT") CURRENT,
        @JsonProperty("HISTORICAL_OR_COMPARE") HISTORICAL_OR_COMPARE
    }

    public enum ProviderFailureErrorCategory {
        @JsonProperty("TRANSIENT_RETRYABLE") TRANSIENT_RETRYABLE,
        @JsonProperty("RATE_LIMIT_RETRYABLE") RATE_LIMIT_RETRYABLE,
        @JsonProperty("HARD_QUOTA_BLOCKED") HARD_QUOTA_BLOCKED,
        @JsonProperty("CONFIGURATION_BLOCKED") CONFIGURATION_BLOCKED,
        @JsonProperty("PERMANENT_INPUT_ERROR") PERMANENT_INPUT_ERROR,
        @JsonProperty("SOURCE_INVALIDATED") SOURCE_INVALIDATED,
        @JsonProperty("UNKNOWN_REQUIRES_REVIEW") UNKNOWN_REQUIRES_REVIEW
    }

    public enum ProviderFailureUsageStatus {
        @JsonProperty("AVAILABLE") AVAILABLE,
        @JsonProperty("PARTIAL") PARTIAL,
        @JsonProperty("UNAVAILABLE") UNAVAILABLE
    }

    public enum ResponseInstructionsEvidenceSufficiency {
        @JsonProperty("SUFFICIENT") SUFFICIENT,
        @JsonProperty("PARTIAL") PARTIAL,
        @JsonProperty("INSUFFICIENT") INSUFFICIENT
    }

    public enum ResponseInstructionsRetrievalStatus {
        @JsonProperty("COMPLETE") COMPLETE,
        @JsonProperty("PARTIAL") PARTIAL,
        @JsonProperty("UNAVAILABLE") UNAVAILABLE
    }

    public enum SourceSourceLinkStatus {
        @JsonProperty("AVAILABLE") AVAILABLE,
        @JsonProperty("UNAVAILABLE") UNAVAILABLE
    }

    public enum StreamInputTaskType {
        @JsonProperty("CHAT_MAIN") CHAT_MAIN,
        @JsonProperty("CHAT_SUB_BRANCH") CHAT_SUB_BRANCH
    }

    public enum StreamOptionsApiInterface {
        @JsonProperty("INTERACTIONS") INTERACTIONS,
        @JsonProperty("GENERATE_CONTENT") GENERATE_CONTENT
    }

    public enum VerifyResultErrorCode {
        @JsonProperty("INVALID_API_KEY") INVALID_API_KEY,
        @JsonProperty("PERMISSION_DENIED") PERMISSION_DENIED,
        @JsonProperty("PROVIDER_UNAVAILABLE") PROVIDER_UNAVAILABLE
    }

    public enum ProviderCompletedUsageStatus {
        @JsonProperty("AVAILABLE") AVAILABLE,
        @JsonProperty("PARTIAL") PARTIAL,
        @JsonProperty("UNAVAILABLE") UNAVAILABLE
    }

}
