"""Direct unit tests for GeminiInteractionsAdapter with stubbed Gemini SDK clients."""

import json
from collections.abc import AsyncIterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx
import pytest
from google.genai import _interactions_errors as ie
from pydantic import SecretStr

from app.providers.error_mapper import ProviderExecutionError
from app.providers.gemini_adapter import GeminiInteractionsAdapter
from app.providers.gemini_client import (
    reset_current_provider_credential,
    set_current_provider_credential,
)
from app.schemas import contracts

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "contracts" / "fixtures" / "internal"


def load_fixture(name: str) -> dict[str, Any]:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))  # type: ignore[no-any-return]


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
    def __init__(self, events: list[Any], calls: list[dict[str, Any]], closed: list[bool]) -> None:
        self._events = events
        self._calls = calls
        self._closed = closed

    async def create(self, **kwargs: Any) -> AsyncIterator[Any]:
        self._calls.append(kwargs)
        events = self._events
        closed = self._closed

        async def _gen() -> AsyncIterator[Any]:
            try:
                for ev in events:
                    if isinstance(ev, Exception):
                        raise ev
                    yield ev
            finally:
                closed.append(True)

        return _gen()


class _StubSyncInteractions:
    def __init__(self, result: Any, calls: list[dict[str, Any]]) -> None:
        self._result = result
        self._calls = calls

    def create(self, **kwargs: Any) -> Any:
        self._calls.append(kwargs)
        if isinstance(self._result, Exception):
            raise self._result
        return self._result


class _StubModels:
    def __init__(
        self,
        *,
        embed_results: list[Any] | None = None,
        list_result: Any = None,
        calls: list[dict[str, Any]],
    ) -> None:
        self._embed_results = list(embed_results or [])
        self._list_result = list_result
        self._calls = calls

    def embed_content(self, *, model: str, contents: Any, config: Any) -> Any:
        self._calls.append({"model": model, "contents": contents, "config": config})
        nxt = self._embed_results.pop(0)
        if isinstance(nxt, Exception):
            raise nxt
        return nxt

    def list(self, *, config: Any = None) -> Any:
        self._calls.append({"list_config": config})
        if isinstance(self._list_result, Exception):
            raise self._list_result
        return iter(self._list_result or [])


class _StubAio:
    def __init__(self, interactions: _StubAsyncInteractions, closed: list[bool]) -> None:
        self.interactions = interactions
        self._closed = closed

    async def aclose(self) -> None:
        self._closed.append(True)


class _StubClient:
    def __init__(
        self,
        *,
        stream_events: list[Any] | None = None,
        sync_result: Any = None,
        embed_results: list[Any] | None = None,
        list_result: Any = None,
    ) -> None:
        self.calls: list[dict[str, Any]] = []
        self.closed: list[bool] = []
        self.aio = _StubAio(
            _StubAsyncInteractions(stream_events or [], self.calls, self.closed), self.closed
        )
        self.interactions = _StubSyncInteractions(sync_result, self.calls)
        self.models = _StubModels(
            embed_results=embed_results, list_result=list_result, calls=self.calls
        )

    def close(self) -> None:
        self.closed.append(True)


@pytest.mark.anyio
async def test_gemini_adapter_stream_replays_continuity_and_closes_client() -> None:
    opaque_steps = [
        {"type": "thought", "signature": "sig-xyz", "summary": [{"type": "text", "text": "t"}]},
        {"type": "model_output", "content": [{"type": "text", "text": "Hello world"}]},
    ]
    events = [
        _StubObj(
            {
                "event_type": "interaction.created",
                "interaction": _StubObj({"id": "int-123"}),
            }
        ),
        _StubObj(
            {
                "event_type": "step.delta",
                "index": 1,
                "delta": _StubObj({"type": "text", "text": "Hello "}),
            }
        ),
        _StubObj(
            {
                "event_type": "step.delta",
                "index": 1,
                "delta": _StubObj({"type": "text", "text": "world"}),
            }
        ),
        _StubObj(
            {
                "event_type": "interaction.completed",
                "interaction": _StubObj(
                    {
                        "id": "int-123",
                        "status": "completed",
                        "steps": opaque_steps,
                        "usage": _StubObj(
                            {
                                "total_input_tokens": 12,
                                "total_output_tokens": 2,
                                "total_tokens": 14,
                            }
                        ),
                    }
                ),
            }
        ),
    ]
    stub_client = _StubClient(stream_events=events)
    adapter = GeminiInteractionsAdapter(client_factory=lambda _cred, **_kw: stub_client)
    req = contracts.StreamInput.model_validate(load_fixture("stream-input.json"))

    token = set_current_provider_credential(SecretStr("AIzaSyUnitTestAlpha12345"))
    try:
        emitted = [item async for item in adapter.stream(req)]
    finally:
        reset_current_provider_credential(token)

    assert [name for name, _ in emitted] == [
        "provider.dispatched",
        "assistant.delta",
        "assistant.delta",
        "provider.completed",
    ]
    completed = emitted[-1][1]
    assert isinstance(completed, contracts.ProviderCompleted)
    assert completed.assistant_text == "Hello world"
    assert completed.native_output_steps == opaque_steps
    assert completed.usage_status == "PARTIAL"
    assert completed.usage.cached_tokens is None
    assert completed.usage.reasoning_tokens is None

    # Verify PromptBuilder isolation & replay order sent to SDK
    sent = stub_client.calls[0]
    assert sent["store"] is False
    assert sent["stream"] is True
    assert "Nguồn mẫu." not in sent["system_instruction"]
    assert any(step.get("thought_signature") == "fixture-only-signature" for step in sent["input"])
    assert len(stub_client.closed) == 3  # raw_stream + aio.aclose + client.close


def test_gemini_adapter_generate_validates_schema_and_omits_v1beta_mime_type() -> None:
    req = contracts.GenerateInput.model_validate(load_fixture("generate-input.json"))
    stub_client = _StubClient(
        sync_result=_StubObj(
            {
                "id": "gen-1",
                "model": "gemini-3.5-flash-lite",
                "output_text": '{"summary": "Compiled summary"}',
                "steps": [
                    {
                        "type": "model_output",
                        "content": [{"type": "text", "text": '{"summary": "Compiled summary"}'}],
                    }
                ],
                "usage": {
                    "total_input_tokens": 30,
                    "total_output_tokens": 10,
                    "total_tokens": 40,
                    "total_cached_tokens": 0,
                    "total_thought_tokens": 0,
                },
            }
        )
    )
    adapter = GeminiInteractionsAdapter(client_factory=lambda _cred, **_kw: stub_client)
    token = set_current_provider_credential(SecretStr("AIzaSyUnitTestGen12345"))
    try:
        res = adapter.generate(req)
    finally:
        reset_current_provider_credential(token)

    assert res.parsed_json == {"summary": "Compiled summary"}
    assert res.usage_status == "AVAILABLE"
    assert "response_mime_type" not in stub_client.calls[0]
    assert stub_client.calls[0]["response_format"] == req.response_schema
    assert stub_client.closed == [True]


def test_gemini_adapter_embeddings_prefixes_tasks_and_verify_returns_not_tested() -> None:
    emb_req = contracts.EmbeddingInput.model_validate(load_fixture("embedding-input.json"))
    stub_client = _StubClient(
        embed_results=[_StubObj({"embeddings": [_StubObj({"values": [0.5] * 1536})]})],
        list_result=[_StubObj({"name": "models/gemini-3.5-flash-lite"})],
    )
    adapter = GeminiInteractionsAdapter(client_factory=lambda _cred, **_kw: stub_client)

    token = set_current_provider_credential(SecretStr("AIzaSyUnitTestEmb12345"))
    try:
        emb_res = adapter.embeddings(emb_req)
    finally:
        reset_current_provider_credential(token)

    assert len(emb_res.vectors) == 1
    assert len(emb_res.vectors[0]) == 1536
    sent_contents = stub_client.calls[0]["contents"]
    assert isinstance(sent_contents, list)
    assert len(sent_contents) == 1
    assert sent_contents[0].parts[0].text.startswith("task: search result | query: ")
    assert stub_client.calls[0]["config"].output_dimensionality == 1536
    assert stub_client.calls[0]["config"].task_type is None

    verify_req = contracts.VerifyInput.model_validate(load_fixture("verify-input.json"))
    ok = adapter.verify(verify_req, SecretStr("AIzaSyUnitTestVerify"))
    assert ok.valid is True
    assert ok.capability_status == "NOT_TESTED"

    bad_client = _StubClient(
        list_result=ie.AuthenticationError(
            "Invalid API key",
            response=httpx.Response(401, request=httpx.Request("GET", "https://example.test")),
            body=None,
        )
    )
    bad_adapter = GeminiInteractionsAdapter(client_factory=lambda _cred, **_kw: bad_client)
    bad = bad_adapter.verify(verify_req, SecretStr("bad-key"))
    assert bad.valid is False
    assert bad.error_code == "INVALID_API_KEY"
    assert bad.capability_status == "NOT_TESTED"


def test_gemini_adapter_rejects_unsupported_api_interface() -> None:
    req = contracts.GenerateInput.model_validate(load_fixture("generate-input.json"))
    req = req.model_copy(
        update={
            "provider_options": req.provider_options.model_copy(
                update={"api_interface": "GENERATE_CONTENT"}
            )
        }
    )
    adapter = GeminiInteractionsAdapter()
    token = set_current_provider_credential(SecretStr("AIzaSyUnitTestKey12345"))
    try:
        with pytest.raises(ProviderExecutionError) as exc_info:
            adapter.generate(req)
    finally:
        reset_current_provider_credential(token)
    assert exc_info.value.error_category == "CONFIGURATION_BLOCKED"
    assert exc_info.value.dispatched is False


def test_gemini_adapter_embeddings_batches_multiple_texts_in_single_sdk_call() -> None:
    emb_req = contracts.EmbeddingInput.model_validate(load_fixture("embedding-input.json"))
    batch_req = emb_req.model_copy(
        update={
            "task_type": "KB_CHUNK_EMBEDDING",
            "texts": ["doc 1", "doc 2", "doc 3"],
        }
    )
    stub_client = _StubClient(
        embed_results=[
            _StubObj(
                {
                    "embeddings": [
                        _StubObj({"values": [0.1] * 1536}),
                        _StubObj({"values": [0.2] * 1536}),
                        _StubObj({"values": [0.3] * 1536}),
                    ],
                    "usage_metadata": {"prompt_token_count": 18, "total_token_count": 18},
                }
            )
        ]
    )
    adapter = GeminiInteractionsAdapter(client_factory=lambda _cred, **_kw: stub_client)
    token = set_current_provider_credential(SecretStr("AIzaSyUnitTestBatch12345"))
    try:
        res = adapter.embeddings(batch_req)
    finally:
        reset_current_provider_credential(token)

    assert len(stub_client.calls) == 1
    sent_texts = [content.parts[0].text for content in stub_client.calls[0]["contents"]]
    assert sent_texts == [
        "title: none | text: doc 1",
        "title: none | text: doc 2",
        "title: none | text: doc 3",
    ]
    assert len(res.vectors) == 3
    assert all(len(v) == 1536 for v in res.vectors)
    assert res.usage_status == "PARTIAL"
    assert res.usage.input_tokens == 18
    assert res.usage.total_tokens == 18


@pytest.mark.anyio
async def test_gemini_adapter_reconstruct_preserves_opaque_blocks_and_annotations() -> None:
    events = [
        _StubObj(
            {
                "event_type": "interaction.created",
                "interaction": _StubObj({"id": "int-opaque-1"}),
            }
        ),
        _StubObj(
            {
                "event_type": "step.start",
                "index": 0,
                "step": {
                    "type": "thought",
                    "summary": [
                        {"type": "text", "text": "", "annotations": [{"id": "t-ann"}]},
                        {"type": "opaque_meta", "value": 42},
                    ],
                },
            }
        ),
        _StubObj(
            {
                "event_type": "step.delta",
                "index": 0,
                "delta": _StubObj({"type": "thought_signature", "signature": "sig-keep"}),
            }
        ),
        _StubObj(
            {
                "event_type": "step.delta",
                "index": 0,
                "delta": _StubObj(
                    {"type": "thought_summary", "content": _StubObj({"text": "reasoning"})}
                ),
            }
        ),
        _StubObj(
            {
                "event_type": "step.start",
                "index": 1,
                "step": {
                    "type": "model_output",
                    "content": [
                        {
                            "type": "text",
                            "text": "",
                            "annotations": [{"start_index": 0, "end_index": 5, "source": "cite-1"}],
                        },
                        {"type": "image", "mime_type": "image/png", "data": "base64data"},
                    ],
                    "extra_opaque": "keep-me",
                },
            }
        ),
        _StubObj(
            {
                "event_type": "step.delta",
                "index": 1,
                "delta": _StubObj({"type": "text", "text": "hello"}),
            }
        ),
        _StubObj(
            {
                "event_type": "interaction.completed",
                "interaction": _StubObj({"id": "int-opaque-1", "status": "completed", "steps": []}),
            }
        ),
    ]
    stub_client = _StubClient(stream_events=events)
    adapter = GeminiInteractionsAdapter(client_factory=lambda _cred, **_kw: stub_client)
    req = contracts.StreamInput.model_validate(load_fixture("stream-input.json"))

    token = set_current_provider_credential(SecretStr("AIzaSyUnitTestOpaque12345"))
    try:
        emitted = [item async for item in adapter.stream(req)]
    finally:
        reset_current_provider_credential(token)

    completed = emitted[-1][1]
    assert isinstance(completed, contracts.ProviderCompleted)
    assert completed.native_output_steps == [
        {
            "type": "thought",
            "signature": "sig-keep",
            "summary": [
                {"type": "text", "text": "reasoning", "annotations": [{"id": "t-ann"}]},
                {"type": "opaque_meta", "value": 42},
            ],
        },
        {
            "type": "model_output",
            "content": [
                {
                    "type": "text",
                    "text": "hello",
                    "annotations": [{"start_index": 0, "end_index": 5, "source": "cite-1"}],
                },
                {"type": "image", "mime_type": "image/png", "data": "base64data"},
            ],
            "extra_opaque": "keep-me",
        },
    ]


@pytest.mark.anyio
async def test_gemini_adapter_stream_fails_when_opaque_deltas_cannot_be_reconstructed() -> None:
    events = [
        _StubObj(
            {
                "event_type": "interaction.created",
                "interaction": _StubObj({"id": "int-opaque-err"}),
            }
        ),
        _StubObj(
            {
                "event_type": "step.start",
                "index": 0,
                "step": {"type": "model_output", "content": []},
            }
        ),
        _StubObj(
            {
                "event_type": "step.delta",
                "index": 0,
                "delta": _StubObj({"type": "citation", "source": "doc-9"}),
            }
        ),
        _StubObj(
            {
                "event_type": "interaction.completed",
                "interaction": _StubObj(
                    {"id": "int-opaque-err", "status": "completed", "steps": []}
                ),
            }
        ),
    ]
    stub_client = _StubClient(stream_events=events)
    adapter = GeminiInteractionsAdapter(client_factory=lambda _cred, **_kw: stub_client)
    req = contracts.StreamInput.model_validate(load_fixture("stream-input.json"))

    token = set_current_provider_credential(SecretStr("AIzaSyUnitTestOpaqueErr12"))
    try:
        emitted = [item async for item in adapter.stream(req)]
    finally:
        reset_current_provider_credential(token)

    assert [name for name, _ in emitted] == ["provider.dispatched", "provider.failed"]
    failure = emitted[-1][1]
    assert isinstance(failure, contracts.ProviderFailure)
    assert failure.error_category == "PERMANENT_INPUT_ERROR"
    assert failure.error_code == "INVALID_ADAPTER_OUTPUT"
    assert failure.retryable is False


@pytest.mark.anyio
async def test_gemini_adapter_stream_maps_error_events_through_error_mapper() -> None:
    req = contracts.StreamInput.model_validate(load_fixture("stream-input.json"))

    # Mid-stream 503 error event -> TRANSIENT_RETRYABLE, retryable=True, dispatched=True
    events_503 = [
        _StubObj(
            {
                "event_type": "interaction.created",
                "interaction": _StubObj({"id": "int-err-503"}),
            }
        ),
        _StubObj(
            {
                "event_type": "error",
                "error": _StubObj({"code": "503", "message": "Service Unavailable"}),
            }
        ),
    ]
    adapter_503 = GeminiInteractionsAdapter(
        client_factory=lambda _cred, **_kw: _StubClient(stream_events=events_503)
    )
    token = set_current_provider_credential(SecretStr("AIzaSyUnitTestErr503Key"))
    try:
        emitted_503 = [item async for item in adapter_503.stream(req)]
    finally:
        reset_current_provider_credential(token)

    assert [name for name, _ in emitted_503] == ["provider.dispatched", "provider.failed"]
    fail_503 = emitted_503[-1][1]
    assert isinstance(fail_503, contracts.ProviderFailure)
    assert fail_503.dispatched is True
    assert fail_503.error_category == "TRANSIENT_RETRYABLE"
    assert fail_503.error_code == "PROVIDER_UNAVAILABLE"
    assert fail_503.retryable is True

    # Undispatched RESOURCE_EXHAUSTED error event with retry hint -> RATE_LIMIT_RETRYABLE
    events_429 = [
        _StubObj(
            {
                "event_type": "error",
                "error": _StubObj(
                    {"code": "RESOURCE_EXHAUSTED", "message": "RPM exceeded; retry after 10s"}
                ),
            }
        )
    ]
    adapter_429 = GeminiInteractionsAdapter(
        client_factory=lambda _cred, **_kw: _StubClient(stream_events=events_429)
    )
    token = set_current_provider_credential(SecretStr("AIzaSyUnitTestErr429Key"))
    try:
        emitted_429 = [item async for item in adapter_429.stream(req)]
    finally:
        reset_current_provider_credential(token)

    assert [name for name, _ in emitted_429] == ["provider.failed"]
    fail_429 = emitted_429[-1][1]
    assert isinstance(fail_429, contracts.ProviderFailure)
    assert fail_429.dispatched is False
    assert fail_429.occurred_at is None
    assert fail_429.error_category == "RATE_LIMIT_RETRYABLE"
    assert fail_429.error_code == "PROVIDER_RATE_LIMITED"
    assert fail_429.retryable is True
    assert fail_429.retry_after_seconds == 10
