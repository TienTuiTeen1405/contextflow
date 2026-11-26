from datetime import UTC, datetime
from uuid import UUID

from fastapi import HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.providers.mock_adapter import unavailable_usage
from app.schemas.contracts import ErrorCategory, InternalError, ProviderFailure


def failure_payload(
    code: str,
    body: object = None,
    *,
    dispatched: bool = False,
    occurred_at: datetime | None = None,
    category: ErrorCategory = "PERMANENT_INPUT_ERROR",
    retryable: bool = False,
    retry_after_seconds: int | None = None,
) -> ProviderFailure:
    trace: dict[str, UUID] = {}
    for key in ("correlation_id", "provider_call_id"):
        value = body.get(key) if isinstance(body, dict) else None
        try:
            trace[key] = UUID(str(value))
        except ValueError:
            trace[key] = UUID(int=0)
    return ProviderFailure(
        **trace,
        dispatched=dispatched,
        occurred_at=occurred_at,
        completed_at=datetime.now(UTC),
        error_category=category,
        error_code=code,
        retryable=retryable,
        retry_after_seconds=retry_after_seconds,
        usage_status="UNAVAILABLE",
        usage=unavailable_usage(),
    )


def failure_response(
    status: int,
    code: str,
    body: object = None,
    *,
    dispatched: bool = False,
    occurred_at: datetime | None = None,
    category: ErrorCategory = "PERMANENT_INPUT_ERROR",
    retryable: bool = False,
    retry_after_seconds: int | None = None,
) -> JSONResponse:
    failure = failure_payload(
        code,
        body,
        dispatched=dispatched,
        occurred_at=occurred_at,
        category=category,
        retryable=retryable,
        retry_after_seconds=retry_after_seconds,
    )
    return JSONResponse(
        status_code=status,
        content=InternalError(error=failure).model_dump(mode="json"),
        headers={"X-Provider-Mode": "MOCK"},
    )


async def validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
    return failure_response(422, "INVALID_REQUEST", exc.body)


async def http_error(_: Request, exc: HTTPException) -> JSONResponse:
    return failure_response(exc.status_code, "REQUEST_REJECTED")
