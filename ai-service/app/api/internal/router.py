import asyncio
import json
import logging
from collections.abc import AsyncIterator
from typing import Annotated, Any, Never

from fastapi import APIRouter, Depends, Header, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse
from jsonschema import Draft202012Validator, SchemaError
from pydantic import BaseModel, SecretStr
from referencing import Registry
from referencing.exceptions import NoSuchResource

from app.api.internal.errors import failure_payload, failure_response
from app.prompt_builder import (
    PromptBuilder,
    get_prompt_builder,
    reset_current_built_prompt,
    set_current_built_prompt,
)
from app.providers.base import AdapterStatus, ProviderAdapter
from app.providers.error_mapper import ProviderExecutionError
from app.providers.factory import get_provider
from app.providers.gemini_client import (
    reset_current_provider_credential,
    set_current_provider_credential,
)
from app.providers.mock_adapter import (
    MockRequestContext,
    reset_mock_request_context,
    set_mock_request_context,
)
from app.schemas.contracts import (
    INTERNAL_EVENTS,
    EmbeddingInput,
    EmbeddingResult,
    GenerateInput,
    GenerateResult,
    InternalError,
    ProviderDispatched,
    ProviderFailure,
    StreamInput,
    VerifyInput,
    VerifyResult,
)
from app.security.service_auth import require_service_auth

router = APIRouter(
    prefix="/internal/v1/ai", tags=["internal-ai"], dependencies=[Depends(require_service_auth)]
)
logger = logging.getLogger(__name__)
Provider = Annotated[ProviderAdapter, Depends(get_provider)]


def provider_credential(
    value: Annotated[str, Header(alias="X-Provider-Credential", min_length=1)],
) -> SecretStr:
    return SecretStr(value)


Credential = Annotated[SecretStr, Depends(provider_credential)]


async def close_stream(iterator: AsyncIterator[tuple[str, BaseModel]] | None) -> None:
    close = getattr(iterator, "aclose", None)
    if close is not None:
        try:
            await close()
        except Exception:
            # Cleanup must not expose provider exception text or append a second terminal event.
            pass


def no_remote_schema(uri: str) -> Never:
    raise NoSuchResource(uri)


# attrs generates the keyword-only constructor; runtime and contract tests cover this binding.
SCHEMA_REGISTRY: Registry[Any] = Registry(retrieve=no_remote_schema)  # type: ignore[call-arg]


ERRORS: dict[int | str, dict[str, Any]] = {
    code: {"model": InternalError, "description": "Safe normalized internal failure"}
    for code in (401, 403, 422, 502)
}


def _mock_context_from_request(http_request: Request, credential: SecretStr) -> MockRequestContext:
    raw_delay = http_request.headers.get("X-Mock-Slow-Delay-Ms")
    slow_delay_ms: int | None = None
    if raw_delay is not None:
        try:
            parsed = int(raw_delay)
            if 0 <= parsed <= 5000:
                slow_delay_ms = parsed
        except ValueError:
            slow_delay_ms = None
    return MockRequestContext(
        scenario_header=http_request.headers.get("X-Mock-Scenario"),
        credential_value=credential.get_secret_value(),
        slow_delay_ms=slow_delay_ms,
    )


@router.get("/status", response_model=AdapterStatus, include_in_schema=False)
def adapter_status(provider: Provider) -> AdapterStatus:
    return provider.status()


@router.post(
    "/streams",
    responses={**ERRORS, 200: {"content": {"text/event-stream": {"schema": {"type": "string"}}}}},
    response_class=StreamingResponse,
    response_model=None,
)
async def streams(
    http_request: Request,
    request: StreamInput,
    provider: Provider,
    credential: Credential,
    prompt_builder: Annotated[PromptBuilder, Depends(get_prompt_builder)],
) -> StreamingResponse | JSONResponse:
    iterator = None
    built_prompt = None
    prompt_token = None
    cred_token = None
    mock_ctx = _mock_context_from_request(http_request, credential)
    mock_token = None
    provider_mode = provider.status().mode
    dispatched: ProviderDispatched | None = None
    initial_failure: ProviderFailure | None = None
    try:
        built_prompt = prompt_builder.build(request.prompt)
        prompt_token = set_current_built_prompt(built_prompt)
        cred_token = set_current_provider_credential(credential)
        mock_token = set_mock_request_context(mock_ctx)
        iterator = provider.stream(request)
        event, payload = await anext(iterator)
        if event == "provider.dispatched":
            dispatched = ProviderDispatched.model_validate(payload.model_dump())
            if (
                dispatched.correlation_id != request.correlation_id
                or dispatched.provider_call_id != request.provider_call_id
            ):
                raise ValueError("Trace mismatch")
        elif event == "provider.failed":
            initial_failure = ProviderFailure.model_validate(payload.model_dump())
            if (
                initial_failure.correlation_id != request.correlation_id
                or initial_failure.provider_call_id != request.provider_call_id
            ):
                raise ValueError("Trace mismatch")
            if initial_failure.dispatched:
                raise ValueError("Initial provider.failed must have dispatched=False")
        else:
            raise ValueError("Dispatch acknowledgement or undispatched failure required")
    except Exception as exc:
        logger.warning(
            "Internal stream setup failed exc_type=%s",
            type(exc).__name__,
        )
        await close_stream(iterator)
        return failure_response(
            502,
            "ADAPTER_UNAVAILABLE",
            request.model_dump(),
            category="UNKNOWN_REQUIRES_REVIEW",
            provider_mode=provider_mode,
        )
    finally:
        if mock_token is not None:
            reset_mock_request_context(mock_token)
        if cred_token is not None:
            reset_current_provider_credential(cred_token)
        if prompt_token is not None:
            reset_current_built_prompt(prompt_token)

    def frame(event: str, payload: BaseModel) -> str:
        data = json.dumps(payload.model_dump(mode="json"), ensure_ascii=False, allow_nan=False)
        return f"event: {event}\ndata: {data}\n\n"

    async def events() -> AsyncIterator[str]:
        assert iterator is not None
        if initial_failure is not None:
            try:
                if await http_request.is_disconnected():
                    return
                yield frame("provider.failed", initial_failure)
            finally:
                await close_stream(iterator)
            return

        assert built_prompt is not None
        assert dispatched is not None
        stream_prompt_token = set_current_built_prompt(built_prompt)
        stream_cred_token = set_current_provider_credential(credential)
        stream_mock_token = set_mock_request_context(mock_ctx)
        try:
            if await http_request.is_disconnected():
                return
            yield frame("provider.dispatched", dispatched)
            while True:
                if await http_request.is_disconnected():
                    return
                try:
                    event, payload = await anext(iterator)
                except StopAsyncIteration:
                    raise ValueError("Missing terminal event") from None
                if await http_request.is_disconnected():
                    return
                if event == "provider.dispatched":
                    raise ValueError("Duplicate dispatch")
                validated = INTERNAL_EVENTS[event].model_validate(payload.model_dump())
                trace = validated.model_dump()
                if (
                    trace["correlation_id"] != request.correlation_id
                    or trace["provider_call_id"] != request.provider_call_id
                ):
                    raise ValueError("Trace mismatch")
                yield frame(event, validated)
                if event in {"provider.completed", "provider.failed"}:
                    return
        except asyncio.CancelledError:
            return
        except Exception as exc:
            logger.warning(
                "Internal stream failed mid-stream exc_type=%s",
                type(exc).__name__,
            )
            failed = failure_payload(
                "PROVIDER_UNAVAILABLE",
                request.model_dump(),
                dispatched=True,
                occurred_at=dispatched.occurred_at,
                category="TRANSIENT_RETRYABLE",
                retryable=True,
            )
            yield frame("provider.failed", failed)
        finally:
            reset_mock_request_context(stream_mock_token)
            reset_current_provider_credential(stream_cred_token)
            reset_current_built_prompt(stream_prompt_token)
            await close_stream(iterator)

    return StreamingResponse(
        events(), media_type="text/event-stream", headers={"X-Provider-Mode": provider_mode}
    )


@router.post("/generate", response_model=GenerateResult, responses=ERRORS)
def generate(
    http_request: Request,
    request: GenerateInput,
    provider: Provider,
    credential: Credential,
    response: Response,
    prompt_builder: Annotated[PromptBuilder, Depends(get_prompt_builder)],
) -> GenerateResult | JSONResponse:
    provider_mode = provider.status().mode
    if request.response_schema is not None:
        try:
            Draft202012Validator.check_schema(request.response_schema)
        except SchemaError:
            return failure_response(
                422,
                "INVALID_RESPONSE_SCHEMA",
                request.model_dump(),
                provider_mode=provider_mode,
            )
    prompt_token = None
    cred_token = None
    mock_token = None
    try:
        built_prompt = prompt_builder.build(request.prompt)
        prompt_token = set_current_built_prompt(built_prompt)
        cred_token = set_current_provider_credential(credential)
        mock_token = set_mock_request_context(_mock_context_from_request(http_request, credential))
        result = provider.generate(request)
    except ProviderExecutionError as exc:
        return failure_response(
            502,
            exc.error_code,
            request.model_dump(),
            dispatched=exc.dispatched,
            occurred_at=exc.occurred_at,
            category=exc.error_category,
            retryable=exc.retryable,
            retry_after_seconds=exc.retry_after_seconds,
            usage_status=exc.usage_status,
            usage=exc.usage,
            provider_mode=provider_mode,
        )
    except Exception as exc:
        logger.warning("Internal generate failed exc_type=%s", type(exc).__name__)
        return failure_response(
            502,
            "ADAPTER_UNAVAILABLE",
            request.model_dump(),
            category="UNKNOWN_REQUIRES_REVIEW",
            provider_mode=provider_mode,
        )
    finally:
        if mock_token is not None:
            reset_mock_request_context(mock_token)
        if cred_token is not None:
            reset_current_provider_credential(cred_token)
        if prompt_token is not None:
            reset_current_built_prompt(prompt_token)
    try:
        result = GenerateResult.model_validate(result.model_dump())
        if request.response_schema is not None:
            Draft202012Validator(request.response_schema, registry=SCHEMA_REGISTRY).validate(
                result.parsed_json
            )
    except Exception:
        return failure_response(
            502,
            "INVALID_ADAPTER_OUTPUT",
            request.model_dump(),
            dispatched=result.dispatched,
            occurred_at=result.occurred_at,
            provider_mode=provider_mode,
        )
    response.headers["X-Provider-Mode"] = provider_mode
    return result


@router.post("/embeddings", response_model=EmbeddingResult, responses=ERRORS)
def embeddings(
    http_request: Request,
    request: EmbeddingInput,
    provider: Provider,
    credential: Credential,
    response: Response,
) -> EmbeddingResult | JSONResponse:
    provider_mode = provider.status().mode
    cred_token = set_current_provider_credential(credential)
    mock_token = set_mock_request_context(_mock_context_from_request(http_request, credential))
    try:
        result = provider.embeddings(request)
    except ProviderExecutionError as exc:
        return failure_response(
            502,
            exc.error_code,
            request.model_dump(),
            dispatched=exc.dispatched,
            occurred_at=exc.occurred_at,
            category=exc.error_category,
            retryable=exc.retryable,
            retry_after_seconds=exc.retry_after_seconds,
            usage_status=exc.usage_status,
            usage=exc.usage,
            provider_mode=provider_mode,
        )
    except Exception as exc:
        logger.warning("Internal embeddings failed exc_type=%s", type(exc).__name__)
        return failure_response(
            502,
            "ADAPTER_UNAVAILABLE",
            request.model_dump(),
            category="UNKNOWN_REQUIRES_REVIEW",
            provider_mode=provider_mode,
        )
    finally:
        reset_mock_request_context(mock_token)
        reset_current_provider_credential(cred_token)
    try:
        result = EmbeddingResult.model_validate(result.model_dump())
        if len(result.vectors) != len(request.texts):
            raise ValueError("Vector count mismatch")
    except Exception:
        return failure_response(
            502,
            "INVALID_ADAPTER_OUTPUT",
            request.model_dump(),
            dispatched=result.dispatched,
            occurred_at=result.occurred_at,
            provider_mode=provider_mode,
        )
    response.headers["X-Provider-Mode"] = provider_mode
    return result


@router.post("/credentials/verify", response_model=VerifyResult, responses=ERRORS)
def verify(
    http_request: Request,
    request: VerifyInput,
    provider: Provider,
    credential: Credential,
    response: Response,
) -> VerifyResult | JSONResponse:
    provider_mode = provider.status().mode
    response.headers["X-Provider-Mode"] = provider_mode
    cred_token = set_current_provider_credential(credential)
    mock_token = set_mock_request_context(_mock_context_from_request(http_request, credential))
    try:
        return provider.verify(request, credential)
    except Exception as exc:
        logger.warning("Internal credential verify failed exc_type=%s", type(exc).__name__)
        return failure_response(
            502,
            "ADAPTER_UNAVAILABLE",
            request.model_dump(),
            category="UNKNOWN_REQUIRES_REVIEW",
            provider_mode=provider_mode,
        )
    finally:
        reset_mock_request_context(mock_token)
        reset_current_provider_credential(cred_token)
