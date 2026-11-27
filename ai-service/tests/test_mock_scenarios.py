"""Integration tests for MockProviderAdapter scenarios, context summary, and SSE cancel."""

import json
from collections.abc import AsyncIterator, Iterator
from pathlib import Path
from typing import Any

import httpx
import pytest
import yaml  # type: ignore[import-untyped]
from fastapi import Request
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator, FormatChecker

from app.config import get_settings
from app.main import app
from app.providers.factory import get_provider
from app.providers.mock_adapter import MockProviderAdapter, get_mock_request_context
from app.schemas import contracts

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "contracts" / "fixtures" / "internal"
TOKEN = "test-service-token-at-least-24-characters"
HEADERS = {"Authorization": f"Bearer {TOKEN}", "X-Provider-Credential": "mock-provider-credential"}


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


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    monkeypatch.setenv("CF_INTERNAL_SERVICE_TOKEN", TOKEN)
    monkeypatch.setenv("CF_PROVIDER_MODE", "MOCK")
    get_settings.cache_clear()
    app.dependency_overrides.clear()
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()
    get_settings.cache_clear()


def test_normal_stream_and_generate_include_factual_context_summary(client: TestClient) -> None:
    stream_body = load_fixture("stream-input.json")
    stream_body["prompt"]["intelligence_context"]["instructions"]["overridable_rules"] = [
        {"id": "ov_1", "priority": 1, "content": "Prefer concise output."}
    ]
    stream_body["prompt"]["intelligence_context"]["instructions"]["active_skills"] = [
        {"id": "sk_1", "priority": 0, "content": "Compare systematically."}
    ]
    stream_body["prompt"]["knowledge_and_evidence"]["knowledge_base"] = [
        {
            "source_ref": "kb_1",
            "source_type": "KB_CHUNK",
            "content": "KB snippet",
            "authority": "PROJECT_KB",
            "source_revision": "1",
            "source_link_status": "AVAILABLE",
        }
    ]

    stream_resp = client.post("/internal/v1/ai/streams", json=stream_body, headers=HEADERS)
    assert stream_resp.status_code == 200
    events = parse_sse(stream_resp.text)
    assert [name for name, _ in events] == [
        "provider.dispatched",
        "assistant.delta",
        "assistant.delta",
        "provider.completed",
    ]
    completed = events[-1][1]
    assert completed["assistant_text"] == events[1][1]["text"] + events[2][1]["text"]
    for snippet in (
        "mandatory_rules=1",
        "overridable_rules=1",
        "active_skills=1",
        "continuity_turns=1",
        "kb=1",
        "explicit_refs=1",
    ):
        assert snippet in completed["assistant_text"]

    gen_body = load_fixture("generate-input.json")
    gen_resp = client.post("/internal/v1/ai/generate", json=gen_body, headers=HEADERS)
    assert gen_resp.status_code == 200
    gen_data = gen_resp.json()
    assert "mandatory_rules=1" in gen_data["output_text"]
    assert "continuity_turns=1" in gen_data["output_text"]
    assert gen_data["parsed_json"] == {"summary": gen_data["output_text"]}
    assert get_mock_request_context() is None


@pytest.mark.parametrize("via", ["keyword", "header", "credential"])
def test_invalid_key_emits_undispatched_provider_failed_on_streams(
    client: TestClient, via: str
) -> None:
    body = load_fixture("stream-input.json")
    headers = dict(HEADERS)
    if via == "keyword":
        body["prompt"]["current_user_request"]["content_blocks"] = [
            {"type": "TEXT", "text": "Please test INVALID_KEY scenario"}
        ]
    elif via == "header":
        headers["X-Mock-Scenario"] = "INVALID_KEY"
    else:
        headers["X-Provider-Credential"] = "INVALID_KEY"

    response = client.post("/internal/v1/ai/streams", json=body, headers=headers)
    assert response.status_code == 200
    events = parse_sse(response.text)
    assert [name for name, _ in events] == ["provider.failed"]
    failure = events[0][1]
    assert failure["dispatched"] is False
    assert failure["occurred_at"] is None
    assert failure["error_category"] == "CONFIGURATION_BLOCKED"
    assert failure["error_code"] == "INVALID_API_KEY"
    assert failure["retryable"] is False


def test_invalid_key_on_generate_embeddings_and_verify(client: TestClient) -> None:
    gen_resp = client.post(
        "/internal/v1/ai/generate",
        json=load_fixture("generate-input.json"),
        headers={**HEADERS, "X-Mock-Scenario": "INVALID_KEY"},
    )
    assert gen_resp.status_code == 502
    validate_internal_schema("InternalError", gen_resp.json())
    assert gen_resp.json()["error"]["dispatched"] is False
    assert gen_resp.json()["error"]["error_category"] == "CONFIGURATION_BLOCKED"

    emb_resp = client.post(
        "/internal/v1/ai/embeddings",
        json=load_fixture("embedding-input.json"),
        headers={**HEADERS, "X-Mock-Scenario": "INVALID_KEY"},
    )
    assert emb_resp.status_code == 502
    validate_internal_schema("InternalError", emb_resp.json())
    assert emb_resp.json()["error"]["dispatched"] is False
    assert emb_resp.json()["error"]["error_category"] == "CONFIGURATION_BLOCKED"

    verify_resp = client.post(
        "/internal/v1/ai/credentials/verify",
        json=load_fixture("verify-input.json"),
        headers={**HEADERS, "X-Mock-Scenario": "INVALID_KEY"},
    )
    assert verify_resp.status_code == 200
    assert verify_resp.json()["valid"] is False
    assert verify_resp.json()["error_code"] == "INVALID_API_KEY"


@pytest.mark.parametrize("via", ["keyword", "header"])
def test_mock_429_emits_dispatched_then_rate_limited_failure(client: TestClient, via: str) -> None:
    body = load_fixture("stream-input.json")
    headers = dict(HEADERS)
    if via == "keyword":
        body["prompt"]["current_user_request"]["content_blocks"] = [
            {"type": "TEXT", "text": "[MOCK_429] trigger"}
        ]
    else:
        headers["X-Mock-Scenario"] = "[MOCK_429]"

    response = client.post("/internal/v1/ai/streams", json=body, headers=headers)
    assert response.status_code == 200
    events = parse_sse(response.text)
    assert [name for name, _ in events] == ["provider.dispatched", "provider.failed"]
    failure = events[-1][1]
    assert failure["dispatched"] is True
    assert failure["occurred_at"] == events[0][1]["occurred_at"]
    assert failure["error_category"] == "RATE_LIMIT_RETRYABLE"
    assert failure["error_code"] == "PROVIDER_RATE_LIMITED"
    assert failure["retryable"] is True
    assert failure["retry_after_seconds"] == 5


@pytest.mark.parametrize("via", ["keyword", "header"])
def test_mock_error_mid_stream_emits_two_deltas_then_transient_failure(
    client: TestClient, via: str
) -> None:
    body = load_fixture("stream-input.json")
    headers = dict(HEADERS)
    if via == "keyword":
        body["prompt"]["current_user_request"]["content_blocks"] = [
            {"type": "TEXT", "text": "[MOCK_ERROR_MID_STREAM] trigger"}
        ]
    else:
        headers["X-Mock-Scenario"] = "MOCK_ERROR_MID_STREAM"

    response = client.post("/internal/v1/ai/streams", json=body, headers=headers)
    assert response.status_code == 200
    events = parse_sse(response.text)
    assert [name for name, _ in events] == [
        "provider.dispatched",
        "assistant.delta",
        "assistant.delta",
        "provider.failed",
    ]
    failure = events[-1][1]
    assert failure["dispatched"] is True
    assert failure["occurred_at"] == events[0][1]["occurred_at"]
    assert failure["error_category"] == "TRANSIENT_RETRYABLE"
    assert failure["error_code"] == "PROVIDER_UNAVAILABLE"
    assert failure["retryable"] is True


@pytest.mark.parametrize("via", ["keyword", "header"])
def test_mock_partial_usage_preserves_null_cached_and_reasoning_tokens(
    client: TestClient, via: str
) -> None:
    body = load_fixture("stream-input.json")
    headers = dict(HEADERS)
    if via == "keyword":
        body["prompt"]["current_user_request"]["content_blocks"] = [
            {"type": "TEXT", "text": "[MOCK_PARTIAL_USAGE] trigger"}
        ]
    else:
        headers["X-Mock-Scenario"] = "[MOCK_PARTIAL_USAGE]"

    response = client.post("/internal/v1/ai/streams", json=body, headers=headers)
    assert response.status_code == 200
    events = parse_sse(response.text)
    assert [name for name, _ in events] == [
        "provider.dispatched",
        "assistant.delta",
        "assistant.delta",
        "provider.completed",
    ]
    completed = events[-1][1]
    assert completed["usage_status"] == "PARTIAL"
    assert completed["usage"]["input_tokens"] is not None
    assert completed["usage"]["output_tokens"] is not None
    assert completed["usage"]["total_tokens"] is not None
    assert completed["usage"]["cached_tokens"] is None
    assert completed["usage"]["reasoning_tokens"] is None

    gen_body = load_fixture("generate-input.json")
    gen_resp = client.post(
        "/internal/v1/ai/generate",
        json=gen_body,
        headers={**HEADERS, "X-Mock-Scenario": "[MOCK_PARTIAL_USAGE]"},
    )
    assert gen_resp.status_code == 200
    gen_json = gen_resp.json()
    assert gen_json["usage_status"] == "PARTIAL"
    assert gen_json["usage"]["cached_tokens"] is None
    assert gen_json["usage"]["reasoning_tokens"] is None


@pytest.mark.parametrize("via", ["keyword", "header"])
def test_mock_slow_stream_completes_when_connected(client: TestClient, via: str) -> None:
    body = load_fixture("stream-input.json")
    headers = {**HEADERS, "X-Mock-Slow-Delay-Ms": "5"}
    if via == "keyword":
        body["prompt"]["current_user_request"]["content_blocks"] = [
            {"type": "TEXT", "text": "[MOCK_SLOW_STREAM] please stream slowly"}
        ]
    else:
        headers["X-Mock-Scenario"] = "[MOCK_SLOW_STREAM]"

    response = client.post("/internal/v1/ai/streams", json=body, headers=headers)
    assert response.status_code == 200
    events = parse_sse(response.text)
    assert events[0][0] == "provider.dispatched"
    assert events[-1][0] == "provider.completed"
    delta_count = sum(1 for name, _ in events if name == "assistant.delta")
    assert delta_count == 6
    assert events[-1][1]["assistant_text"] == "".join(
        payload["text"] for name, payload in events if name == "assistant.delta"
    )


@pytest.mark.anyio
async def test_slow_stream_stops_and_closes_iterator_on_client_disconnect(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("CF_INTERNAL_SERVICE_TOKEN", TOKEN)
    monkeypatch.setenv("CF_PROVIDER_MODE", "MOCK")
    get_settings.cache_clear()
    app.dependency_overrides.clear()

    iterator_closed = False
    yielded_events = 0

    class TrackingSlowAdapter(MockProviderAdapter):
        async def stream(self, request: contracts.StreamInput) -> AsyncIterator[tuple[str, Any]]:
            nonlocal iterator_closed, yielded_events
            try:
                async for item in super().stream(request):
                    yielded_events += 1
                    yield item
            finally:
                iterator_closed = True

    disconnect_calls = 0

    async def fake_is_disconnected(_self: Request) -> bool:
        nonlocal disconnect_calls
        disconnect_calls += 1
        return disconnect_calls >= 5

    monkeypatch.setattr(Request, "is_disconnected", fake_is_disconnected)
    app.dependency_overrides[get_provider] = lambda: TrackingSlowAdapter(
        slow_chunk_delay_seconds=0.005
    )

    try:
        body = load_fixture("stream-input.json")
        body["prompt"]["current_user_request"]["content_blocks"] = [
            {"type": "TEXT", "text": "[MOCK_SLOW_STREAM] cancel test"}
        ]
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as async_client:
            resp = await async_client.post("/internal/v1/ai/streams", json=body, headers=HEADERS)
        assert resp.status_code == 200
        events = parse_sse(resp.text)
        assert events[0][0] == "provider.dispatched"
        assert all(name != "provider.completed" for name, _ in events)
        assert all(name != "provider.failed" for name, _ in events)
        assert iterator_closed is True
        assert yielded_events < 8
    finally:
        app.dependency_overrides.clear()
        get_settings.cache_clear()


@pytest.mark.anyio
async def test_slow_stream_early_http_close_closes_upstream_iterator(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("CF_INTERNAL_SERVICE_TOKEN", TOKEN)
    monkeypatch.setenv("CF_PROVIDER_MODE", "MOCK")
    get_settings.cache_clear()
    app.dependency_overrides.clear()

    iterator_closed = False

    class TrackingSlowAdapter(MockProviderAdapter):
        async def stream(self, request: contracts.StreamInput) -> AsyncIterator[tuple[str, Any]]:
            nonlocal iterator_closed
            try:
                async for item in super().stream(request):
                    yield item
            finally:
                iterator_closed = True

    app.dependency_overrides[get_provider] = lambda: TrackingSlowAdapter(
        slow_chunk_delay_seconds=0.01
    )

    try:
        body = load_fixture("stream-input.json")
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as async_client:
            async with async_client.stream(
                "POST",
                "/internal/v1/ai/streams",
                json=body,
                headers={**HEADERS, "X-Mock-Scenario": "[MOCK_SLOW_STREAM]"},
            ) as resp:
                assert resp.status_code == 200
                lines: list[str] = []
                async for line in resp.aiter_lines():
                    if line:
                        lines.append(line)
                    if len(lines) >= 4:
                        break
        assert iterator_closed is True
        assert not any("provider.completed" in line for line in lines)
    finally:
        app.dependency_overrides.clear()
        get_settings.cache_clear()
