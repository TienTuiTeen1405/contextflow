"""Contract fixtures and real HTTP serialization, independent of provider SDKs."""

import json
import math
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator, FormatChecker

from app.main import app
from app.providers.factory import get_provider
from app.providers.mock_adapter import MockProviderAdapter
from app.schemas import contracts

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "contracts" / "fixtures"
MANIFEST = json.loads((FIXTURES / "manifest.json").read_text(encoding="utf-8"))
TOKEN = "test-service-token-at-least-24-characters"
HEADERS = {"Authorization": f"Bearer {TOKEN}", "X-Provider-Credential": "mock-provider-credential"}


def sample(file: str) -> dict[str, Any]:
    return json.loads((FIXTURES / file).read_text(encoding="utf-8"))  # type: ignore[no-any-return]


def validate(document: str, schema: str, value: object) -> None:
    spec = json.loads((ROOT / "contracts" / f"{document}.openapi.json").read_text(encoding="utf-8"))
    Draft202012Validator(
        {"$ref": f"#/components/schemas/{schema}", "components": spec["components"]},
        format_checker=FormatChecker(),
    ).validate(value)


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch):  # type: ignore[no-untyped-def]
    from app.config import get_settings

    monkeypatch.setenv("CF_INTERNAL_SERVICE_TOKEN", TOKEN)
    monkeypatch.setenv("CF_PROVIDER_MODE", "MOCK")
    get_settings.cache_clear()
    app.dependency_overrides.clear()
    with TestClient(app) as result:
        yield result
    app.dependency_overrides.clear()
    get_settings.cache_clear()


@pytest.mark.parametrize("case", MANIFEST, ids=lambda c: c["file"])
def test_shared_fixtures_obey_the_wire_schema(case: dict[str, str]) -> None:
    body = sample(case["file"])
    validate(case["document"], case["schema"], body)
    if case["document"] == "internal":
        model = getattr(contracts, case["schema"])
        parsed = model.model_validate(body)
        validate("internal", case["schema"], parsed.model_dump(mode="json"))


@pytest.mark.parametrize("file", ["completed.sse", "failed.sse", "cancelled.sse"])
def test_public_sse_has_one_terminal_no_secret_and_monotonic_ids(file: str) -> None:
    wire = (FIXTURES / "public" / file).read_text(encoding="utf-8")
    for forbidden in (
        "native_output_steps",
        "thought_signature",
        "X-Provider-Credential",
        "password_hash",
    ):
        assert forbidden not in wire
    spec = json.loads((ROOT / "contracts/public.openapi.json").read_text(encoding="utf-8"))
    frames = [f for f in wire.split("\n\n") if f.startswith("id:")]
    terminal = {"assistant.completed", "request.failed", "request.cancelled"}
    events = []
    for index, frame in enumerate(frames, 1):
        fields = dict(line.split(": ", 1) for line in frame.splitlines())
        assert fields["id"] == str(index)
        event = fields["event"]
        validate("public", spec["x-sse-events"][event], json.loads(fields["data"]))
        events.append(event)
    assert events[0] == "request.accepted"
    assert sum(e in terminal for e in events) == 1
    assert events[-1] in terminal


def test_authentication_happens_before_invalid_payload_is_parsed(client: TestClient) -> None:
    response = client.post("/internal/v1/ai/generate", content="{invalid")
    assert response.status_code == 401
    validate("internal", "InternalError", response.json())
    assert response.json()["error"]["dispatched"] is False


def test_all_four_tasks_serialize_the_shared_contract(client: TestClient) -> None:
    cases = [
        ("generate", "generate-input.json", "GenerateResult"),
        ("embeddings", "embedding-input.json", "EmbeddingResult"),
        ("credentials/verify", "verify-input.json", "VerifyResult"),
    ]
    for endpoint, file, schema in cases:
        response = client.post(
            f"/internal/v1/ai/{endpoint}", json=sample("internal/" + file), headers=HEADERS
        )
        assert response.status_code == 200, response.text
        assert response.headers["X-Provider-Mode"] == "MOCK"
        validate("internal", schema, response.json())
    verification = client.post(
        "/internal/v1/ai/credentials/verify",
        json=sample("internal/verify-input.json"),
        headers=HEADERS,
    ).json()
    assert verification["valid"] is True
    assert verification["capability_status"] == "NOT_TESTED"
    result = client.post(
        "/internal/v1/ai/embeddings", json=sample("internal/embedding-input.json"), headers=HEADERS
    ).json()
    assert len(result["vectors"]) == 1
    assert len(result["vectors"][0]) == 1536
    assert all(math.isfinite(v) for v in result["vectors"][0])
    assert all(v is None for v in result["usage"].values())


def test_stream_uses_internal_events_and_preserves_null_usage(client: TestClient) -> None:
    response = client.post(
        "/internal/v1/ai/streams", json=sample("internal/stream-input.json"), headers=HEADERS
    )
    assert response.status_code == 200
    frames = [
        dict(line.split(": ", 1) for line in frame.splitlines())
        for frame in response.text.strip().split("\n\n")
    ]
    assert [f["event"] for f in frames] == [
        "provider.dispatched",
        "assistant.delta",
        "assistant.delta",
        "provider.completed",
    ]
    for frame in frames:
        assert "id" not in frame
        model = contracts.INTERNAL_EVENTS[frame["event"]]
        body = json.loads(frame["data"])
        model.model_validate(body)
        validate("internal", model.__name__, body)
    completed = json.loads(frames[-1]["data"])
    assert completed["assistant_text"] == "".join(
        json.loads(f["data"])["text"] for f in frames if f["event"] == "assistant.delta"
    )
    assert completed["usage"]["reasoning_tokens"] is None


@pytest.mark.parametrize(
    "mutation",
    ["business_id", "uuid_alias", "query_batch", "dimension", "store", "historical_quote"],
)
def test_invalid_inputs_fail_before_mock_dispatch(client: TestClient, mutation: str) -> None:
    file = (
        "embedding-input.json" if mutation in {"query_batch", "dimension"} else "stream-input.json"
    )
    body = sample("internal/" + file)
    if mutation == "business_id":
        body["contextflow_project_id"] = "11111111-1111-1111-1111-111111111111"
    elif mutation == "uuid_alias":
        body["prompt"]["continuity"][0]["turn_ref"] = "11111111-1111-1111-1111-111111111111"
    elif mutation == "query_batch":
        body["texts"] = ["one", "two"]
    elif mutation == "dimension":
        body["output_dimensionality"] = 1535
    elif mutation == "store":
        body["provider_options"]["store"] = True
    else:
        body["prompt"]["continuity"][0].pop("user_content_blocks")
    endpoint = "embeddings" if file.startswith("embedding") else "streams"
    response = client.post("/internal/v1/ai/" + endpoint, json=body, headers=HEADERS)
    assert response.status_code == 422
    validate("internal", "InternalError", response.json())
    assert response.json()["error"]["dispatched"] is False


def test_bad_structured_output_is_not_returned_to_core(client: TestClient) -> None:
    body = sample("internal/generate-input.json")
    body["response_schema"]["properties"]["summary"]["type"] = "integer"
    response = client.post("/internal/v1/ai/generate", json=body, headers=HEADERS)
    assert response.status_code == 502
    validate("internal", "InternalError", response.json())
    assert response.json()["error"]["error_category"] == "PERMANENT_INPUT_ERROR"


@pytest.mark.parametrize("bad_vector", [[0.0] * 1535, [float("nan")] * 1536, [float("inf")] * 1536])
def test_bad_adapter_vectors_are_normalized(client: TestClient, bad_vector: list[float]) -> None:
    class BadAdapter(MockProviderAdapter):
        def embeddings(self, request: contracts.EmbeddingInput) -> contracts.EmbeddingResult:
            return super().embeddings(request).model_copy(update={"vectors": [bad_vector]})

    app.dependency_overrides[get_provider] = BadAdapter
    response = client.post(
        "/internal/v1/ai/embeddings", json=sample("internal/embedding-input.json"), headers=HEADERS
    )
    assert response.status_code == 502
    validate("internal", "InternalError", response.json())


def test_native_steps_remain_opaque_through_model_round_trip() -> None:
    body = sample("internal/provider.completed.json")
    parsed = contracts.ProviderCompleted.model_validate(body)
    assert parsed.model_dump(mode="json")["native_output_steps"] == body["native_output_steps"]
    assert list(parsed.native_output_steps[0]["extension"]) == ["z", "a"]  # type: ignore[arg-type]


def test_wrong_provider_secret_is_never_echoed(
    client: TestClient, caplog: pytest.LogCaptureFixture
) -> None:
    secret = "synthetic-do-not-echo-secret"
    response = client.post(
        "/internal/v1/ai/credentials/verify",
        json=sample("internal/verify-input.json"),
        headers={**HEADERS, "X-Provider-Credential": secret},
    )
    assert response.status_code == 200
    assert response.json()["valid"] is False
    assert secret not in response.text
    assert secret not in caplog.text


def test_unexpected_adapter_error_is_sanitized(
    client: TestClient, caplog: pytest.LogCaptureFixture
) -> None:
    secret = "synthetic-secret-in-exception"

    class BrokenAdapter(MockProviderAdapter):
        def generate(self, request: contracts.GenerateInput) -> contracts.GenerateResult:
            raise RuntimeError(secret)

    app.dependency_overrides[get_provider] = BrokenAdapter
    response = client.post(
        "/internal/v1/ai/generate", json=sample("internal/generate-input.json"), headers=HEADERS
    )
    assert response.status_code == 502
    validate("internal", "InternalError", response.json())
    assert secret not in response.text
    assert secret not in caplog.text


def test_stream_failure_after_dispatch_has_one_safe_terminal(
    client: TestClient, caplog: pytest.LogCaptureFixture
) -> None:
    secret = "synthetic-secret-in-stream"

    class BrokenStream(MockProviderAdapter):
        async def stream(self, request: contracts.StreamInput) -> AsyncIterator[tuple[str, Any]]:
            async for event in super().stream(request):
                yield event
                raise RuntimeError(secret)

    app.dependency_overrides[get_provider] = BrokenStream
    response = client.post(
        "/internal/v1/ai/streams", json=sample("internal/stream-input.json"), headers=HEADERS
    )
    frames = [
        dict(line.split(": ", 1) for line in frame.splitlines())
        for frame in response.text.strip().split("\n\n")
    ]
    assert [frame["event"] for frame in frames] == ["provider.dispatched", "provider.failed"]
    body = json.loads(frames[-1]["data"])
    validate("internal", "ProviderFailure", body)
    assert body["dispatched"] is True
    assert body["occurred_at"] == json.loads(frames[0]["data"])["occurred_at"]
    assert secret not in response.text
    assert secret not in caplog.text


def test_structured_schema_references_never_trigger_network_retrieval(client: TestClient) -> None:
    body = sample("internal/generate-input.json")
    body["response_schema"] = {"$ref": "https://schema.invalid/private.json"}
    response = client.post("/internal/v1/ai/generate", json=body, headers=HEADERS)
    assert response.status_code == 502
    validate("internal", "InternalError", response.json())
    assert response.json()["error"]["error_category"] == "PERMANENT_INPUT_ERROR"
    body["response_schema"] = {
        "type": "object",
        "$defs": {"summary": {"type": "string"}},
        "properties": {"summary": {"$ref": "#/$defs/summary"}, "$ref": {"type": "string"}},
        "required": ["summary"],
        "additionalProperties": False,
    }
    response = client.post("/internal/v1/ai/generate", json=body, headers=HEADERS)
    assert response.status_code == 200
