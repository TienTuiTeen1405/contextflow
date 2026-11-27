from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr, ValidationError

from app.config import Settings, get_settings
from app.main import app

TOKEN = "test-service-token-at-least-24-characters"


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    monkeypatch.setenv("CF_INTERNAL_SERVICE_TOKEN", TOKEN)
    monkeypatch.setenv("CF_PROVIDER_MODE", "MOCK")
    get_settings.cache_clear()
    with TestClient(app) as test_client:
        yield test_client
    get_settings.cache_clear()


def test_internal_status_requires_the_service_token(client: TestClient) -> None:
    assert client.get("/internal/v1/ai/status").status_code == 401
    assert (
        client.get(
            "/internal/v1/ai/status", headers={"Authorization": "Bearer invalid"}
        ).status_code
        == 403
    )
    response = client.get("/internal/v1/ai/status", headers={"Authorization": f"Bearer {TOKEN}"})
    assert response.status_code == 200
    assert response.json() == {"mode": "MOCK", "external_calls_enabled": False}


def test_health_does_not_expose_configuration(client: TestClient) -> None:
    assert client.get("/health").json() == {"status": "UP", "service": "ai-service"}
    assert client.get("/openapi.json").status_code == 404


def test_gemini_interactions_mode_can_be_configured_and_invalid_mode_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    default_settings = Settings(internal_service_token=SecretStr(TOKEN))
    assert default_settings.provider_mode == "MOCK"

    monkeypatch.setenv("CF_INTERNAL_SERVICE_TOKEN", TOKEN)
    monkeypatch.setenv("CF_PROVIDER_MODE", "GEMINI_INTERACTIONS")
    get_settings.cache_clear()
    try:
        with TestClient(app) as gemini_client:
            response = gemini_client.get(
                "/internal/v1/ai/status", headers={"Authorization": f"Bearer {TOKEN}"}
            )
            assert response.status_code == 200
            assert response.json() == {
                "mode": "GEMINI_INTERACTIONS",
                "external_calls_enabled": True,
            }
    finally:
        get_settings.cache_clear()

    with pytest.raises(ValidationError):
        Settings(provider_mode="UNSUPPORTED_MODE", internal_service_token=TOKEN)  # type: ignore[arg-type]
