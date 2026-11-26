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
public final class PublicApi {
    private PublicApi() {}

    public record ApiError(
        @JsonProperty(value = "code", required = true) @NotNull @Valid
        ErrorCode code,
        @JsonProperty(value = "message", required = true) @NotNull
        String message,
        @JsonProperty(value = "retryable", required = true) @NotNull
        Boolean retryable,
        @JsonProperty(value = "correlationId", required = true) @NotNull
        UUID correlationId,
        @JsonProperty(value = "details", required = false) @JsonInclude(JsonInclude.Include.NON_NULL)
        JsonNode details
    ) {}

    public record ErrorEnvelope(
        @JsonProperty(value = "error", required = true) @NotNull @Valid
        ApiError error
    ) {}

    public record TextBlock(
        @JsonProperty(value = "type", required = true) @NotNull
        TextBlockType type,
        @JsonProperty(value = "text", required = true) @NotNull
        String text
    ) implements ContentBlock {}

    public record QuoteRefBlock(
        @JsonProperty(value = "type", required = true) @NotNull
        QuoteRefBlockType type,
        @JsonProperty(value = "quoteRefId", required = true) @NotNull @CodePointSize(min = 1, max = 64)
        String quoteRefId
    ) implements ContentBlock {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class, name = "TEXT"),
        @JsonSubTypes.Type(value = QuoteRefBlock.class, name = "QUOTE_REF")
    })
    public sealed interface ContentBlock permits TextBlock, QuoteRefBlock {}

    public record Locator(
        @JsonProperty(value = "charStart", required = true) @NotNull @Min(0)
        Long charStart,
        @JsonProperty(value = "charEnd", required = true) @NotNull @Min(1)
        Long charEnd,
        @JsonProperty(value = "prefixAnchor", required = true) @NotNull @CodePointSize(min = 0, max = 32)
        String prefixAnchor,
        @JsonProperty(value = "suffixAnchor", required = true) @NotNull @CodePointSize(min = 0, max = 32)
        String suffixAnchor
    ) {}

    public record QuoteInput(
        @JsonProperty(value = "quoteRefId", required = true) @NotNull @CodePointSize(min = 1, max = 64)
        String quoteRefId,
        @JsonProperty(value = "sourceKind", required = true) @NotNull @Valid
        QuoteSourceKind sourceKind,
        @JsonProperty(value = "sourceId", required = true) @NotNull
        UUID sourceId,
        @JsonProperty(value = "sourceRevision", required = true) @NotNull
        String sourceRevision,
        @JsonProperty(value = "locator", required = true) @NotNull @Valid
        Locator locator,
        @JsonProperty(value = "contentHashSha256Hex", required = true) @NotNull @Pattern(regexp = "^[0-9a-f]{64}$")
        String contentHashSha256Hex,
        @JsonProperty(value = "snapshotText", required = true) @NotNull
        String snapshotText
    ) {}

    public record StageReference(
        @JsonProperty(value = "granularity", required = true) @NotNull
        StageReferenceGranularity granularity,
        @JsonProperty(value = "targetStageId", required = true) @NotNull
        UUID targetStageId,
        @JsonProperty(value = "targetChapterId", required = false) @JsonInclude(JsonInclude.Include.NON_NULL)
        Void targetChapterId,
        @JsonProperty(value = "targetTurnId", required = false) @JsonInclude(JsonInclude.Include.NON_NULL)
        Void targetTurnId
    ) implements MainReference {}

    public record ChapterReference(
        @JsonProperty(value = "granularity", required = true) @NotNull
        ChapterReferenceGranularity granularity,
        @JsonProperty(value = "targetStageId", required = true) @NotNull
        UUID targetStageId,
        @JsonProperty(value = "targetChapterId", required = true) @NotNull
        UUID targetChapterId,
        @JsonProperty(value = "targetTurnId", required = false) @JsonInclude(JsonInclude.Include.NON_NULL)
        Void targetTurnId
    ) implements MainReference {}

    public record TurnReference(
        @JsonProperty(value = "granularity", required = true) @NotNull
        TurnReferenceGranularity granularity,
        @JsonProperty(value = "targetStageId", required = true) @NotNull
        UUID targetStageId,
        @JsonProperty(value = "targetChapterId", required = false) @JsonInclude(JsonInclude.Include.NON_NULL)
        Void targetChapterId,
        @JsonProperty(value = "targetTurnId", required = true) @NotNull
        UUID targetTurnId
    ) implements MainReference {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "granularity", visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = StageReference.class, name = "STAGE"),
        @JsonSubTypes.Type(value = ChapterReference.class, name = "CHAPTER"),
        @JsonSubTypes.Type(value = TurnReference.class, name = "TURN")
    })
    public sealed interface MainReference permits StageReference, ChapterReference, TurnReference {}

    public record ChatInput(
        @JsonProperty(value = "projectId", required = true) @NotNull
        UUID projectId,
        @JsonProperty(value = "conversationId", required = true) @NotNull
        UUID conversationId,
        @JsonProperty(value = "mainStageId", required = true) @NotNull
        UUID mainStageId,
        @JsonProperty(value = "subBranchId", required = false) @JsonInclude(JsonInclude.Include.NON_NULL)
        UUID subBranchId,
        @JsonProperty(value = "userContentBlocks", required = true) @NotNull @Size(min = 1, max = 2147483647) @Valid
        List<ContentBlock> userContentBlocks,
        @JsonProperty(value = "quotes", required = false) @JsonInclude(JsonInclude.Include.NON_NULL) @Valid
        List<QuoteInput> quotes,
        @JsonProperty(value = "mainReferences", required = false) @JsonInclude(JsonInclude.Include.NON_NULL) @Valid
        List<MainReference> mainReferences
    ) {
        public ChatInput {
            quotes = quotes == null ? List.of() : List.copyOf(quotes);
            mainReferences = mainReferences == null ? List.of() : List.copyOf(mainReferences);
        }
    }

    public record ChatAccepted(
        @JsonProperty(value = "requestId", required = true) @NotNull
        UUID requestId,
        @JsonProperty(value = "correlationId", required = true) @NotNull
        UUID correlationId,
        @JsonProperty(value = "status", required = true) @NotNull @Valid
        RequestState status,
        @JsonProperty(value = "effectivePrivacyTierPlanned", required = false) @JsonInclude(JsonInclude.Include.NON_NULL) @Valid
        PrivacyTier effectivePrivacyTierPlanned,
        @JsonProperty(value = "eventsUrl", required = true) @NotNull
        String eventsUrl,
        @JsonProperty(value = "statusUrl", required = true) @NotNull
        String statusUrl,
        @JsonProperty(value = "cancelUrl", required = true) @NotNull
        String cancelUrl,
        @JsonProperty(value = "createdAt", required = true) @NotNull
        Instant createdAt
    ) {}

    public record RequestStatus(
        @JsonProperty(value = "requestId", required = true) @NotNull
        UUID requestId,
        @JsonProperty(value = "status", required = true) @NotNull @Valid
        RequestState status,
        @JsonProperty(value = "turnId", required = true)
        UUID turnId,
        @JsonProperty(value = "lastEventId", required = true) @NotNull @Min(0)
        Long lastEventId,
        @JsonProperty(value = "errorCode", required = true) @Valid
        ErrorCode errorCode,
        @JsonProperty(value = "createdAt", required = true) @NotNull
        Instant createdAt,
        @JsonProperty(value = "finishedAt", required = true)
        Instant finishedAt
    ) {}

    public record CancelResult(
        @JsonProperty(value = "requestId", required = true) @NotNull
        UUID requestId,
        @JsonProperty(value = "status", required = true) @NotNull @Valid
        TerminalState status,
        @JsonProperty(value = "alreadyTerminal", required = true) @NotNull
        Boolean alreadyTerminal
    ) {}

    public record QuoteSaved(
        @JsonProperty(value = "quoteRefId", required = true) @NotNull
        UUID quoteRefId,
        @JsonProperty(value = "sourceKind", required = true) @NotNull @Valid
        QuoteSourceKind sourceKind,
        @JsonProperty(value = "sourceId", required = true) @NotNull
        UUID sourceId,
        @JsonProperty(value = "sourceRevision", required = true) @NotNull
        String sourceRevision,
        @JsonProperty(value = "locator", required = true) @NotNull @Valid
        Locator locator,
        @JsonProperty(value = "contentHashSha256Hex", required = true) @NotNull @Pattern(regexp = "^[0-9a-f]{64}$")
        String contentHashSha256Hex,
        @JsonProperty(value = "snapshotText", required = true) @NotNull
        String snapshotText,
        @JsonProperty(value = "linkStatus", required = true) @NotNull @Valid
        LinkStatus linkStatus,
        @JsonProperty(value = "excludeFromAutoKb", required = true) @NotNull
        Boolean excludeFromAutoKb
    ) {}

    public record Turn(
        @JsonProperty(value = "id", required = true) @NotNull
        UUID id,
        @JsonProperty(value = "projectId", required = false) @JsonInclude(JsonInclude.Include.NON_NULL)
        UUID projectId,
        @JsonProperty(value = "conversationId", required = false) @JsonInclude(JsonInclude.Include.NON_NULL)
        UUID conversationId,
        @JsonProperty(value = "mainStageId", required = true) @NotNull
        UUID mainStageId,
        @JsonProperty(value = "subBranchId", required = true)
        UUID subBranchId,
        @JsonProperty(value = "chapterId", required = false) @JsonInclude(JsonInclude.Include.NON_NULL)
        UUID chapterId,
        @JsonProperty(value = "sequenceNo", required = true) @NotNull @Min(1)
        Long sequenceNo,
        @JsonProperty(value = "sourceRevision", required = true) @NotNull @Min(1)
        Long sourceRevision,
        @JsonProperty(value = "userContentBlocks", required = true) @NotNull @Size(min = 1, max = 2147483647) @Valid
        List<ContentBlock> userContentBlocks,
        @JsonProperty(value = "quotes", required = true) @NotNull @Valid
        List<QuoteSaved> quotes,
        @JsonProperty(value = "mainReferences", required = true) @NotNull @Valid
        List<MainReference> mainReferences,
        @JsonProperty(value = "assistantText", required = true) @NotNull
        String assistantText,
        @JsonProperty(value = "createdAt", required = true) @NotNull
        Instant createdAt
    ) {}

    public record Turns(
        @JsonProperty(value = "items", required = true) @NotNull @Valid
        List<Turn> items,
        @JsonProperty(value = "nextAfterSequence", required = true) @Min(0)
        Long nextAfterSequence,
        @JsonProperty(value = "hasMore", required = true) @NotNull
        Boolean hasMore
    ) {}

    public record PublicUsage(
        @JsonProperty(value = "totalInputTokens", required = true) @Min(0)
        Long totalInputTokens,
        @JsonProperty(value = "totalOutputTokens", required = true) @Min(0)
        Long totalOutputTokens,
        @JsonProperty(value = "totalThoughtTokens", required = true) @Min(0)
        Long totalThoughtTokens,
        @JsonProperty(value = "totalCachedTokens", required = true) @Min(0)
        Long totalCachedTokens,
        @JsonProperty(value = "totalTokens", required = true) @Min(0)
        Long totalTokens
    ) {}

    public record EventAccepted(
        @JsonProperty(value = "requestId", required = true) @NotNull
        UUID requestId,
        @JsonProperty(value = "status", required = true) @NotNull @Pattern(regexp = "^ACCEPTED$")
        String status,
        @JsonProperty(value = "projectId", required = true) @NotNull
        UUID projectId,
        @JsonProperty(value = "mainStageId", required = true) @NotNull
        UUID mainStageId,
        @JsonProperty(value = "subBranchId", required = true)
        UUID subBranchId
    ) {}

    public record DegradedComponent(
        @JsonProperty(value = "component", required = true) @NotNull @Valid
        RetrievalComponent component,
        @JsonProperty(value = "status", required = true) @NotNull @Valid
        DegradedStatus status,
        @JsonProperty(value = "reasonCode", required = true) @NotNull
        String reasonCode
    ) {}

    public record EventDegraded(
        @JsonProperty(value = "requestId", required = true) @NotNull
        UUID requestId,
        @JsonProperty(value = "overallStatus", required = true) @NotNull @Valid
        DegradedStatus overallStatus,
        @JsonProperty(value = "components", required = true) @NotNull @Valid
        List<DegradedComponent> components,
        @JsonProperty(value = "userMessage", required = true) @NotNull
        String userMessage
    ) {}

    public record EventStarted(
        @JsonProperty(value = "requestId", required = true) @NotNull
        UUID requestId,
        @JsonProperty(value = "effectivePrivacyTier", required = true) @NotNull @Valid
        PrivacyTier effectivePrivacyTier,
        @JsonProperty(value = "mixedTierWarning", required = true) @NotNull
        Boolean mixedTierWarning,
        @JsonProperty(value = "platformTasksInUnpaidTier", required = true) @NotNull @Valid
        List<String> platformTasksInUnpaidTier,
        @JsonProperty(value = "rolloverSuggested", required = true) @NotNull
        Boolean rolloverSuggested,
        @JsonProperty(value = "estimatedInputTokens", required = true) @NotNull @Min(0)
        Long estimatedInputTokens
    ) {}

    public record EventDelta(
        @JsonProperty(value = "requestId", required = true) @NotNull
        UUID requestId,
        @JsonProperty(value = "text", required = true) @NotNull @CodePointSize(min = 1, max = 2147483647)
        String text
    ) {}

    public record EventCompleted(
        @JsonProperty(value = "requestId", required = true) @NotNull
        UUID requestId,
        @JsonProperty(value = "turnId", required = true) @NotNull
        UUID turnId,
        @JsonProperty(value = "sequenceNo", required = true) @NotNull @Min(1)
        Long sequenceNo,
        @JsonProperty(value = "sourceRevision", required = true) @NotNull @Min(1)
        Long sourceRevision,
        @JsonProperty(value = "finishReason", required = true)
        String finishReason,
        @JsonProperty(value = "modelUsed", required = true) @NotNull
        String modelUsed,
        @JsonProperty(value = "effectivePrivacyTier", required = true) @NotNull @Valid
        PrivacyTier effectivePrivacyTier,
        @JsonProperty(value = "usageStatus", required = true) @NotNull @Valid
        UsageStatus usageStatus,
        @JsonProperty(value = "usage", required = true) @NotNull @Valid
        PublicUsage usage
    ) {}

    public record EventFailed(
        @JsonProperty(value = "requestId", required = true) @NotNull
        UUID requestId,
        @JsonProperty(value = "errorCode", required = true) @NotNull @Valid
        ErrorCode errorCode,
        @JsonProperty(value = "message", required = true) @NotNull
        String message,
        @JsonProperty(value = "retryable", required = true) @NotNull
        Boolean retryable,
        @JsonProperty(value = "retryAfterSeconds", required = true) @Min(0)
        Long retryAfterSeconds
    ) {}

    public record EventCancelled(
        @JsonProperty(value = "requestId", required = true) @NotNull
        UUID requestId,
        @JsonProperty(value = "reason", required = true) @NotNull @Valid
        CancelReason reason
    ) {}

    public record OffsetPage(
        @JsonProperty(value = "items", required = true) @NotNull @Valid
        List<JsonNode> items,
        @JsonProperty(value = "page", required = true) @NotNull @Min(0)
        Long page,
        @JsonProperty(value = "size", required = true) @NotNull @Min(1) @Max(100)
        Long size,
        @JsonProperty(value = "totalElements", required = true) @NotNull @Min(0)
        Long totalElements,
        @JsonProperty(value = "totalPages", required = true) @NotNull @Min(0)
        Long totalPages,
        @JsonProperty(value = "hasNext", required = true) @NotNull
        Boolean hasNext
    ) {}

    public enum RequestState {
        @JsonProperty("ACCEPTED") ACCEPTED,
        @JsonProperty("IN_PROGRESS") IN_PROGRESS,
        @JsonProperty("COMPLETED") COMPLETED,
        @JsonProperty("FAILED") FAILED,
        @JsonProperty("CANCELLED") CANCELLED
    }

    public enum TerminalState {
        @JsonProperty("COMPLETED") COMPLETED,
        @JsonProperty("FAILED") FAILED,
        @JsonProperty("CANCELLED") CANCELLED
    }

    public enum PrivacyTier {
        @JsonProperty("FREE") FREE,
        @JsonProperty("PAID") PAID,
        @JsonProperty("UNKNOWN") UNKNOWN
    }

    public enum UsageStatus {
        @JsonProperty("AVAILABLE") AVAILABLE,
        @JsonProperty("PARTIAL") PARTIAL,
        @JsonProperty("UNAVAILABLE") UNAVAILABLE
    }

    public enum LinkStatus {
        @JsonProperty("AVAILABLE") AVAILABLE,
        @JsonProperty("UNAVAILABLE") UNAVAILABLE
    }

    public enum QuoteSourceKind {
        @JsonProperty("MAIN_TURN") MAIN_TURN,
        @JsonProperty("SUB_BRANCH_TURN") SUB_BRANCH_TURN,
        @JsonProperty("KB_ITEM") KB_ITEM,
        @JsonProperty("SOT_RECORD") SOT_RECORD,
        @JsonProperty("CHAPTER_SUMMARY") CHAPTER_SUMMARY
    }

    public enum CancelReason {
        @JsonProperty("USER_CANCELLED") USER_CANCELLED,
        @JsonProperty("PROJECT_DELETING") PROJECT_DELETING,
        @JsonProperty("CREDENTIAL_REMOVED") CREDENTIAL_REMOVED,
        @JsonProperty("SOURCE_INVALIDATED") SOURCE_INVALIDATED
    }

    public enum RetrievalComponent {
        @JsonProperty("VECTOR_SEARCH") VECTOR_SEARCH,
        @JsonProperty("KEYWORD_SEARCH") KEYWORD_SEARCH,
        @JsonProperty("SOT_LOOKUP") SOT_LOOKUP,
        @JsonProperty("ANCESTOR_HISTORY") ANCESTOR_HISTORY
    }

    public enum DegradedStatus {
        @JsonProperty("PARTIAL") PARTIAL,
        @JsonProperty("UNAVAILABLE") UNAVAILABLE
    }

    public enum ErrorCode {
        VALIDATION_ERROR(400, false),
        INVALID_SSE_CURSOR(400, false),
        UNAUTHENTICATED(401, false),
        ACCOUNT_DISABLED(403, false),
        FORBIDDEN(403, false),
        RESOURCE_NOT_FOUND(404, false),
        PROJECT_DELETING(409, false),
        CHAT_SCOPE_LOCKED(409, false),
        IDEMPOTENCY_KEY_REUSED(409, false),
        CHAT_REQUEST_IN_PROGRESS(409, true),
        CONFLICT_STALE_REVISION(409, false),
        SOURCE_DELETED(410, false),
        SSE_CURSOR_EXPIRED(410, false),
        USER_CREDENTIAL_UNAVAILABLE(422, false),
        DISCLOSURE_REQUIRED(422, false),
        INVALID_EXPLICIT_REFERENCE(422, false),
        EXPLICIT_REFERENCE_BUDGET_EXCEEDED(422, false),
        STAGE_CONTEXT_LIMIT(422, false),
        BRANCH_CONTEXT_LIMIT(422, false),
        APP_USAGE_LIMIT_EXCEEDED(429, false),
        PROVIDER_RATE_LIMITED(429, true),
        PROVIDER_QUOTA_EXHAUSTED(429, false),
        PLATFORM_CREDENTIAL_UNAVAILABLE(503, false),
        PROVIDER_UNAVAILABLE(503, true),
        WORKER_LEASE_EXPIRED(null, false);
        private final Integer httpStatus;
        private final boolean retryable;
        ErrorCode(Integer httpStatus, boolean retryable) { this.httpStatus = httpStatus; this.retryable = retryable; }
        public Integer httpStatus() { return httpStatus; }
        public boolean retryable() { return retryable; }
    }

    public enum TextBlockType {
        @JsonProperty("TEXT") TEXT
    }

    public enum QuoteRefBlockType {
        @JsonProperty("QUOTE_REF") QUOTE_REF
    }

    public enum StageReferenceGranularity {
        @JsonProperty("STAGE") STAGE
    }

    public enum ChapterReferenceGranularity {
        @JsonProperty("CHAPTER") CHAPTER
    }

    public enum TurnReferenceGranularity {
        @JsonProperty("TURN") TURN
    }

}
