"""Unit tests for CF-DEC-042 7-category error mapper, Usage Null Semantics, and validators."""

from datetime import UTC, datetime, timedelta
from email.utils import format_datetime

import httpx
import pytest
from google.genai import _interactions_errors as ie
from google.genai import errors as ge
from pydantic import JsonValue

from app.core.redaction import REDACTED, redacted_secrets_scope
from app.providers.error_mapper import (
    AdapterOutputValidationError,
    SourceInvalidatedError,
    map_provider_exception,
    map_provider_usage,
    map_verify_exception,
)
from app.providers.validators import (
    parse_and_validate_generate_json,
    validate_embedding_vectors,
)


def _httpx_response(status: int, headers: dict[str, str] | None = None) -> httpx.Response:
    req = httpx.Request("POST", "https://generativelanguage.googleapis.com/v1/interactions")
    return httpx.Response(status, headers=headers or {}, request=req)


def test_error_mapper_covers_all_7_cf_dec_042_categories() -> None:
    # 1. TRANSIENT_RETRYABLE (503 + timeout)
    mapped_503 = map_provider_exception(
        ie.InternalServerError(
            "service unavailable", response=_httpx_response(503), body={"error": {"code": 503}}
        )
    )
    assert mapped_503.error_category == "TRANSIENT_RETRYABLE"
    assert mapped_503.retryable is True

    mapped_timeout = map_provider_exception(TimeoutError("upstream timed out"))
    assert mapped_timeout.error_category == "TRANSIENT_RETRYABLE"
    assert mapped_timeout.retryable is True

    # 2. RATE_LIMIT_RETRYABLE (429 + integer Retry-After)
    mapped_429 = map_provider_exception(
        ie.RateLimitError(
            "Rate limit exceeded",
            response=_httpx_response(429, {"Retry-After": "9"}),
            body={"error": {"code": 429, "status": "RESOURCE_EXHAUSTED"}},
        )
    )
    assert mapped_429.error_category == "RATE_LIMIT_RETRYABLE"
    assert mapped_429.error_code == "PROVIDER_RATE_LIMITED"
    assert mapped_429.retryable is True
    assert mapped_429.retry_after_seconds == 9

    # 3. HARD_QUOTA_BLOCKED (429 daily RPD / billing cap)
    mapped_rpd = map_provider_exception(
        ge.APIError(
            429,
            {
                "error": {
                    "code": 429,
                    "status": "RESOURCE_EXHAUSTED",
                    "message": "Requests per day RPD quota exceeded; check plan and billing",
                }
            },
            _httpx_response(429, {"Retry-After": "30"}),
        )
    )
    assert mapped_rpd.error_category == "HARD_QUOTA_BLOCKED"
    assert mapped_rpd.error_code == "PROVIDER_QUOTA_EXHAUSTED"
    assert mapped_rpd.retryable is False
    assert mapped_rpd.retry_after_seconds is None

    # 4. CONFIGURATION_BLOCKED (401 / 403 / 404 / 400 API_KEY_INVALID)
    mapped_401 = map_provider_exception(
        ie.AuthenticationError("Invalid API key", response=_httpx_response(401), body=None)
    )
    assert mapped_401.error_category == "CONFIGURATION_BLOCKED"
    assert mapped_401.error_code == "INVALID_API_KEY"
    assert mapped_401.retryable is False

    mapped_400_key = map_provider_exception(
        ge.APIError(
            400,
            {"error": {"code": 400, "status": "INVALID_ARGUMENT", "message": "API key not valid"}},
        )
    )
    assert mapped_400_key.error_category == "CONFIGURATION_BLOCKED"
    assert mapped_400_key.error_code == "INVALID_API_KEY"

    mapped_404 = map_provider_exception(
        ie.NotFoundError("Model not found", response=_httpx_response(404), body=None)
    )
    assert mapped_404.error_category == "CONFIGURATION_BLOCKED"

    # 5. PERMANENT_INPUT_ERROR (400 bad payload & AdapterOutputValidationError)
    mapped_400 = map_provider_exception(
        ie.BadRequestError("Malformed step input", response=_httpx_response(400), body=None)
    )
    assert mapped_400.error_category == "PERMANENT_INPUT_ERROR"
    assert mapped_400.retryable is False

    mapped_val = map_provider_exception(
        AdapterOutputValidationError("INVALID_ADAPTER_OUTPUT", "Bad schema")
    )
    assert mapped_val.error_category == "PERMANENT_INPUT_ERROR"
    assert mapped_val.error_code == "INVALID_ADAPTER_OUTPUT"

    # 6. SOURCE_INVALIDATED
    mapped_src = map_provider_exception(SourceInvalidatedError("Cache gone"))
    assert mapped_src.error_category == "SOURCE_INVALIDATED"
    assert mapped_src.retryable is False

    mapped_410 = map_provider_exception(
        ge.APIError(410, {"error": {"code": 410, "message": "Resource expired"}})
    )
    assert mapped_410.error_category == "SOURCE_INVALIDATED"

    # 7. UNKNOWN_REQUIRES_REVIEW
    mapped_unknown = map_provider_exception(RuntimeError("Unclassified internal failure"))
    assert mapped_unknown.error_category == "UNKNOWN_REQUIRES_REVIEW"
    assert mapped_unknown.retryable is False


def test_retry_after_http_date_and_retry_info_parsing_and_secret_scrubbing() -> None:
    future_date = format_datetime(datetime.now(UTC) + timedelta(seconds=12), usegmt=True)
    mapped_date = map_provider_exception(
        ie.RateLimitError(
            "Too many requests",
            response=_httpx_response(429, {"Retry-After": future_date}),
            body=None,
        )
    )
    assert mapped_date.error_category == "RATE_LIMIT_RETRYABLE"
    assert mapped_date.retry_after_seconds is not None
    assert 1 <= mapped_date.retry_after_seconds <= 15

    mapped_delay = map_provider_exception(
        ge.APIError(
            429,
            {
                "error": {
                    "code": 429,
                    "status": "RESOURCE_EXHAUSTED",
                    "message": "Please retry in 7.2s",
                }
            },
        )
    )
    assert mapped_delay.retry_after_seconds == 8

    secret = "custom-leak-key-99999"
    with redacted_secrets_scope([secret]):
        scrubbed = map_provider_exception(
            RuntimeError(f"Error with {secret} and AIzaSyFakeKeyLeak123456789")
        )
    assert secret not in scrubbed.safe_summary
    assert "AIza" not in scrubbed.safe_summary
    assert REDACTED in scrubbed.safe_summary


def test_map_verify_exception_maps_auth_permission_and_transient() -> None:
    assert (
        map_verify_exception(
            ie.AuthenticationError("Bad key", response=_httpx_response(401), body=None)
        )
        == "INVALID_API_KEY"
    )
    assert (
        map_verify_exception(
            ie.PermissionDeniedError("Denied", response=_httpx_response(403), body=None)
        )
        == "PERMISSION_DENIED"
    )
    assert map_verify_exception(TimeoutError("network")) == "PROVIDER_UNAVAILABLE"


def test_usage_null_semantics_never_coerces_none_or_adds_reasoning_tokens() -> None:
    # Unavailable
    status_none, usage_none = map_provider_usage(None)
    assert status_none == "UNAVAILABLE"
    assert all(v is None for v in usage_none.model_dump().values())

    # Available (explicit zeros preserved, all 5 populated)
    status_avail, usage_avail = map_provider_usage(
        {
            "total_input_tokens": 54,
            "total_output_tokens": 43,
            "total_tokens": 365,
            "total_cached_tokens": 0,
            "total_thought_tokens": 268,
        }
    )
    assert status_avail == "AVAILABLE"
    assert usage_avail.cached_tokens == 0
    assert usage_avail.reasoning_tokens == 268
    assert usage_avail.total_tokens == 365

    # Partial: missing cached & reasoning stay None; total_tokens NOT fabricated/added
    status_partial, usage_partial = map_provider_usage(
        {"total_input_tokens": 10, "total_output_tokens": 20, "total_thought_tokens": 5}
    )
    assert status_partial == "PARTIAL"
    assert usage_partial.input_tokens == 10
    assert usage_partial.output_tokens == 20
    assert usage_partial.reasoning_tokens == 5
    assert usage_partial.total_tokens is None
    assert usage_partial.cached_tokens is None

    # Invalid negative or boolean values become None rather than leaking bad numbers
    status_bad, usage_bad = map_provider_usage(
        {"total_input_tokens": True, "total_output_tokens": -1}
    )
    assert status_bad == "UNAVAILABLE"
    assert usage_bad.input_tokens is None
    assert usage_bad.output_tokens is None


def test_generate_json_validator_accepts_valid_and_rejects_malformed_or_mismatch() -> None:
    schema: dict[str, JsonValue] = {
        "type": "object",
        "properties": {"summary": {"type": "string"}},
        "required": ["summary"],
        "additionalProperties": False,
    }
    assert parse_and_validate_generate_json("non json text", None) is None
    assert parse_and_validate_generate_json('{"summary": "ok"}', schema) == {"summary": "ok"}

    for bad_text in ("", "{not-json", "123", '{"summary": 123}', '{"summary": NaN}'):
        with pytest.raises(AdapterOutputValidationError):
            parse_and_validate_generate_json(bad_text, schema)

    remote_schema: dict[str, JsonValue] = {"$ref": "https://example.invalid/schema.json"}
    with pytest.raises(AdapterOutputValidationError):
        parse_and_validate_generate_json('{"summary": "ok"}', remote_schema)


def test_embedding_validator_enforces_batch_1536_dim_and_finite_numbers() -> None:
    valid = [[0.25] * 1536]
    assert validate_embedding_vectors(valid, expected_count=1) == valid

    with pytest.raises(AdapterOutputValidationError):
        validate_embedding_vectors([[0.1] * 1536] * 11, expected_count=11)

    with pytest.raises(AdapterOutputValidationError):
        validate_embedding_vectors(valid, expected_count=2)

    with pytest.raises(AdapterOutputValidationError):
        validate_embedding_vectors([[0.1] * 1535], expected_count=1)

    for bad_elem in (float("nan"), float("inf"), float("-inf"), True, "0.1"):
        bad_vec: list[object] = [0.0] * 1535 + [bad_elem]
        with pytest.raises(AdapterOutputValidationError):
            validate_embedding_vectors([bad_vec], expected_count=1)
