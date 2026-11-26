"""Tests verifying credential redaction in logs, tracebacks, and HTTP error responses."""

import json
import logging
from collections.abc import AsyncIterator, Iterator
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.core.redaction import (
    REDACTED,
    install_logging_redaction,
    redact_text,
    redacted_secrets_scope,
)
from app.main import app
from app.providers.factory import get_provider
from app.providers.mock_adapter import MockProviderAdapter
from app.schemas import contracts

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "contracts" / "fixtures" / "internal"
TOKEN = "test-service-token-at-least-24-characters"


def load_fixture(name: str) -> dict[str, Any]:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))  # type: ignore[no-any-return]


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


def test_redact_text_and_logging_filter_scrub_dynamic_and_static_secrets(
    caplog: pytest.LogCaptureFixture,
) -> None:
    install_logging_redaction()
    dynamic_secret = "custom-secret-key-not-aiza-987654"
    aiza_key = "AIzaSyTestFakeKey1234567890abcdef"
    custom_logger = logging.getLogger("app.test.redaction.unit")

    with caplog.at_level(logging.DEBUG):
        with redacted_secrets_scope([dynamic_secret]):
            custom_logger.debug(
                "Debug prompt log with key=%s and static=%s and auth=Bearer my-token-123",
                dynamic_secret,
                aiza_key,
            )
            custom_logger.info("Split boundary secret: custom-secret-%s", "key-not-aiza-987654")
            try:
                raise RuntimeError(f"Upstream call failed for {dynamic_secret} and {aiza_key}")
            except RuntimeError:
                custom_logger.exception("Caught exception with secret=%s", dynamic_secret)

    assert dynamic_secret not in caplog.text
    assert aiza_key not in caplog.text
    assert "my-token-123" not in caplog.text
    assert REDACTED in caplog.text
    assert "RuntimeError" in caplog.text
    for record in caplog.records:
        assert dynamic_secret not in record.getMessage()
        assert aiza_key not in record.getMessage()
        assert record.exc_info is None
        if record.exc_text:
            assert dynamic_secret not in record.exc_text
            assert aiza_key not in record.exc_text

    # ContextVar scope is cleared outside the context manager.
    assert redact_text(dynamic_secret) == dynamic_secret
    assert redact_text(aiza_key) == REDACTED


def test_streaming_exception_redacts_provider_credential_and_service_token(
    client: TestClient, caplog: pytest.LogCaptureFixture
) -> None:
    secret = "synthetic-stream-credential-secret-9911"
    test_logger = logging.getLogger("app.test.stream.redaction")

    class LoggingBrokenStreamAdapter(MockProviderAdapter):
        async def stream(self, request: contracts.StreamInput) -> AsyncIterator[tuple[str, Any]]:
            test_logger.debug("Debug prompt dump header=%s token=%s", secret, TOKEN)
            async for event in super().stream(request):
                yield event
                try:
                    raise RuntimeError(f"Stream exploded using key={secret} bearer=Bearer {TOKEN}")
                except RuntimeError:
                    test_logger.exception("Stream adapter failure with %s", secret)
                    raise

    app.dependency_overrides[get_provider] = LoggingBrokenStreamAdapter
    with caplog.at_level(logging.DEBUG):
        response = client.post(
            "/internal/v1/ai/streams",
            json=load_fixture("stream-input.json"),
            headers={"Authorization": f"Bearer {TOKEN}", "X-Provider-Credential": secret},
        )

    assert response.status_code == 200
    assert "provider.failed" in response.text
    assert secret not in response.text
    assert TOKEN not in response.text
    assert secret not in caplog.text
    assert TOKEN not in caplog.text
    assert REDACTED in caplog.text


def test_generate_and_verify_exceptions_redact_provider_credential(
    client: TestClient, caplog: pytest.LogCaptureFixture
) -> None:
    secret = "AIzaSySyntheticProviderSecretKey123456"
    test_logger = logging.getLogger("app.test.generate.redaction")

    class LoggingBrokenAdapter(MockProviderAdapter):
        def generate(self, request: contracts.GenerateInput) -> contracts.GenerateResult:
            test_logger.debug("Generating prompt with credential=%s", secret)
            try:
                raise ValueError(f"Bad credential {secret}")
            except ValueError:
                test_logger.exception("Generate traceback for %s", secret)
                raise

        def verify(self, request: contracts.VerifyInput, credential: Any) -> contracts.VerifyResult:
            raw = credential.get_secret_value()
            try:
                raise RuntimeError(f"Verify network error with {raw}")
            except RuntimeError:
                test_logger.exception("Verify traceback for %s", raw)
                raise

    app.dependency_overrides[get_provider] = LoggingBrokenAdapter
    headers = {"Authorization": f"Bearer {TOKEN}", "X-Provider-Credential": secret}

    with caplog.at_level(logging.DEBUG):
        gen_resp = client.post(
            "/internal/v1/ai/generate", json=load_fixture("generate-input.json"), headers=headers
        )
        verify_resp = client.post(
            "/internal/v1/ai/credentials/verify",
            json=load_fixture("verify-input.json"),
            headers=headers,
        )

    assert gen_resp.status_code == 502
    assert verify_resp.status_code == 502
    assert secret not in gen_resp.text
    assert secret not in verify_resp.text
    assert secret not in caplog.text
    assert REDACTED in caplog.text


def test_unhandled_dependency_exception_is_scrubbed_in_logs_and_http_response(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    monkeypatch.setenv("CF_INTERNAL_SERVICE_TOKEN", TOKEN)
    monkeypatch.setenv("CF_PROVIDER_MODE", "MOCK")
    get_settings.cache_clear()
    app.dependency_overrides.clear()

    secret = "unhandled-x-provider-credential-secret-777"

    def broken_provider() -> MockProviderAdapter:
        raise RuntimeError(f"Unhandled dependency failure for {secret} and Bearer {TOKEN}")

    app.dependency_overrides[get_provider] = broken_provider
    try:
        with TestClient(app, raise_server_exceptions=False) as safe_client:
            with caplog.at_level(logging.DEBUG):
                response = safe_client.post(
                    "/internal/v1/ai/generate",
                    json=load_fixture("generate-input.json"),
                    headers={
                        "Authorization": f"Bearer {TOKEN}",
                        "X-Provider-Credential": secret,
                    },
                )
        assert response.status_code == 502
        body = response.json()
        assert body["error"]["error_code"] == "ADAPTER_UNAVAILABLE"
        assert secret not in response.text
        assert TOKEN not in response.text
        assert secret not in caplog.text
        assert TOKEN not in caplog.text
        assert REDACTED in caplog.text
    finally:
        app.dependency_overrides.clear()
        get_settings.cache_clear()
