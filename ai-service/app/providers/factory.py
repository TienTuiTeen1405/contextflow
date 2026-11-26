from app.config import get_settings
from app.providers.base import ProviderAdapter
from app.providers.mock_adapter import MockProviderAdapter


def get_provider() -> ProviderAdapter:
    get_settings()
    return MockProviderAdapter()
