import json
from collections.abc import AsyncIterator
from typing import Annotated, Any, Never

from fastapi import APIRouter, Depends, Header, Response
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
from app.providers.factory import get_provider
from app.schemas.contracts import (
    INTERNAL_EVENTS,
    EmbeddingInput,
    EmbeddingResult,
    GenerateInput,
    GenerateResult,
    InternalError,
    ProviderDispatched,
    StreamInput,
    VerifyInput,
    VerifyResult,
)
from app.security.service_auth import require_service_auth

router = APIRouter(
    prefix="/internal/v1/ai", tags=["internal-ai"], dependencies=[Depends(require_service_auth)]
)
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
    request: StreamInput,
    provider: Provider,
    credential: Credential,
    prompt_builder: Annotated[PromptBuilder, Depends(get_prompt_builder)],
) -> StreamingResponse | JSONResponse:
    iterator = None
    built_prompt = None
    token = None
    try:
        built_prompt = prompt_builder.build(request.prompt)
        token = set_current_built_prompt(built_prompt)
        iterator = provider.stream(request)
        event, payload = await anext(iterator)
        if event != "provider.dispatched":
            raise ValueError("Dispatch acknowledgement required")
        dispatched = ProviderDispatched.model_validate(payload.model_dump())
        if (
            dispatched.correlation_id != request.correlation_id
            or dispatched.provider_call_id != request.provider_call_id
        ):
            raise ValueError("Trace mismatch")
    except Exception:
        await close_stream(iterator)
        return failure_response(
            502, "ADAPTER_UNAVAILABLE", request.model_dump(), category="UNKNOWN_REQUIRES_REVIEW"
        )
    finally:
        if token is not None:
            reset_current_built_prompt(token)

    def frame(event: str, payload: BaseModel) -> str:
        data = json.dumps(payload.model_dump(mode="json"), ensure_ascii=False, allow_nan=False)
        return f"event: {event}\ndata: {data}\n\n"

    async def events() -> AsyncIterator[str]:
        assert iterator is not None
        assert built_prompt is not None
        stream_token = set_current_built_prompt(built_prompt)
        try:
            yield frame("provider.dispatched", dispatched)
            async for event, payload in iterator:
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
            raise ValueError("Missing terminal event")
        except Exception:
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
            reset_current_built_prompt(stream_token)
            await close_stream(iterator)

    return StreamingResponse(
        events(), media_type="text/event-stream", headers={"X-Provider-Mode": "MOCK"}
    )


@router.post("/generate", response_model=GenerateResult, responses=ERRORS)
def generate(
    request: GenerateInput,
    provider: Provider,
    credential: Credential,
    response: Response,
    prompt_builder: Annotated[PromptBuilder, Depends(get_prompt_builder)],
) -> GenerateResult | JSONResponse:
    if request.response_schema is not None:
        try:
            Draft202012Validator.check_schema(request.response_schema)
        except SchemaError:
            return failure_response(422, "INVALID_RESPONSE_SCHEMA", request.model_dump())
    token = None
    try:
        built_prompt = prompt_builder.build(request.prompt)
        token = set_current_built_prompt(built_prompt)
        result = provider.generate(request)
    except Exception:
        return failure_response(
            502, "ADAPTER_UNAVAILABLE", request.model_dump(), category="UNKNOWN_REQUIRES_REVIEW"
        )
    finally:
        if token is not None:
            reset_current_built_prompt(token)
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
        )
    response.headers["X-Provider-Mode"] = "MOCK"
    return result


@router.post("/embeddings", response_model=EmbeddingResult, responses=ERRORS)
def embeddings(
    request: EmbeddingInput, provider: Provider, credential: Credential, response: Response
) -> EmbeddingResult | JSONResponse:
    try:
        result = provider.embeddings(request)
    except Exception:
        return failure_response(
            502, "ADAPTER_UNAVAILABLE", request.model_dump(), category="UNKNOWN_REQUIRES_REVIEW"
        )
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
        )
    response.headers["X-Provider-Mode"] = "MOCK"
    return result


@router.post("/credentials/verify", response_model=VerifyResult, responses=ERRORS)
def verify(
    request: VerifyInput, provider: Provider, credential: Credential, response: Response
) -> VerifyResult | JSONResponse:
    response.headers["X-Provider-Mode"] = "MOCK"
    try:
        return provider.verify(request, credential)
    except Exception:
        return failure_response(
            502, "ADAPTER_UNAVAILABLE", request.model_dump(), category="UNKNOWN_REQUIRES_REVIEW"
        )
