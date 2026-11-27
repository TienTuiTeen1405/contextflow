"""Stateless Google Gemini Interactions & Embeddings provider adapter."""

import copy
import inspect
import logging
from collections.abc import AsyncIterator, Mapping
from datetime import UTC, datetime
from typing import Any

from google.genai import types
from pydantic import BaseModel, JsonValue, SecretStr

from app.prompt_builder import (
    BuiltPrompt,
    PromptBuilder,
    get_current_built_prompt,
    get_prompt_builder,
)
from app.providers.base import AdapterStatus
from app.providers.error_mapper import (
    AdapterOutputValidationError,
    ProviderExecutionError,
    ProviderStreamEventError,
    map_provider_exception,
    map_provider_usage,
    map_verify_exception,
    unavailable_usage,
)
from app.providers.gemini_client import (
    GeminiClientBuilder,
    async_request_scoped_gemini_client,
    create_gemini_client,
    get_current_provider_credential,
    request_scoped_gemini_client,
)
from app.providers.validators import parse_and_validate_generate_json, validate_embedding_vectors
from app.schemas.contracts import (
    AssistantDelta,
    EmbeddingInput,
    EmbeddingResult,
    EmbeddingTask,
    GenerateInput,
    GenerateResult,
    NativeStep,
    ProviderCompleted,
    ProviderDispatched,
    ProviderFailure,
    StreamInput,
    VerifyInput,
    VerifyResult,
)

logger = logging.getLogger(__name__)
DEFAULT_EMBEDDING_TIMEOUT_SECONDS = 30.0
DEFAULT_VERIFY_TIMEOUT_SECONDS = 15.0


def _read_field(obj: object, *names: str) -> Any:
    for name in names:
        if isinstance(obj, Mapping) and name in obj:
            return obj[name]
        if hasattr(obj, name):
            return getattr(obj, name)
    return None


def _dump_step_opaque(step: object) -> NativeStep:
    if hasattr(step, "model_dump"):
        dumped = step.model_dump(mode="json", exclude_none=True)
        if isinstance(dumped, dict):
            return copy.deepcopy(dumped)
    if isinstance(step, dict):
        return copy.deepcopy(step)
    raise ValueError(f"Unsupported native step type: {type(step).__name__}")


def _build_interactions_input(built_prompt: BuiltPrompt) -> list[dict[str, Any]]:
    steps: list[dict[str, Any]] = []
    for turn in built_prompt.input_turns:
        if turn.role == "user":
            if turn.content is not None:
                steps.append(
                    {
                        "type": "user_input",
                        "content": [{"type": "text", "text": turn.content}],
                    }
                )
        elif turn.role == "model":
            for native_step in turn.native_output_steps:
                steps.append(copy.deepcopy(native_step))
    return steps


def _extract_model_output_text(steps: list[NativeStep]) -> str:
    parts: list[str] = []
    for step in steps:
        if step.get("type") == "model_output":
            content = step.get("content")
            if isinstance(content, list):
                for item in content:
                    if isinstance(item, dict) and item.get("type") == "text":
                        text = item.get("text")
                        if isinstance(text, str):
                            parts.append(text)
    return "".join(parts)


_STREAM_ERROR_LABEL_ALIASES: dict[str, str] = {
    "SERVICE_UNAVAILABLE": "UNAVAILABLE",
    "INTERNAL_ERROR": "INTERNAL",
    "SERVER_ERROR": "INTERNAL",
    "TIMEOUT": "DEADLINE_EXCEEDED",
    "REQUEST_TIMEOUT": "DEADLINE_EXCEEDED",
    "RATE_LIMIT_EXCEEDED": "RESOURCE_EXHAUSTED",
    "TOO_MANY_REQUESTS": "RESOURCE_EXHAUSTED",
}


def _build_stream_event_error(event: object, default_message: str) -> ProviderStreamEventError:
    err_obj = _read_field(event, "error")
    raw_code = _read_field(err_obj, "code") if err_obj is not None else None
    if raw_code is None:
        raw_code = _read_field(event, "code")
    raw_status = _read_field(err_obj, "status") if err_obj is not None else None
    if raw_status is None:
        raw_status = _read_field(event, "status")
    raw_message = _read_field(err_obj, "message") if err_obj is not None else None
    if raw_message is None:
        raw_message = _read_field(event, "message")

    status_code: int | None = None
    status_label = ""

    if isinstance(raw_code, int) and not isinstance(raw_code, bool):
        status_code = raw_code
    elif isinstance(raw_code, str) and raw_code.strip():
        stripped = raw_code.strip()
        if stripped.isdigit():
            status_code = int(stripped)
        else:
            normalized = stripped.upper()
            status_label = _STREAM_ERROR_LABEL_ALIASES.get(normalized, normalized)

    if isinstance(raw_status, str) and raw_status.strip():
        normalized_status = raw_status.strip().upper()
        status_label = _STREAM_ERROR_LABEL_ALIASES.get(normalized_status, normalized_status)

    details: object = None
    if err_obj is not None:
        if hasattr(err_obj, "model_dump"):
            details = err_obj.model_dump(mode="json", exclude_none=True)
        elif isinstance(err_obj, Mapping):
            details = copy.deepcopy(dict(err_obj))
        else:
            details = str(err_obj)

    message = (
        str(raw_message)
        if isinstance(raw_message, str) and raw_message.strip()
        else default_message
    )
    return ProviderStreamEventError(
        message,
        status_code=status_code,
        status_label=status_label,
        details=details,
    )


def _merge_text_delta_into_blocks(
    existing_blocks: object, delta_text: str, *, field_name: str
) -> list[JsonValue]:
    if existing_blocks is None:
        return [{"type": "text", "text": delta_text}]
    if not isinstance(existing_blocks, list):
        raise AdapterOutputValidationError(
            "INVALID_ADAPTER_OUTPUT",
            f"Cannot reconstruct opaque stream step: non-list {field_name} field",
        )
    merged: list[JsonValue] = copy.deepcopy(existing_blocks)
    for block in reversed(merged):
        if isinstance(block, dict) and block.get("type") == "text":
            prior_text = block.get("text")
            prefix = prior_text if isinstance(prior_text, str) else ""
            block["text"] = f"{prefix}{delta_text}"
            return merged
    merged.append({"type": "text", "text": delta_text})
    return merged


def _reconstruct_stream_steps(
    completed_interaction: object,
    started_steps: dict[int, dict[str, Any]],
    text_deltas: dict[int, list[str]],
    thought_deltas: dict[int, list[str]],
    thought_signatures: dict[int, str],
    *,
    unreconstructable_steps: set[int] | None = None,
) -> list[NativeStep]:
    raw_steps = _read_field(completed_interaction, "steps")
    if isinstance(raw_steps, list) and raw_steps:
        return [_dump_step_opaque(step) for step in raw_steps]

    delta_indices = set(text_deltas) | set(thought_deltas) | set(thought_signatures)
    missing_step_starts = delta_indices - set(started_steps)
    if unreconstructable_steps or missing_step_starts:
        raise AdapterOutputValidationError(
            "INVALID_ADAPTER_OUTPUT",
            "Completed interaction omitted native steps and opaque stream deltas cannot be "
            "reconstructed losslessly",
        )

    reconstructed: list[NativeStep] = []
    for index in sorted(started_steps):
        step: NativeStep = copy.deepcopy(started_steps[index])
        step_type = step.get("type")
        if step_type == "model_output" and index in text_deltas:
            step["content"] = _merge_text_delta_into_blocks(
                step.get("content"),
                "".join(text_deltas[index]),
                field_name="content",
            )
        elif step_type == "thought":
            if index in thought_signatures:
                step["signature"] = thought_signatures[index]
            if index in thought_deltas:
                step["summary"] = _merge_text_delta_into_blocks(
                    step.get("summary"),
                    "".join(thought_deltas[index]),
                    field_name="summary",
                )
        reconstructed.append(step)
    return reconstructed


def _format_embedding_text(task_type: EmbeddingTask, text: str) -> str:
    if task_type == "QUERY_EMBEDDING":
        return f"task: search result | query: {text}"
    return f"title: none | text: {text}"


async def _close_raw_stream(raw_stream: object) -> None:
    for method_name in ("aclose", "close"):
        closer = getattr(raw_stream, method_name, None)
        if closer is not None:
            try:
                res = closer()
                if inspect.isawaitable(res):
                    await res
            except Exception:
                pass
            return


class GeminiInteractionsAdapter:
    """Request-scoped adapter executing Google Gemini Interactions & Embeddings calls."""

    def __init__(
        self,
        *,
        client_factory: GeminiClientBuilder = create_gemini_client,
        prompt_builder: PromptBuilder | None = None,
        api_version: str | None = None,
    ) -> None:
        self._client_factory = client_factory
        self._prompt_builder = prompt_builder or get_prompt_builder()
        self._api_version = api_version

    def status(self) -> AdapterStatus:
        return AdapterStatus(mode="GEMINI_INTERACTIONS", external_calls_enabled=True)

    async def stream(self, request: StreamInput) -> AsyncIterator[tuple[str, BaseModel]]:
        trace = {
            "correlation_id": request.correlation_id,
            "provider_call_id": request.provider_call_id,
        }
        credential = get_current_provider_credential()
        if credential is None:
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

        if request.provider_options.api_interface != "INTERACTIONS":
            yield (
                "provider.failed",
                ProviderFailure(
                    **trace,
                    dispatched=False,
                    occurred_at=None,
                    completed_at=datetime.now(UTC),
                    error_category="CONFIGURATION_BLOCKED",
                    error_code="UNSUPPORTED_API_INTERFACE",
                    retryable=False,
                    retry_after_seconds=None,
                    usage_status="UNAVAILABLE",
                    usage=unavailable_usage(),
                ),
            )
            return

        built_prompt = get_current_built_prompt() or self._prompt_builder.build(request.prompt)
        body: dict[str, Any] = {
            "model": request.provider_options.model,
            "system_instruction": built_prompt.system_instruction,
            "input": _build_interactions_input(built_prompt),
            "store": False,
            "stream": True,
        }
        if request.provider_options.max_output_tokens is not None:
            body["generation_config"] = {
                "max_output_tokens": request.provider_options.max_output_tokens
            }

        dispatched_sent = False
        occurred_at: datetime | None = None
        provider_request_id: str | None = None
        raw_stream: Any = None
        started_steps: dict[int, dict[str, Any]] = {}
        text_deltas: dict[int, list[str]] = {}
        thought_deltas: dict[int, list[str]] = {}
        thought_signatures: dict[int, str] = {}
        unreconstructable_steps: set[int] = set()
        emitted_chunks: list[str] = []
        completed_interaction: object = None

        async with async_request_scoped_gemini_client(
            credential,
            factory=self._client_factory,
            timeout_seconds=request.provider_options.timeout_seconds,
            api_version=self._api_version,
        ) as client:
            try:
                raw_stream = await client.aio.interactions.create(**body)
                async for event in raw_stream:
                    etype = str(_read_field(event, "event_type") or "")
                    if etype == "error":
                        raise _build_stream_event_error(
                            event, "Provider emitted stream error event"
                        )

                    if etype == "interaction.created":
                        created_obj = _read_field(event, "interaction")
                        raw_id = _read_field(created_obj, "id")
                        if isinstance(raw_id, str) and raw_id:
                            provider_request_id = raw_id
                        if not dispatched_sent:
                            occurred_at = datetime.now(UTC)
                            dispatched_sent = True
                            yield (
                                "provider.dispatched",
                                ProviderDispatched(
                                    **trace,
                                    occurred_at=occurred_at,
                                    provider_request_id=provider_request_id,
                                ),
                            )
                        continue

                    if etype == "interaction.status_update":
                        status_val = str(_read_field(event, "status") or "").lower()
                        if status_val in {"failed", "cancelled"}:
                            raise _build_stream_event_error(
                                event, f"Interaction ended with status={status_val}"
                            )
                        continue

                    if etype == "interaction.cancelled":
                        raise _build_stream_event_error(event, "Interaction cancelled by provider")

                    if not dispatched_sent:
                        occurred_at = datetime.now(UTC)
                        dispatched_sent = True
                        yield (
                            "provider.dispatched",
                            ProviderDispatched(
                                **trace,
                                occurred_at=occurred_at,
                                provider_request_id=provider_request_id,
                            ),
                        )

                    if etype == "step.start":
                        idx = int(_read_field(event, "index") or 0)
                        started_steps[idx] = _dump_step_opaque(_read_field(event, "step") or {})
                    elif etype == "step.delta":
                        idx = int(_read_field(event, "index") or 0)
                        delta = _read_field(event, "delta")
                        dtype = _read_field(delta, "type")
                        if dtype == "text":
                            chunk = _read_field(delta, "text")
                            if isinstance(chunk, str) and chunk:
                                emitted_chunks.append(chunk)
                                text_deltas.setdefault(idx, []).append(chunk)
                                yield "assistant.delta", AssistantDelta(**trace, text=chunk)
                            if _read_field(delta, "annotations") is not None:
                                unreconstructable_steps.add(idx)
                        elif dtype == "thought_summary":
                            content = _read_field(delta, "content")
                            t_val = _read_field(content, "text")
                            if isinstance(t_val, str) and t_val:
                                thought_deltas.setdefault(idx, []).append(t_val)
                            elif content is not None:
                                unreconstructable_steps.add(idx)
                        elif dtype == "thought_signature":
                            sig = _read_field(delta, "signature")
                            if isinstance(sig, str) and sig:
                                thought_signatures[idx] = sig
                        elif dtype is not None:
                            unreconstructable_steps.add(idx)
                    elif etype == "interaction.completed":
                        completed_interaction = _read_field(event, "interaction")
                        break

                if completed_interaction is None:
                    raise RuntimeError("Stream ended before interaction.completed")

                native_steps = _reconstruct_stream_steps(
                    completed_interaction,
                    started_steps,
                    text_deltas,
                    thought_deltas,
                    thought_signatures,
                    unreconstructable_steps=unreconstructable_steps,
                )
                assistant_text = "".join(emitted_chunks)
                if not assistant_text:
                    fallback_text = _extract_model_output_text(native_steps)
                    if fallback_text:
                        assistant_text = fallback_text
                        yield "assistant.delta", AssistantDelta(**trace, text=fallback_text)

                usage_status, usage = map_provider_usage(
                    _read_field(completed_interaction, "usage")
                )
                raw_status = _read_field(completed_interaction, "status")
                finish_reason = (
                    "STOP" if raw_status in (None, "completed") else str(raw_status).upper()
                )
                raw_tier = _read_field(completed_interaction, "service_tier")
                service_tier = str(raw_tier) if raw_tier is not None else None

                yield (
                    "provider.completed",
                    ProviderCompleted(
                        **trace,
                        assistant_text=assistant_text,
                        native_output_steps=native_steps,
                        finish_reason=finish_reason,
                        service_tier=service_tier,
                        completed_at=datetime.now(UTC),
                        usage_status=usage_status,
                        usage=usage,
                    ),
                )
            except Exception as exc:
                mapped = map_provider_exception(exc)
                logger.warning(
                    "Gemini stream failed dispatched=%s category=%s exc_type=%s",
                    dispatched_sent,
                    mapped.error_category,
                    type(exc).__name__,
                )
                yield (
                    "provider.failed",
                    ProviderFailure(
                        **trace,
                        dispatched=dispatched_sent,
                        occurred_at=occurred_at if dispatched_sent else None,
                        completed_at=datetime.now(UTC),
                        error_category=mapped.error_category,
                        error_code=mapped.error_code,
                        retryable=mapped.retryable,
                        retry_after_seconds=mapped.retry_after_seconds,
                        usage_status="UNAVAILABLE",
                        usage=unavailable_usage(),
                    ),
                )
            finally:
                if raw_stream is not None:
                    await _close_raw_stream(raw_stream)

    def generate(self, request: GenerateInput) -> GenerateResult:
        credential = get_current_provider_credential()
        if credential is None:
            raise ProviderExecutionError(
                error_code="INVALID_API_KEY",
                error_category="CONFIGURATION_BLOCKED",
                dispatched=False,
                occurred_at=None,
                retryable=False,
            )

        if request.provider_options.api_interface != "INTERACTIONS":
            raise ProviderExecutionError(
                error_code="UNSUPPORTED_API_INTERFACE",
                error_category="CONFIGURATION_BLOCKED",
                dispatched=False,
                occurred_at=None,
                retryable=False,
            )

        built_prompt = get_current_built_prompt() or self._prompt_builder.build(request.prompt)
        body: dict[str, Any] = {
            "model": request.provider_options.model,
            "system_instruction": built_prompt.system_instruction,
            "input": _build_interactions_input(built_prompt),
            "store": False,
            "stream": False,
        }
        if request.provider_options.max_output_tokens is not None:
            body["generation_config"] = {
                "max_output_tokens": request.provider_options.max_output_tokens
            }
        if request.response_schema is not None:
            body["response_format"] = request.response_schema

        occurred_at = datetime.now(UTC)
        with request_scoped_gemini_client(
            credential,
            factory=self._client_factory,
            timeout_seconds=request.provider_options.timeout_seconds,
            api_version=self._api_version,
        ) as client:
            try:
                interaction = client.interactions.create(**body)
            except Exception as exc:
                mapped = map_provider_exception(exc)
                dispatched = mapped.error_category not in {
                    "CONFIGURATION_BLOCKED",
                    "PERMANENT_INPUT_ERROR",
                }
                raise ProviderExecutionError(
                    error_code=mapped.error_code,
                    error_category=mapped.error_category,
                    dispatched=dispatched,
                    occurred_at=occurred_at if dispatched else None,
                    retryable=mapped.retryable,
                    retry_after_seconds=mapped.retry_after_seconds,
                ) from exc

        completed_at = datetime.now(UTC)
        raw_steps = _read_field(interaction, "steps") or []
        native_steps = [_dump_step_opaque(step) for step in raw_steps]
        raw_output_text = _read_field(interaction, "output_text")
        output_text = (
            raw_output_text
            if isinstance(raw_output_text, str) and raw_output_text
            else _extract_model_output_text(native_steps)
        )
        usage_status, usage = map_provider_usage(_read_field(interaction, "usage"))

        try:
            parsed_json: dict[str, JsonValue] | list[JsonValue] | None = (
                parse_and_validate_generate_json(output_text, request.response_schema)
            )
        except Exception as exc:
            mapped = map_provider_exception(exc)
            raise ProviderExecutionError(
                error_code=mapped.error_code,
                error_category=mapped.error_category,
                dispatched=True,
                occurred_at=occurred_at,
                retryable=False,
                usage_status=usage_status,
                usage=usage,
            ) from exc

        raw_id = _read_field(interaction, "id")
        raw_status = _read_field(interaction, "status")
        finish_reason = "STOP" if raw_status in (None, "completed") else str(raw_status).upper()
        raw_tier = _read_field(interaction, "service_tier")
        raw_model = _read_field(interaction, "model")

        return GenerateResult(
            correlation_id=request.correlation_id,
            provider_call_id=request.provider_call_id,
            dispatched=True,
            occurred_at=occurred_at,
            completed_at=completed_at,
            model=str(raw_model) if raw_model else request.provider_options.model,
            provider_request_id=str(raw_id) if raw_id else None,
            output_text=output_text,
            parsed_json=parsed_json,
            native_output_steps=native_steps,
            finish_reason=finish_reason,
            service_tier=str(raw_tier) if raw_tier is not None else None,
            usage_status=usage_status,
            usage=usage,
        )

    def embeddings(self, request: EmbeddingInput) -> EmbeddingResult:
        credential = get_current_provider_credential()
        if credential is None:
            raise ProviderExecutionError(
                error_code="INVALID_API_KEY",
                error_category="CONFIGURATION_BLOCKED",
                dispatched=False,
                occurred_at=None,
                retryable=False,
            )

        occurred_at = datetime.now(UTC)
        formatted_contents: list[types.Content] = [
            types.Content(parts=[types.Part(text=_format_embedding_text(request.task_type, text))])
            for text in request.texts
        ]

        with request_scoped_gemini_client(
            credential,
            factory=self._client_factory,
            timeout_seconds=DEFAULT_EMBEDDING_TIMEOUT_SECONDS,
            api_version=self._api_version,
        ) as client:
            try:
                resp = client.models.embed_content(
                    model=request.model,
                    contents=formatted_contents,
                    config=types.EmbedContentConfig(
                        output_dimensionality=request.output_dimensionality
                    ),
                )
            except Exception as exc:
                mapped = map_provider_exception(exc)
                dispatched = mapped.error_category not in {
                    "CONFIGURATION_BLOCKED",
                    "PERMANENT_INPUT_ERROR",
                }
                raise ProviderExecutionError(
                    error_code=mapped.error_code,
                    error_category=mapped.error_category,
                    dispatched=dispatched,
                    occurred_at=occurred_at if dispatched else None,
                    retryable=mapped.retryable,
                    retry_after_seconds=mapped.retry_after_seconds,
                ) from exc

        embeddings_list = _read_field(resp, "embeddings") or []
        raw_vectors: list[list[object]] = [
            list(_read_field(item, "values") or []) for item in embeddings_list
        ]
        usage_status, usage = map_provider_usage(_read_field(resp, "usage_metadata"))
        try:
            validated_vectors = validate_embedding_vectors(
                raw_vectors,
                expected_count=len(request.texts),
                expected_dim=request.output_dimensionality,
            )
        except Exception as exc:
            mapped = map_provider_exception(exc)
            raise ProviderExecutionError(
                error_code=mapped.error_code,
                error_category=mapped.error_category,
                dispatched=True,
                occurred_at=occurred_at,
                retryable=False,
                usage_status=usage_status,
                usage=usage,
            ) from exc

        return EmbeddingResult(
            correlation_id=request.correlation_id,
            provider_call_id=request.provider_call_id,
            dispatched=True,
            occurred_at=occurred_at,
            completed_at=datetime.now(UTC),
            model=request.model,
            provider_request_id=None,
            vectors=validated_vectors,
            usage_status=usage_status,
            usage=usage,
        )

    def verify(self, request: VerifyInput, credential: SecretStr) -> VerifyResult:
        checked_at = datetime.now(UTC)
        with request_scoped_gemini_client(
            credential,
            factory=self._client_factory,
            timeout_seconds=DEFAULT_VERIFY_TIMEOUT_SECONDS,
            api_version=self._api_version,
        ) as client:
            try:
                pager = client.models.list(config=types.ListModelsConfig(page_size=1))
                next(iter(pager), None)
                return VerifyResult(
                    correlation_id=request.correlation_id,
                    provider_call_id=request.provider_call_id,
                    valid=True,
                    checked_at=checked_at,
                    error_code=None,
                    capability_status="NOT_TESTED",
                )
            except Exception as exc:
                error_code = map_verify_exception(exc)
                logger.warning(
                    "Gemini credential verify failed error_code=%s exc_type=%s",
                    error_code,
                    type(exc).__name__,
                )
                return VerifyResult(
                    correlation_id=request.correlation_id,
                    provider_call_id=request.provider_call_id,
                    valid=False,
                    checked_at=checked_at,
                    error_code=error_code,
                    capability_status="NOT_TESTED",
                )
