"""BranOS-04-6 v2: snake_case wire DTOs. Provider-native objects remain opaque."""

from datetime import datetime
from typing import Annotated, Literal, Self
from uuid import UUID

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, JsonValue, model_validator

UsageStatus = Literal["AVAILABLE", "PARTIAL", "UNAVAILABLE"]
ErrorCategory = Literal[
    "TRANSIENT_RETRYABLE",
    "RATE_LIMIT_RETRYABLE",
    "HARD_QUOTA_BLOCKED",
    "CONFIGURATION_BLOCKED",
    "PERMANENT_INPUT_ERROR",
    "SOURCE_INVALIDATED",
    "UNKNOWN_REQUIRES_REVIEW",
]
StreamTask = Literal["CHAT_MAIN", "CHAT_SUB_BRANCH"]
GenerateTask = Literal[
    "MAIN_STAGE_CONDENSATION",
    "CONVERSATION_CONDENSATION",
    "SUB_BRANCH_CHERRY_PICK_CONDENSE",
    "SUB_BRANCH_CONDENSE_ALL",
    "CHAPTER_TOPIC_DETECTION",
    "CHAPTER_SUMMARY_GENERATION",
]
EmbeddingTask = Literal["QUERY_EMBEDDING", "KB_CHUNK_EMBEDDING"]
ApiInterface = Literal["INTERACTIONS", "GENERATE_CONTENT"]
Component = Literal["VECTOR_SEARCH", "KEYWORD_SEARCH", "SOT_LOOKUP", "ANCESTOR_HISTORY"]
DegradedStatus = Literal["PARTIAL", "UNAVAILABLE"]
NativeStep = dict[str, JsonValue]
TokenCount = Annotated[int, Field(ge=0, strict=True)] | None
FiniteNumber = Annotated[float, Field(allow_inf_nan=False, strict=True)]
Vector = Annotated[list[FiniteNumber], Field(min_length=1536, max_length=1536)]


def local_alias(value: str) -> str:
    try:
        UUID(value)
    except ValueError:
        return value
    raise ValueError("Use a request-local alias instead of a business UUID")


Alias = Annotated[str, Field(min_length=1), AfterValidator(local_alias)]


class WireModel(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)


class Usage(WireModel):
    input_tokens: TokenCount
    output_tokens: TokenCount
    total_tokens: TokenCount
    cached_tokens: TokenCount
    reasoning_tokens: TokenCount


class StreamOptions(WireModel):
    model: str
    api_interface: ApiInterface
    store: Literal[False]
    stream: Literal[True]
    timeout_seconds: int = Field(ge=1)
    max_output_tokens: int | None = Field(default=None, ge=1)


class GenerateOptions(WireModel):
    model: str
    api_interface: ApiInterface
    store: Literal[False]
    stream: Literal[False]
    timeout_seconds: int = Field(ge=1)
    max_output_tokens: int | None = Field(default=None, ge=1)


class TextBlock(WireModel):
    type: Literal["TEXT"]
    text: str


class QuoteRefBlock(WireModel):
    type: Literal["QUOTE_REF"]
    quote_ref_id: Alias


ContentBlock = Annotated[TextBlock | QuoteRefBlock, Field(discriminator="type")]


class Instruction(WireModel):
    id: Alias
    priority: int = Field(ge=0)
    content: str


class Source(WireModel):
    source_ref: Alias
    source_type: str
    content: str
    authority: str
    source_revision: str
    source_link_status: Literal["AVAILABLE", "UNAVAILABLE"]


class ExplicitReference(WireModel):
    kind: Literal["QUOTE", "MAIN_REF"]
    id: Alias
    source_type: str
    snapshot_text: str
    source_revision: str | None = None
    authority: str


class HistoricalQuote(WireModel):
    quote_ref_id: Alias
    snapshot_text: str
    source_type: str


class ContinuityTurn(WireModel):
    turn_ref: Alias
    user_text_blocks: list[str]
    historical_quotes: list[HistoricalQuote]
    native_output_steps: list[NativeStep]
    user_content_blocks: list[ContentBlock] | None = None

    @model_validator(mode="after")
    def historical_quote_positions(self) -> Self:
        if self.historical_quotes and self.user_content_blocks is None:
            raise ValueError("Historical quotes require ordered user_content_blocks")
        if self.user_content_blocks is not None:
            aliases = [
                b.quote_ref_id for b in self.user_content_blocks if isinstance(b, QuoteRefBlock)
            ]
            quotes = [q.quote_ref_id for q in self.historical_quotes]
            if len(set(quotes)) != len(quotes) or sorted(aliases) != sorted(quotes):
                raise ValueError("Historical quote blocks and aliases must match one-to-one")
        return self


class IntentContext(WireModel):
    primary_intent: str
    time_scope: Literal["CURRENT", "HISTORICAL_OR_COMPARE"]
    resolved_entities: list[str] = Field(default_factory=list)
    exact_lookup: bool = False


class DegradedComponent(WireModel):
    component: Component
    status: DegradedStatus
    reason_code: str


class CurrentUserRequest(WireModel):
    content_blocks: list[ContentBlock]


class IntelligenceBackground(WireModel):
    project_information: str | None
    personal_profile: str | None


class IntelligenceInstructions(WireModel):
    mandatory_rules: list[Instruction]
    overridable_rules: list[Instruction]
    active_skills: list[Instruction]
    personal_instructions: str | None


class IntelligenceContext(WireModel):
    background: IntelligenceBackground
    instructions: IntelligenceInstructions


class KnowledgeAndEvidence(WireModel):
    history: list[Source]
    knowledge_base: list[Source]
    source_of_truth: list[Source]
    explicit_references: list[ExplicitReference]


class ResponseInstructions(WireModel):
    intent_context: IntentContext
    evidence_sufficiency: Literal["SUFFICIENT", "PARTIAL", "INSUFFICIENT"]
    retrieval_status: Literal["COMPLETE", "PARTIAL", "UNAVAILABLE"]
    degraded_components: list[DegradedComponent]


class PromptEnvelope(WireModel):
    platform_policy: str
    current_user_request: CurrentUserRequest
    intelligence_context: IntelligenceContext
    knowledge_and_evidence: KnowledgeAndEvidence
    response_instructions: ResponseInstructions
    continuity: list[ContinuityTurn]


class TraceInput(WireModel):
    correlation_id: UUID
    provider_call_id: UUID


class StreamInput(TraceInput):
    task_type: StreamTask
    provider_options: StreamOptions
    prompt: PromptEnvelope


class GenerateInput(TraceInput):
    task_type: GenerateTask
    provider_options: GenerateOptions
    prompt: PromptEnvelope
    response_schema: dict[str, JsonValue] | None = None


class EmbeddingInput(TraceInput):
    task_type: EmbeddingTask
    model: str
    output_dimensionality: Literal[1536]
    texts: list[str] = Field(min_length=1, max_length=10)

    @model_validator(mode="after")
    def query_has_one_text(self) -> Self:
        if self.task_type == "QUERY_EMBEDDING" and len(self.texts) != 1:
            raise ValueError("QUERY_EMBEDDING requires exactly one text")
        return self


class VerifyInput(TraceInput):
    pass


class VerifyResult(TraceInput):
    valid: bool
    checked_at: datetime
    error_code: Literal["INVALID_API_KEY", "PERMISSION_DENIED", "PROVIDER_UNAVAILABLE"] | None
    capability_status: Literal["NOT_TESTED"]


class GenerateResult(TraceInput):
    dispatched: bool
    occurred_at: datetime | None
    completed_at: datetime
    model: str
    provider_request_id: str | None
    output_text: str
    parsed_json: dict[str, JsonValue] | list[JsonValue] | None
    native_output_steps: list[NativeStep]
    finish_reason: str | None
    service_tier: str | None
    usage_status: UsageStatus
    usage: Usage


class EmbeddingResult(TraceInput):
    dispatched: bool
    occurred_at: datetime | None
    completed_at: datetime
    model: str
    provider_request_id: str | None
    vectors: list[Vector]
    usage_status: UsageStatus
    usage: Usage


class ProviderDispatched(TraceInput):
    occurred_at: datetime
    provider_request_id: str | None


class AssistantDelta(TraceInput):
    text: str


class ProviderCompleted(TraceInput):
    assistant_text: str
    native_output_steps: list[NativeStep]
    finish_reason: str | None
    service_tier: str | None
    completed_at: datetime
    usage_status: UsageStatus
    usage: Usage


class ProviderFailure(TraceInput):
    dispatched: bool
    occurred_at: datetime | None
    completed_at: datetime
    error_category: ErrorCategory
    error_code: str
    retryable: bool
    retry_after_seconds: int | None = Field(ge=0)
    usage_status: UsageStatus
    usage: Usage


class InternalError(WireModel):
    error: ProviderFailure


INTERNAL_EVENTS: dict[str, type[BaseModel]] = {
    "provider.dispatched": ProviderDispatched,
    "assistant.delta": AssistantDelta,
    "provider.completed": ProviderCompleted,
    "provider.failed": ProviderFailure,
}
