from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.config import Settings, get_settings
from app.main import app
from app.providers.factory import get_provider

TOKEN = "test-service-token-at-least-24-characters"


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    monkeypatch.setenv("CF_INTERNAL_SERVICE_TOKEN", TOKEN)
    monkeypatch.setenv("CF_PROVIDER_MODE", "MOCK")
    get_settings.cache_clear()
    get_provider.cache_clear()
    with TestClient(app) as test_client:
        yield test_client
    get_settings.cache_clear()
    get_provider.cache_clear()


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


def test_real_provider_cannot_be_enabled_before_poc() -> None:
    with pytest.raises(ValidationError):
        Settings(provider_mode="GEMINI_INTERACTIONS", internal_service_token=TOKEN)  # type: ignore[arg-type]
