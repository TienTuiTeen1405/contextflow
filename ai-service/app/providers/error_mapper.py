"""CF-DEC-042 error classification (7 categories) and Usage Null Semantics mapper."""

import asyncio
import math
import re
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime
from typing import Literal

import httpx
import httpx2
from google.genai import _interactions_errors as genai_interactions_errors
from google.genai import errors as genai_errors

from app.core.redaction import redact_text
from app.schemas.contracts import ErrorCategory, Usage, UsageStatus

VerifyErrorCode = Literal["INVALID_API_KEY", "PERMISSION_DENIED", "PROVIDER_UNAVAILABLE"]

_RETRY_DELAY_RE = re.compile(r"retry\s+(?:after|in)\s+(\d+(?:\.\d+)?)\s*s", re.IGNORECASE)
_JSON_RETRY_DELAY_RE = re.compile(r"\"retryDelay\"\s*:\s*\"(\d+(?:\.\d+)?)s\"", re.IGNORECASE)

_HARD_QUOTA_MARKERS = (
    "daily",
    "per day",
    "per_day",
    "rpd",
    "requests_per_day",
    "billing",
    "spend cap",
    "spend_cap",
    "hard quota",
    "plan and billing",
    "insufficient_quota",
    "free_tier",
    "quota_limit_value",
    "limit: 0",
)

_CONFIG_KEY_MARKERS = (
    "api_key_invalid",
    "api key not valid",
    "invalid api key",
    "api key expired",
    "expired api key",
    "permission_denied",
    "permission denied",
    "unauthenticated",
    "unauthorized",
    "model not found",
    "not found for api version",
    "is not supported for",
)

_SOURCE_INVALIDATED_MARKERS = (
    "source_invalidated",
    "cachedcontent",
    "cached_content",
    "cache not found",
    "cache expired",
    "file not found",
    "file has expired",
    "source deleted",
    "resource expired",
)


class AdapterOutputValidationError(ValueError):
    """Raised when provider output fails strict JSON, schema, or vector validation."""

    def __init__(self, error_code: str, message: str) -> None:
        super().__init__(redact_text(message))
        self.error_code = error_code


class SourceInvalidatedError(ValueError):
    """Raised when a referenced upstream source/cache revision is missing or expired."""

    def __init__(self, message: str = "Referenced provider resource invalidated") -> None:
        super().__init__(redact_text(message))


class ProviderStreamEventError(Exception):
    """Structured error payload received inside an upstream SSE stream event."""

    def __init__(
        self,
        message: str,
        *,
        status_code: int | None = None,
        status_label: str = "",
        details: object = None,
    ) -> None:
        super().__init__(redact_text(message))
        self.status_code = status_code
        self.status_label = status_label
        self.details = details


class ProviderExecutionError(Exception):
    """Normalized provider failure raised from synchronous adapter execution."""

    def __init__(
        self,
        *,
        error_code: str,
        error_category: ErrorCategory,
        dispatched: bool,
        occurred_at: datetime | None,
        retryable: bool,
        retry_after_seconds: int | None = None,
        usage_status: UsageStatus = "UNAVAILABLE",
        usage: Usage | None = None,
    ) -> None:
        super().__init__(error_code)
        self.error_code = error_code
        self.error_category = error_category
        self.dispatched = dispatched
        self.occurred_at = occurred_at
        self.retryable = retryable
        self.retry_after_seconds = retry_after_seconds
        self.usage_status = usage_status
        self.usage = usage or unavailable_usage()


@dataclass(frozen=True)
class MappedProviderError:
    """Normalized internal failure attributes adhering to CF-DEC-042."""

    error_category: ErrorCategory
    error_code: str
    retryable: bool
    retry_after_seconds: int | None
    safe_summary: str


def unavailable_usage() -> Usage:
    """Return an all-null Usage object preserving missing-data semantics."""
    return Usage(
        input_tokens=None,
        output_tokens=None,
        total_tokens=None,
        cached_tokens=None,
        reasoning_tokens=None,
    )


def _read_attr_or_key(obj: object, *names: str) -> object:
    for name in names:
        if isinstance(obj, Mapping) and name in obj:
            return obj[name]
        if hasattr(obj, name):
            return getattr(obj, name)
    return None


def _coerce_non_neg_int(value: object) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int) and value >= 0:
        return value
    return None


def map_provider_usage(usage_obj: object) -> tuple[UsageStatus, Usage]:
    """Map provider usage honoring Usage Null Semantics (never fill None with 0 or sum fields)."""
    if usage_obj is None:
        return "UNAVAILABLE", unavailable_usage()

    input_tokens = _coerce_non_neg_int(
        _read_attr_or_key(usage_obj, "total_input_tokens", "input_tokens", "prompt_token_count")
    )
    output_tokens = _coerce_non_neg_int(
        _read_attr_or_key(
            usage_obj, "total_output_tokens", "output_tokens", "candidates_token_count"
        )
    )
    total_tokens = _coerce_non_neg_int(
        _read_attr_or_key(usage_obj, "total_tokens", "total_token_count")
    )
    cached_tokens = _coerce_non_neg_int(
        _read_attr_or_key(
            usage_obj, "total_cached_tokens", "cached_tokens", "cached_content_token_count"
        )
    )
    reasoning_tokens = _coerce_non_neg_int(
        _read_attr_or_key(
            usage_obj, "total_thought_tokens", "reasoning_tokens", "thoughts_token_count"
        )
    )

    usage = Usage(
        input_tokens=input_tokens,
        output_tokens=output_tokens,
        total_tokens=total_tokens,
        cached_tokens=cached_tokens,
        reasoning_tokens=reasoning_tokens,
    )
    values = (input_tokens, output_tokens, total_tokens, cached_tokens, reasoning_tokens)
    if all(v is None for v in values):
        return "UNAVAILABLE", usage
    if all(v is not None for v in values):
        return "AVAILABLE", usage
    return "PARTIAL", usage


def _parse_retry_after_header(headers: Mapping[str, str] | None) -> int | None:
    if not headers:
        return None
    raw: str | None = None
    for key, val in headers.items():
        if key.lower() == "retry-after":
            raw = val
            break
    if raw is None:
        return None
    stripped = raw.strip()
    if stripped.isdigit():
        return max(0, int(stripped))
    try:
        target = parsedate_to_datetime(stripped)
        if target.tzinfo is None:
            target = target.replace(tzinfo=UTC)
        delta = (target - datetime.now(UTC)).total_seconds()
        return max(0, int(math.ceil(delta)))
    except (TypeError, ValueError):
        return None


def _extract_retry_after_seconds(
    headers: Mapping[str, str] | None, combined_text: str, details: object
) -> int | None:
    from_header = _parse_retry_after_header(headers)
    if from_header is not None:
        return from_header

    if isinstance(details, Mapping):
        raw_delay = _find_retry_delay_in_obj(details)
        if raw_delay is not None:
            return raw_delay

    for pattern in (_JSON_RETRY_DELAY_RE, _RETRY_DELAY_RE):
        match = pattern.search(combined_text)
        if match:
            return max(0, int(math.ceil(float(match.group(1)))))
    return None


def _find_retry_delay_in_obj(obj: object) -> int | None:
    if isinstance(obj, Mapping):
        delay = obj.get("retryDelay")
        if isinstance(delay, str) and delay.endswith("s"):
            try:
                return max(0, int(math.ceil(float(delay[:-1]))))
            except ValueError:
                pass
        for value in obj.values():
            nested = _find_retry_delay_in_obj(value)
            if nested is not None:
                return nested
    elif isinstance(obj, list):
        for item in obj:
            nested = _find_retry_delay_in_obj(item)
            if nested is not None:
                return nested
    return None


def _extract_error_context(
    exc: BaseException,
) -> tuple[int | None, str, str, Mapping[str, str] | None, object]:
    status_code: int | None = None
    status_label = ""
    headers: Mapping[str, str] | None = None
    details: object = None
    text_parts: list[str] = [str(exc)]

    if isinstance(exc, genai_errors.APIError):
        status_code = exc.code if isinstance(exc.code, int) else None
        status_label = str(exc.status or "")
        details = exc.details
        if exc.message:
            text_parts.append(str(exc.message))
        if details is not None:
            text_parts.append(str(details))
        resp = getattr(exc, "response", None)
        if resp is not None and hasattr(resp, "headers"):
            headers = resp.headers
    elif isinstance(exc, genai_interactions_errors.APIError):
        status_code = getattr(exc, "status_code", None)
        details = getattr(exc, "body", None)
        if getattr(exc, "message", None):
            text_parts.append(str(exc.message))
        if details is not None:
            text_parts.append(str(details))
        resp = getattr(exc, "response", None)
        if resp is not None and hasattr(resp, "headers"):
            headers = resp.headers
    elif isinstance(exc, ProviderStreamEventError):
        status_code = exc.status_code
        status_label = exc.status_label
        details = exc.details
        if details is not None:
            text_parts.append(str(details))
    elif hasattr(exc, "status_code"):
        raw_status = getattr(exc, "status_code", None)
        if isinstance(raw_status, int):
            status_code = raw_status

    if isinstance(details, Mapping):
        err_block = details.get("error")
        if isinstance(err_block, Mapping):
            if status_code is None and isinstance(err_block.get("code"), int):
                status_code = err_block["code"]
            if not status_label and isinstance(err_block.get("status"), str):
                status_label = err_block["status"]

    combined = redact_text(" ".join(part for part in text_parts if part))
    return status_code, status_label.upper(), combined, headers, details


def _is_transport_or_timeout_error(exc: BaseException) -> bool:
    return isinstance(
        exc,
        TimeoutError
        | asyncio.TimeoutError
        | ConnectionError
        | OSError
        | genai_interactions_errors.APIConnectionError
        | genai_interactions_errors.APITimeoutError
        | httpx.TimeoutException
        | httpx.TransportError
        | httpx2.TimeoutException
        | httpx2.TransportError,
    )


def map_provider_exception(exc: BaseException) -> MappedProviderError:
    """Classify any SDK, HTTP, transport, or validation error into CF-DEC-042 7 categories."""
    if isinstance(exc, AdapterOutputValidationError):
        return MappedProviderError(
            error_category="PERMANENT_INPUT_ERROR",
            error_code=exc.error_code,
            retryable=False,
            retry_after_seconds=None,
            safe_summary=redact_text(str(exc)),
        )

    if isinstance(exc, SourceInvalidatedError):
        return MappedProviderError(
            error_category="SOURCE_INVALIDATED",
            error_code="SOURCE_INVALIDATED",
            retryable=False,
            retry_after_seconds=None,
            safe_summary=redact_text(str(exc)),
        )

    if _is_transport_or_timeout_error(exc):
        return MappedProviderError(
            error_category="TRANSIENT_RETRYABLE",
            error_code="PROVIDER_UNAVAILABLE",
            retryable=True,
            retry_after_seconds=None,
            safe_summary=redact_text(f"{type(exc).__name__}: {exc}"),
        )

    status_code, status_label, combined, headers, details = _extract_error_context(exc)
    lowered = combined.lower()

    if any(marker in lowered for marker in _SOURCE_INVALIDATED_MARKERS) or status_code in (
        410,
        412,
    ):
        return MappedProviderError(
            error_category="SOURCE_INVALIDATED",
            error_code="SOURCE_INVALIDATED",
            retryable=False,
            retry_after_seconds=None,
            safe_summary=combined,
        )

    if status_code == 429 or status_label == "RESOURCE_EXHAUSTED":
        if any(marker in lowered for marker in _HARD_QUOTA_MARKERS):
            return MappedProviderError(
                error_category="HARD_QUOTA_BLOCKED",
                error_code="PROVIDER_QUOTA_EXHAUSTED",
                retryable=False,
                retry_after_seconds=None,
                safe_summary=combined,
            )
        retry_after = _extract_retry_after_seconds(headers, combined, details)
        return MappedProviderError(
            error_category="RATE_LIMIT_RETRYABLE",
            error_code="PROVIDER_RATE_LIMITED",
            retryable=True,
            retry_after_seconds=retry_after,
            safe_summary=combined,
        )

    if status_code in (401, 403):
        if status_code == 403 and any(marker in lowered for marker in _HARD_QUOTA_MARKERS):
            return MappedProviderError(
                error_category="HARD_QUOTA_BLOCKED",
                error_code="PROVIDER_QUOTA_EXHAUSTED",
                retryable=False,
                retry_after_seconds=None,
                safe_summary=combined,
            )
        error_code = (
            "INVALID_API_KEY"
            if status_code == 401 or "api key" in lowered or "api_key" in lowered
            else "PERMISSION_DENIED"
        )
        return MappedProviderError(
            error_category="CONFIGURATION_BLOCKED",
            error_code=error_code,
            retryable=False,
            retry_after_seconds=None,
            safe_summary=combined,
        )

    if status_code == 404 or status_label == "NOT_FOUND":
        return MappedProviderError(
            error_category="CONFIGURATION_BLOCKED",
            error_code="MODEL_OR_ENDPOINT_NOT_FOUND",
            retryable=False,
            retry_after_seconds=None,
            safe_summary=combined,
        )

    if status_code in (400, 413, 422) or status_label == "INVALID_ARGUMENT":
        if any(marker in lowered for marker in _CONFIG_KEY_MARKERS):
            error_code = (
                "INVALID_API_KEY"
                if "api key" in lowered or "api_key" in lowered
                else "CONFIGURATION_BLOCKED"
            )
            return MappedProviderError(
                error_category="CONFIGURATION_BLOCKED",
                error_code=error_code,
                retryable=False,
                retry_after_seconds=None,
                safe_summary=combined,
            )
        return MappedProviderError(
            error_category="PERMANENT_INPUT_ERROR",
            error_code="PERMANENT_INPUT_ERROR",
            retryable=False,
            retry_after_seconds=None,
            safe_summary=combined,
        )

    if status_code in (408, 500, 502, 503, 504) or status_label in (
        "UNAVAILABLE",
        "INTERNAL",
        "DEADLINE_EXCEEDED",
    ):
        return MappedProviderError(
            error_category="TRANSIENT_RETRYABLE",
            error_code="PROVIDER_UNAVAILABLE",
            retryable=True,
            retry_after_seconds=None,
            safe_summary=combined,
        )

    return MappedProviderError(
        error_category="UNKNOWN_REQUIRES_REVIEW",
        error_code="ADAPTER_UNAVAILABLE",
        retryable=False,
        retry_after_seconds=None,
        safe_summary=combined,
    )


def map_verify_exception(exc: BaseException) -> VerifyErrorCode:
    """Map an exception from credential verification to VerifyResult.error_code."""
    mapped = map_provider_exception(exc)
    if mapped.error_category == "CONFIGURATION_BLOCKED":
        if mapped.error_code == "PERMISSION_DENIED":
            return "PERMISSION_DENIED"
        return "INVALID_API_KEY"
    return "PROVIDER_UNAVAILABLE"
