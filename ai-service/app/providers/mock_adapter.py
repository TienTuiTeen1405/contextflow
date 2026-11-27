import asyncio
import hashlib
from collections.abc import AsyncIterator
from contextvars import ContextVar, Token
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Literal

from pydantic import BaseModel, SecretStr

from app.prompt_builder import BuiltPrompt, build_prompt, get_current_built_prompt
from app.providers.base import AdapterStatus
from app.providers.error_mapper import ProviderExecutionError
from app.schemas.contracts import (
    AssistantDelta,
    EmbeddingInput,
    EmbeddingResult,
    GenerateInput,
    GenerateResult,
    PromptEnvelope,
    ProviderCompleted,
    ProviderDispatched,
    ProviderFailure,
    StreamInput,
    TextBlock,
    Usage,
    UsageStatus,
    VerifyInput,
    VerifyResult,
)

MockScenario = Literal[
    "NORMAL",
    "INVALID_KEY",
    "MOCK_429",
    "MOCK_ERROR_MID_STREAM",
    "MOCK_SLOW_STREAM",
    "MOCK_PARTIAL_USAGE",
]

_HEADER_SCENARIO_MAP: dict[str, MockScenario] = {
    "INVALID_KEY": "INVALID_KEY",
    "[MOCK_INVALID_KEY]": "INVALID_KEY",
    "MOCK_INVALID_KEY": "INVALID_KEY",
    "[MOCK_429]": "MOCK_429",
    "MOCK_429": "MOCK_429",
    "[MOCK_ERROR_MID_STREAM]": "MOCK_ERROR_MID_STREAM",
    "MOCK_ERROR_MID_STREAM": "MOCK_ERROR_MID_STREAM",
    "[MOCK_SLOW_STREAM]": "MOCK_SLOW_STREAM",
    "MOCK_SLOW_STREAM": "MOCK_SLOW_STREAM",
    "[MOCK_PARTIAL_USAGE]": "MOCK_PARTIAL_USAGE",
    "MOCK_PARTIAL_USAGE": "MOCK_PARTIAL_USAGE",
}

_KEYWORD_SCENARIOS: tuple[tuple[str, MockScenario], ...] = (
    ("INVALID_KEY", "INVALID_KEY"),
    ("[MOCK_INVALID_KEY]", "INVALID_KEY"),
    ("[MOCK_429]", "MOCK_429"),
    ("[MOCK_ERROR_MID_STREAM]", "MOCK_ERROR_MID_STREAM"),
    ("[MOCK_SLOW_STREAM]", "MOCK_SLOW_STREAM"),
    ("[MOCK_PARTIAL_USAGE]", "MOCK_PARTIAL_USAGE"),
)


@dataclass(frozen=True)
class MockRequestContext:
    """Request-scoped mock control metadata read from HTTP headers without OpenAPI changes."""

    scenario_header: str | None = None
    credential_value: str | None = None
    slow_delay_ms: int | None = None


_MOCK_REQUEST_CONTEXT: ContextVar[MockRequestContext | None] = ContextVar(
    "mock_request_context", default=None
)


def set_mock_request_context(ctx: MockRequestContext) -> Token[MockRequestContext | None]:
    """Bind request-scoped mock scenario metadata for the current async execution."""
    return _MOCK_REQUEST_CONTEXT.set(ctx)


def get_mock_request_context() -> MockRequestContext | None:
    """Retrieve active request-scoped mock scenario metadata, if any."""
    return _MOCK_REQUEST_CONTEXT.get()


def reset_mock_request_context(token: Token[MockRequestContext | None]) -> None:
    """Restore previous mock request context after request handling."""
    _MOCK_REQUEST_CONTEXT.reset(token)


class MockScenarioFailure(ProviderExecutionError):
    """Normalized adapter failure raised by synchronous mock error triggers."""


def unavailable_usage() -> Usage:
    return Usage(
        input_tokens=None,
        output_tokens=None,
        total_tokens=None,
        cached_tokens=None,
        reasoning_tokens=None,
    )


def partial_usage() -> Usage:
    return Usage(
        input_tokens=42,
        output_tokens=18,
        total_tokens=60,
        cached_tokens=None,
        reasoning_tokens=None,
    )


def _prompt_text(prompt: PromptEnvelope) -> str:
    return "\n".join(
        block.text
        for block in prompt.current_user_request.content_blocks
        if isinstance(block, TextBlock)
    )


def _detect_scenario(texts: list[str], ctx: MockRequestContext | None) -> MockScenario:
    if ctx is not None:
        if ctx.scenario_header:
            mapped = _HEADER_SCENARIO_MAP.get(ctx.scenario_header.strip().upper())
            if mapped is not None:
                return mapped
        if ctx.credential_value == "INVALID_KEY":
            return "INVALID_KEY"
    combined = "\n".join(texts)
    for keyword, scenario in _KEYWORD_SCENARIOS:
        if keyword in combined:
            return scenario
    return "NORMAL"


def _format_context_summary(built: BuiltPrompt) -> str:
    s = built.summary
    return (
        f"Context(mandatory_rules={s.mandatory_rules_count}, "
        f"overridable_rules={s.overridable_rules_count}, "
        f"active_skills={s.active_skills_count}, "
        f"continuity_turns={s.continuity_turns_count}, "
        f"sot={s.source_of_truth_sources_count}, "
        f"kb={s.knowledge_base_sources_count}, "
        f"history={s.history_sources_count}, "
        f"explicit_refs={s.explicit_references_count}, "
        f"intent={s.primary_intent}, "
        f"sufficiency={s.evidence_sufficiency})."
    )


class MockProviderAdapter:
    """Synthetic local attempts. No network, SDK, real-key checks or usage estimates."""

    def __init__(self, *, slow_chunk_delay_seconds: float = 0.05) -> None:
        self.slow_chunk_delay_seconds = slow_chunk_delay_seconds

    def status(self) -> AdapterStatus:
        return AdapterStatus(mode="MOCK", external_calls_enabled=False)

    async def stream(self, request: StreamInput) -> AsyncIterator[tuple[str, BaseModel]]:
        resolved_prompt = get_current_built_prompt() or build_prompt(request.prompt)
        ctx = get_mock_request_context()
        scenario = _detect_scenario([_prompt_text(request.prompt)], ctx)

        trace = {
            "correlation_id": request.correlation_id,
            "provider_call_id": request.provider_call_id,
        }

        if scenario == "INVALID_KEY":
            yield (
                "provider.failed",
                ProviderFailure(
                    **trace,
                    dispatched=False,
                    occurred_at=None,
                    completed_at=datetime.now(UTC),
                    error_category="CONFIGURATION_BLOCKED",
                    error_code="INVALID_API_KEY",
                    retryable=False,
                    retry_after_seconds=None,
                    usage_status="UNAVAILABLE",
                    usage=unavailable_usage(),
                ),
            )
            return

        occurred_at = datetime.now(UTC)
        yield (
            "provider.dispatched",
            ProviderDispatched(**trace, occurred_at=occurred_at, provider_request_id=None),
        )

        if scenario == "MOCK_429":
            yield (
                "provider.failed",
                ProviderFailure(
                    **trace,
                    dispatched=True,
                    occurred_at=occurred_at,
                    completed_at=datetime.now(UTC),
                    error_category="RATE_LIMIT_RETRYABLE",
                    error_code="PROVIDER_RATE_LIMITED",
                    retryable=True,
                    retry_after_seconds=5,
                    usage_status="UNAVAILABLE",
                    usage=unavailable_usage(),
                ),
            )
            return

        if scenario == "MOCK_ERROR_MID_STREAM":
            for part in (
                "[MOCK] Partial stream chunk 1. ",
                "Partial stream chunk 2 before failure.",
            ):
                await asyncio.sleep(0)
                yield "assistant.delta", AssistantDelta(**trace, text=part)
            yield (
                "provider.failed",
                ProviderFailure(
                    **trace,
                    dispatched=True,
                    occurred_at=occurred_at,
                    completed_at=datetime.now(UTC),
                    error_category="TRANSIENT_RETRYABLE",
                    error_code="PROVIDER_UNAVAILABLE",
                    retryable=True,
                    retry_after_seconds=None,
                    usage_status="UNAVAILABLE",
                    usage=unavailable_usage(),
                ),
            )
            return

        summary_suffix = _format_context_summary(resolved_prompt)
        if scenario == "MOCK_SLOW_STREAM":
            delay = self.slow_chunk_delay_seconds
            if ctx is not None and ctx.slow_delay_ms is not None:
                delay = ctx.slow_delay_ms / 1000.0
            chunks = [
                "[MOCK] ",
                "Slow stream chunk 1. ",
                "Slow stream chunk 2. ",
                "Slow stream chunk 3. ",
                "Slow stream chunk 4. ",
                summary_suffix,
            ]
            for part in chunks:
                await asyncio.sleep(delay)
                yield "assistant.delta", AssistantDelta(**trace, text=part)
        else:
            chunks = ["[MOCK] ContextFlow active — ", summary_suffix]
            for part in chunks:
                await asyncio.sleep(0)
                yield "assistant.delta", AssistantDelta(**trace, text=part)

        full_text = "".join(chunks)
        usage_status: UsageStatus = "PARTIAL" if scenario == "MOCK_PARTIAL_USAGE" else "UNAVAILABLE"
        usage = partial_usage() if scenario == "MOCK_PARTIAL_USAGE" else unavailable_usage()
        yield (
            "provider.completed",
            ProviderCompleted(
                **trace,
                assistant_text=full_text,
                native_output_steps=[{"type": "text", "text": full_text}],
                finish_reason="STOP",
                service_tier=None,
                completed_at=datetime.now(UTC),
                usage_status=usage_status,
                usage=usage,
            ),
        )

    def generate(self, request: GenerateInput) -> GenerateResult:
        resolved_prompt = get_current_built_prompt() or build_prompt(request.prompt)
        ctx = get_mock_request_context()
        scenario = _detect_scenario([_prompt_text(request.prompt)], ctx)

        if scenario == "INVALID_KEY":
            raise MockScenarioFailure(
                error_code="INVALID_API_KEY",
                error_category="CONFIGURATION_BLOCKED",
                dispatched=False,
                occurred_at=None,
                retryable=False,
            )

        now = datetime.now(UTC)
        if scenario == "MOCK_429":
            raise MockScenarioFailure(
                error_code="PROVIDER_RATE_LIMITED",
                error_category="RATE_LIMIT_RETRYABLE",
                dispatched=True,
                occurred_at=now,
                retryable=True,
                retry_after_seconds=5,
            )

        if scenario == "MOCK_ERROR_MID_STREAM":
            raise MockScenarioFailure(
                error_code="PROVIDER_UNAVAILABLE",
                error_category="TRANSIENT_RETRYABLE",
                dispatched=True,
                occurred_at=now,
                retryable=True,
            )

        text = f"[MOCK] ContextFlow generate — {_format_context_summary(resolved_prompt)}"
        usage_status: UsageStatus = "PARTIAL" if scenario == "MOCK_PARTIAL_USAGE" else "UNAVAILABLE"
        usage = partial_usage() if scenario == "MOCK_PARTIAL_USAGE" else unavailable_usage()
        return GenerateResult(
            correlation_id=request.correlation_id,
            provider_call_id=request.provider_call_id,
            dispatched=True,
            occurred_at=now,
            completed_at=now,
            model=request.provider_options.model,
            provider_request_id=None,
            output_text=text,
            parsed_json={"summary": text} if request.response_schema is not None else None,
            native_output_steps=[{"type": "text", "text": text}],
            finish_reason="STOP",
            service_tier=None,
            usage_status=usage_status,
            usage=usage,
        )

    def embeddings(self, request: EmbeddingInput) -> EmbeddingResult:
        ctx = get_mock_request_context()
        scenario = _detect_scenario(request.texts, ctx)
        if scenario == "INVALID_KEY":
            raise MockScenarioFailure(
                error_code="INVALID_API_KEY",
                error_category="CONFIGURATION_BLOCKED",
                dispatched=False,
                occurred_at=None,
                retryable=False,
            )

        now = datetime.now(UTC)
        if scenario == "MOCK_429":
            raise MockScenarioFailure(
                error_code="PROVIDER_RATE_LIMITED",
                error_category="RATE_LIMIT_RETRYABLE",
                dispatched=True,
                occurred_at=now,
                retryable=True,
                retry_after_seconds=5,
            )

        vectors = []
        for text in request.texts:
            digest = hashlib.sha256(text.encode("utf-8")).digest()
            vectors.append([(digest[i % len(digest)] - 127.5) / 127.5 for i in range(1536)])
        usage_status: UsageStatus = "PARTIAL" if scenario == "MOCK_PARTIAL_USAGE" else "UNAVAILABLE"
        usage = partial_usage() if scenario == "MOCK_PARTIAL_USAGE" else unavailable_usage()
        return EmbeddingResult(
            correlation_id=request.correlation_id,
            provider_call_id=request.provider_call_id,
            dispatched=True,
            occurred_at=now,
            completed_at=now,
            model=request.model,
            provider_request_id=None,
            vectors=vectors,
            usage_status=usage_status,
            usage=usage,
        )

    def verify(self, request: VerifyInput, credential: SecretStr) -> VerifyResult:
        ctx = get_mock_request_context()
        scenario = _detect_scenario([], ctx)
        valid = (
            scenario != "INVALID_KEY"
            and credential.get_secret_value() == "mock-provider-credential"
        )
        return VerifyResult(
            correlation_id=request.correlation_id,
            provider_call_id=request.provider_call_id,
            valid=valid,
            checked_at=datetime.now(UTC),
            error_code=None if valid else "INVALID_API_KEY",
            capability_status="NOT_TESTED",
        )
