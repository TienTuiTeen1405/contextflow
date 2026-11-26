import asyncio
import hashlib
from collections.abc import AsyncIterator
from datetime import UTC, datetime

from pydantic import BaseModel, SecretStr

from app.providers.base import AdapterStatus
from app.schemas.contracts import (
    AssistantDelta,
    EmbeddingInput,
    EmbeddingResult,
    GenerateInput,
    GenerateResult,
    ProviderCompleted,
    ProviderDispatched,
    StreamInput,
    Usage,
    VerifyInput,
    VerifyResult,
)


def unavailable_usage() -> Usage:
    return Usage(
        input_tokens=None,
        output_tokens=None,
        total_tokens=None,
        cached_tokens=None,
        reasoning_tokens=None,
    )


class MockProviderAdapter:
    """Synthetic local attempts. No network, SDK, real-key checks or usage estimates."""

    def status(self) -> AdapterStatus:
        return AdapterStatus(mode="MOCK", external_calls_enabled=False)

    async def stream(self, request: StreamInput) -> AsyncIterator[tuple[str, BaseModel]]:
        trace = {
            "correlation_id": request.correlation_id,
            "provider_call_id": request.provider_call_id,
        }
        yield (
            "provider.dispatched",
            ProviderDispatched(**trace, occurred_at=datetime.now(UTC), provider_request_id=None),
        )
        text = "[MOCK] Hợp đồng streaming hoạt động."
        for part in ["[MOCK] ", "Hợp đồng streaming hoạt động."]:
            await asyncio.sleep(0)
            yield "assistant.delta", AssistantDelta(**trace, text=part)
        yield (
            "provider.completed",
            ProviderCompleted(
                **trace,
                assistant_text=text,
                native_output_steps=[{"type": "text", "text": text}],
                finish_reason="STOP",
                service_tier=None,
                completed_at=datetime.now(UTC),
                usage_status="UNAVAILABLE",
                usage=unavailable_usage(),
            ),
        )

    def generate(self, request: GenerateInput) -> GenerateResult:
        now = datetime.now(UTC)
        text = "[MOCK] Kết quả tác vụ."
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
            usage_status="UNAVAILABLE",
            usage=unavailable_usage(),
        )

    def embeddings(self, request: EmbeddingInput) -> EmbeddingResult:
        now = datetime.now(UTC)
        vectors = []
        for text in request.texts:
            digest = hashlib.sha256(text.encode("utf-8")).digest()
            vectors.append([(digest[i % len(digest)] - 127.5) / 127.5 for i in range(1536)])
        return EmbeddingResult(
            correlation_id=request.correlation_id,
            provider_call_id=request.provider_call_id,
            dispatched=True,
            occurred_at=now,
            completed_at=now,
            model=request.model,
            provider_request_id=None,
            vectors=vectors,
            usage_status="UNAVAILABLE",
            usage=unavailable_usage(),
        )

    def verify(self, request: VerifyInput, credential: SecretStr) -> VerifyResult:
        valid = credential.get_secret_value() == "mock-provider-credential"
        return VerifyResult(
            correlation_id=request.correlation_id,
            provider_call_id=request.provider_call_id,
            valid=valid,
            checked_at=datetime.now(UTC),
            error_code=None if valid else "INVALID_API_KEY",
            capability_status="NOT_TESTED",
        )
