from app.config import get_settings
from app.providers.base import ProviderAdapter
from app.providers.gemini_adapter import GeminiInteractionsAdapter
from app.providers.mock_adapter import MockProviderAdapter


def get_provider() -> ProviderAdapter:
    settings = get_settings()
    if settings.provider_mode == "GEMINI_INTERACTIONS":
        return GeminiInteractionsAdapter()
    return MockProviderAdapter()
