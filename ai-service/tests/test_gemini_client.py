"""Unit tests for request-scoped Gemini client factory and isolation invariants."""

from pathlib import Path
from typing import Any

import pytest
from google import genai
from pydantic import SecretStr

from app.providers.gemini_client import (
    async_request_scoped_gemini_client,
    create_gemini_client,
    request_scoped_gemini_client,
)


def test_pyproject_has_no_database_drivers() -> None:
    pyproject = Path(__file__).resolve().parents[1] / "pyproject.toml"
    text = pyproject.read_text(encoding="utf-8").lower()
    for forbidden in ("psycopg", "sqlalchemy", "asyncpg"):
        assert forbidden not in text
    assert "google-genai" in text


def test_create_gemini_client_sets_single_attempt_retry_and_ms_timeout(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: list[dict[str, Any]] = []

    class FakeClient:
        def __init__(self, *, api_key: str, http_options: Any) -> None:
            captured.append({"api_key": api_key, "http_options": http_options})

    monkeypatch.setattr(genai, "Client", FakeClient)

    create_gemini_client(SecretStr("secret-alpha"), timeout_seconds=45, api_version="v1")
    create_gemini_client(SecretStr("secret-beta"), timeout_seconds=15)

    assert len(captured) == 2
    assert captured[0]["api_key"] == "secret-alpha"
    assert captured[1]["api_key"] == "secret-beta"
    opts_0 = captured[0]["http_options"]
    opts_1 = captured[1]["http_options"]
    assert opts_0.retry_options.attempts == 1
    assert opts_1.retry_options.attempts == 1
    assert opts_0.timeout == 45000
    assert opts_1.timeout == 15000
    assert opts_0.api_version == "v1"
    assert opts_1.api_version is None


def test_request_scoped_sync_client_closes_in_finally_even_on_error() -> None:
    closed_keys: list[str] = []

    class FakeSyncClient:
        def __init__(self, key: str) -> None:
            self.key = key

        def close(self) -> None:
            closed_keys.append(self.key)

    def fake_factory(cred: SecretStr, **_kwargs: Any) -> Any:
        return FakeSyncClient(cred.get_secret_value())

    with request_scoped_gemini_client(SecretStr("key-one"), factory=fake_factory) as c1:
        assert c1.key == "key-one"

    with pytest.raises(RuntimeError, match="boom"):
        with request_scoped_gemini_client(SecretStr("key-two"), factory=fake_factory):
            raise RuntimeError("boom")

    assert closed_keys == ["key-one", "key-two"]


@pytest.mark.anyio
async def test_request_scoped_async_client_closes_aio_and_sync_in_finally() -> None:
    events: list[str] = []

    class FakeAio:
        async def aclose(self) -> None:
            events.append("aio.aclose")

    class FakeAsyncClient:
        def __init__(self, key: str) -> None:
            self.key = key
            self.aio = FakeAio()

        def close(self) -> None:
            events.append("client.close")

    def fake_factory(cred: SecretStr, **_kwargs: Any) -> Any:
        return FakeAsyncClient(cred.get_secret_value())

    with pytest.raises(ValueError, match="stream failed"):
        async with async_request_scoped_gemini_client(
            SecretStr("key-async"), factory=fake_factory
        ) as client:
            assert client.key == "key-async"
            raise ValueError("stream failed")

    assert events == ["aio.aclose", "client.close"]
