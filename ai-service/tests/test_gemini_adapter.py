"""Offline integration tests ADP-IT-01..06 for GeminiInteractionsAdapter over HTTP."""

import json
import logging
from collections.abc import AsyncIterator, Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx
import pytest
import yaml  # type: ignore[import-untyped]
from fastapi import Request
from fastapi.testclient import TestClient
from google.genai import _interactions_errors as ie
from google.genai import errors as ge
from jsonschema import Draft202012Validator, FormatChecker

from app.config import get_settings
from app.core.redaction import REDACTED
from app.main import app
from app.providers.factory import get_provider
from app.providers.gemini_adapter import GeminiInteractionsAdapter
from app.schemas import contracts

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "contracts" / "fixtures" / "internal"
TOKEN = "test-service-token-at-least-24-characters"
SECRET_KEY = "AIzaSyIntegrationTestMockSecret1234567890"
HEADERS = {"Authorization": f"Bearer {TOKEN}", "X-Provider-Credential": SECRET_KEY}


def load_fixture(name: str) -> dict[str, Any]:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))  # type: ignore[no-any-return]


def validate_internal_schema(schema: str, value: object) -> None:
    spec = yaml.safe_load(
        (ROOT / "contracts" / "internal.openapi.yaml").read_text(encoding="utf-8")
    )
    Draft202012Validator(
        {"$ref": f"#/components/schemas/{schema}", "components": spec["components"]},
        format_checker=FormatChecker(),
    ).validate(value)


def parse_sse(text: str) -> list[tuple[str, dict[str, Any]]]:
    frames: list[tuple[str, dict[str, Any]]] = []
    for raw_frame in text.strip().split("\n\n"):
        if not raw_frame:
            continue
        fields = dict(line.split(": ", 1) for line in raw_frame.splitlines())
        event = fields["event"]
        body = json.loads(fields["data"])
        model = contracts.INTERNAL_EVENTS[event]
        model.model_validate(body)
        validate_internal_schema(model.__name__, body)
        frames.append((event, body))
    return frames


@dataclass
class _StubObj:
    data: dict[str, Any]

    def __getattr__(self, name: str) -> Any:
        if name in self.data:
            return self.data[name]
        raise AttributeError(name)

    def model_dump(self, **_kwargs: Any) -> dict[str, Any]:
        return dict(self.data)


class _StubAsyncInteractions:
    def __init__(self, events: list[Any], closed: list[str]) -> None:
        self._events = events
        self._closed = closed

    async def create(self, **kwargs: Any) -> AsyncIterator[Any]:
        events = self._events
        closed = self._closed
        if events and isinstance(events[0], Exception) and len(events) == 1:
            raise events[0]

        async def _gen() -> AsyncIterator[Any]:
            try:
                for ev in events:
                    if isinstance(ev, Exception):
                        raise ev
                    yield ev
            finally:
                closed.append("raw_stream")

        return _gen()


class _StubSyncInteractions:
    def __init__(self, result: Any) -> None:
        self._result = result

    def create(self, **_kwargs: Any) -> Any:
        if isinstance(self._result, Exception):
            raise self._result
        return self._result


class _StubModels:
    def __init__(self, *, embed_results: list[Any] | None = None, list_result: Any = None) -> None:
        self._embed_results = list(embed_results or [])
        self._list_result = list_result

    def embed_content(self, **_kwargs: Any) -> Any:
        nxt = self._embed_results.pop(0)
        if isinstance(nxt, Exception):
            raise nxt
        return nxt

    def list(self, **_kwargs: Any) -> Any:
        if isinstance(self._list_result, Exception):
            raise self._list_result
        return iter(self._list_result or [])


class _StubAio:
    def __init__(self, interactions: _StubAsyncInteractions, closed: list[str]) -> None:
        self.interactions = interactions
        self._closed = closed

    async def aclose(self) -> None:
        self._closed.append("aio")


class _StubClient:
    def __init__(
        self,
        *,
        stream_events: list[Any] | None = None,
        sync_result: Any = None,
        embed_results: list[Any] | None = None,
        list_result: Any = None,
    ) -> None:
        self.closed: list[str] = []
        self.aio = _StubAio(_StubAsyncInteractions(stream_events or [], self.closed), self.closed)
        self.interactions = _StubSyncInteractions(sync_result)
        self.models = _StubModels(embed_results=embed_results, list_result=list_result)

    def close(self) -> None:
        self.closed.append("sync")


@pytest.fixture
def gemini_client(monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    monkeypatch.setenv("CF_INTERNAL_SERVICE_TOKEN", TOKEN)
    monkeypatch.setenv("CF_PROVIDER_MODE", "GEMINI_INTERACTIONS")
    get_settings.cache_clear()
    app.dependency_overrides.clear()
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()
    get_settings.cache_clear()


def test_adp_it_01_stream_happy_path_opaque_steps_and_usage_null_semantics(
    gemini_client: TestClient,
) -> None:
    opaque_steps = [
        {"type": "thought", "signature": "sig-01", "extension": {"z": 1, "a": 2}},
        {"type": "model_output", "content": [{"type": "text", "text": "Part 1 Part 2"}]},
    ]
    stub = _StubClient(
        stream_events=[
            _StubObj(
                {
                    "event_type": "interaction.created",
                    "interaction": _StubObj({"id": "int-adp-01"}),
                }
            ),
            _StubObj(
                {
                    "event_type": "step.delta",
                    "index": 1,
                    "delta": _StubObj({"type": "text", "text": "Part 1 "}),
                }
            ),
            _StubObj(
                {
                    "event_type": "step.delta",
                    "index": 1,
                    "delta": _StubObj({"type": "text", "text": "Part 2"}),
                }
            ),
            _StubObj(
                {
                    "event_type": "interaction.completed",
                    "interaction": _StubObj(
                        {
                            "id": "int-adp-01",
                            "status": "completed",
                            "steps": opaque_steps,
                            "usage": _StubObj(
                                {
                                    "total_input_tokens": 21,
                                    "total_output_tokens": 9,
                                    "total_tokens": 30,
                                }
                            ),
                        }
                    ),
                }
            ),
        ]
    )
    app.dependency_overrides[get_provider] = lambda: GeminiInteractionsAdapter(
        client_factory=lambda _cred, **_kw: stub
    )

    resp = gemini_client.post(
        "/internal/v1/ai/streams", json=load_fixture("stream-input.json"), headers=HEADERS
    )
    assert resp.status_code == 200
    assert resp.headers["X-Provider-Mode"] == "GEMINI_INTERACTIONS"

    events = parse_sse(resp.text)
    assert [name for name, _ in events] == [
        "provider.dispatched",
        "assistant.delta",
        "assistant.delta",
        "provider.completed",
    ]
    assert events[0][1]["provider_request_id"] == "int-adp-01"
    completed = events[-1][1]
    assert completed["assistant_text"] == "Part 1 Part 2"
    assert completed["native_output_steps"] == opaque_steps
    assert list(completed["native_output_steps"][0]["extension"]) == ["z", "a"]
    assert completed["usage_status"] == "PARTIAL"
    assert completed["usage"]["input_tokens"] == 21
    assert completed["usage"]["output_tokens"] == 9
    assert completed["usage"]["total_tokens"] == 30
    assert completed["usage"]["cached_tokens"] is None
    assert completed["usage"]["reasoning_tokens"] is None
    assert stub.closed == ["raw_stream", "aio", "sync"]


def test_adp_it_02_mid_stream_failure_and_disconnect_cleanup(
    gemini_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    stub_err = _StubClient(
        stream_events=[
            _StubObj(
                {
                    "event_type": "interaction.created",
                    "interaction": _StubObj({"id": "int-adp-02"}),
                }
            ),
            _StubObj(
                {
                    "event_type": "step.delta",
                    "index": 0,
                    "delta": _StubObj({"type": "text", "text": "Partial text"}),
                }
            ),
            ie.InternalServerError(
                "503 upstream disconnect",
                response=httpx.Response(503, request=httpx.Request("POST", "https://example.test")),
                body={"error": {"code": 503}},
            ),
        ]
    )
    app.dependency_overrides[get_provider] = lambda: GeminiInteractionsAdapter(
        client_factory=lambda _cred, **_kw: stub_err
    )

    resp = gemini_client.post(
        "/internal/v1/ai/streams", json=load_fixture("stream-input.json"), headers=HEADERS
    )
    assert resp.status_code == 200
    events = parse_sse(resp.text)
    assert [name for name, _ in events] == [
        "provider.dispatched",
        "assistant.delta",
        "provider.failed",
    ]
    failure = events[-1][1]
    assert failure["dispatched"] is True
    assert failure["occurred_at"] == events[0][1]["occurred_at"]
    assert failure["error_category"] == "TRANSIENT_RETRYABLE"
    assert failure["retryable"] is True
    assert stub_err.closed == ["raw_stream", "aio", "sync"]

    # Disconnect mid-stream cancels cleanly without terminal event and closes upstream resources
    stub_disc = _StubClient(
        stream_events=[
            _StubObj(
                {
                    "event_type": "interaction.created",
                    "interaction": _StubObj({"id": "int-disc"}),
                }
            ),
            _StubObj(
                {
                    "event_type": "step.delta",
                    "index": 0,
                    "delta": _StubObj({"type": "text", "text": "Chunk 1"}),
                }
            ),
            _StubObj(
                {
                    "event_type": "step.delta",
                    "index": 0,
                    "delta": _StubObj({"type": "text", "text": "Chunk 2"}),
                }
            ),
        ]
    )
    app.dependency_overrides[get_provider] = lambda: GeminiInteractionsAdapter(
        client_factory=lambda _cred, **_kw: stub_disc
    )
    calls = 0

    async def fake_disconnect(_self: Request) -> bool:
        nonlocal calls
        calls += 1
        return calls >= 4

    monkeypatch.setattr(Request, "is_disconnected", fake_disconnect)
    disc_resp = gemini_client.post(
        "/internal/v1/ai/streams", json=load_fixture("stream-input.json"), headers=HEADERS
    )
    disc_events = parse_sse(disc_resp.text)
    assert disc_events[0][0] == "provider.dispatched"
    assert all(name not in {"provider.completed", "provider.failed"} for name, _ in disc_events)
    assert set(stub_disc.closed) == {"raw_stream", "aio", "sync"}


def test_adp_it_03_rate_limit_retry_after_and_hard_quota_mapping(
    gemini_client: TestClient,
) -> None:
    req_obj = httpx.Request("POST", "https://generativelanguage.googleapis.com/v1/interactions")
    stub_429 = _StubClient(
        stream_events=[
            _StubObj(
                {
                    "event_type": "interaction.created",
                    "interaction": _StubObj({"id": "int-429"}),
                }
            ),
            ie.RateLimitError(
                "RPM rate limit exceeded",
                response=httpx.Response(429, headers={"Retry-After": "12"}, request=req_obj),
                body={"error": {"code": 429, "status": "RESOURCE_EXHAUSTED"}},
            ),
        ]
    )
    app.dependency_overrides[get_provider] = lambda: GeminiInteractionsAdapter(
        client_factory=lambda _cred, **_kw: stub_429
    )

    resp_429 = gemini_client.post(
        "/internal/v1/ai/streams", json=load_fixture("stream-input.json"), headers=HEADERS
    )
    events_429 = parse_sse(resp_429.text)
    assert [name for name, _ in events_429] == ["provider.dispatched", "provider.failed"]
    fail_429 = events_429[-1][1]
    assert fail_429["error_category"] == "RATE_LIMIT_RETRYABLE"
    assert fail_429["retry_after_seconds"] == 12
    assert fail_429["retryable"] is True

    # Hard quota on /generate -> HARD_QUOTA_BLOCKED, retryable=False, retry_after_seconds=None
    stub_quota = _StubClient(
        sync_result=ie.RateLimitError(
            "Daily RPD hard quota exhausted; check billing and plan",
            response=httpx.Response(429, headers={"Retry-After": "60"}, request=req_obj),
            body={"error": {"code": 429, "status": "RESOURCE_EXHAUSTED"}},
        )
    )
    app.dependency_overrides[get_provider] = lambda: GeminiInteractionsAdapter(
        client_factory=lambda _cred, **_kw: stub_quota
    )
    resp_quota = gemini_client.post(
        "/internal/v1/ai/generate", json=load_fixture("generate-input.json"), headers=HEADERS
    )
    assert resp_quota.status_code == 502
    assert resp_quota.headers["X-Provider-Mode"] == "GEMINI_INTERACTIONS"
    validate_internal_schema("InternalError", resp_quota.json())
    err = resp_quota.json()["error"]
    assert err["error_category"] == "HARD_QUOTA_BLOCKED"
    assert err["error_code"] == "PROVIDER_QUOTA_EXHAUSTED"
    assert err["retryable"] is False
    assert err["retry_after_seconds"] is None


@pytest.mark.parametrize("bad_output", ["{not valid json", '{"summary": 123}', ""])
def test_adp_it_04_generate_rejects_malformed_or_schema_mismatched_json(
    gemini_client: TestClient, bad_output: str
) -> None:
    stub = _StubClient(
        sync_result=_StubObj(
            {
                "id": "gen-bad",
                "output_text": bad_output,
                "steps": [
                    {"type": "model_output", "content": [{"type": "text", "text": bad_output}]}
                ],
                "usage": {"total_input_tokens": 5, "total_output_tokens": 2, "total_tokens": 7},
            }
        )
    )
    app.dependency_overrides[get_provider] = lambda: GeminiInteractionsAdapter(
        client_factory=lambda _cred, **_kw: stub
    )

    resp = gemini_client.post(
        "/internal/v1/ai/generate", json=load_fixture("generate-input.json"), headers=HEADERS
    )
    assert resp.status_code == 502
    validate_internal_schema("InternalError", resp.json())
    err = resp.json()["error"]
    assert err["error_category"] == "PERMANENT_INPUT_ERROR"
    assert err["error_code"] == "INVALID_ADAPTER_OUTPUT"
    assert err["dispatched"] is True
    assert err["usage_status"] == "PARTIAL"
    assert err["usage"]["cached_tokens"] is None


@pytest.mark.parametrize(
    "bad_vector",
    [[0.1] * 1535, [float("nan")] * 1536, [float("inf")] * 1536],
)
def test_adp_it_05_embeddings_rejects_invalid_dimension_or_non_finite_values(
    gemini_client: TestClient, bad_vector: list[float]
) -> None:
    stub = _StubClient(embed_results=[_StubObj({"embeddings": [_StubObj({"values": bad_vector})]})])
    app.dependency_overrides[get_provider] = lambda: GeminiInteractionsAdapter(
        client_factory=lambda _cred, **_kw: stub
    )

    resp = gemini_client.post(
        "/internal/v1/ai/embeddings", json=load_fixture("embedding-input.json"), headers=HEADERS
    )
    assert resp.status_code == 502
    validate_internal_schema("InternalError", resp.json())
    err = resp.json()["error"]
    assert err["error_category"] == "PERMANENT_INPUT_ERROR"
    assert err["error_code"] == "INVALID_ADAPTER_OUTPUT"
    assert err["dispatched"] is True


def test_adp_it_06_verify_always_not_tested_and_never_leaks_secret_in_logs(
    gemini_client: TestClient, caplog: pytest.LogCaptureFixture
) -> None:
    req_obj = httpx.Request("GET", "https://generativelanguage.googleapis.com/v1/models")
    stub_ok = _StubClient(list_result=[_StubObj({"name": "models/gemini-3.5-flash-lite"})])
    app.dependency_overrides[get_provider] = lambda: GeminiInteractionsAdapter(
        client_factory=lambda _cred, **_kw: stub_ok
    )

    with caplog.at_level(logging.DEBUG):
        ok_resp = gemini_client.post(
            "/internal/v1/ai/credentials/verify",
            json=load_fixture("verify-input.json"),
            headers=HEADERS,
        )
    assert ok_resp.status_code == 200
    assert ok_resp.headers["X-Provider-Mode"] == "GEMINI_INTERACTIONS"
    assert ok_resp.json()["valid"] is True
    assert ok_resp.json()["capability_status"] == "NOT_TESTED"
    assert SECRET_KEY not in ok_resp.text
    assert SECRET_KEY not in caplog.text

    # Invalid key failure with secret embedded in upstream exception text
    stub_err = _StubClient(
        list_result=ge.APIError(
            400,
            {
                "error": {
                    "code": 400,
                    "status": "INVALID_ARGUMENT",
                    "message": f"API key not valid: {SECRET_KEY}",
                }
            },
            httpx.Response(400, request=req_obj),
        )
    )
    app.dependency_overrides[get_provider] = lambda: GeminiInteractionsAdapter(
        client_factory=lambda _cred, **_kw: stub_err
    )

    with caplog.at_level(logging.DEBUG):
        err_resp = gemini_client.post(
            "/internal/v1/ai/credentials/verify",
            json=load_fixture("verify-input.json"),
            headers=HEADERS,
        )
    assert err_resp.status_code == 200
    body = err_resp.json()
    assert body["valid"] is False
    assert body["error_code"] == "INVALID_API_KEY"
    assert body["capability_status"] == "NOT_TESTED"
    assert SECRET_KEY not in err_resp.text
    assert SECRET_KEY not in caplog.text
    assert TOKEN not in caplog.text
    if caplog.text:
        assert REDACTED in caplog.text or "INVALID_API_KEY" in caplog.text
