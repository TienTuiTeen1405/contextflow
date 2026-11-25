from functools import lru_cache

from app.config import get_settings
from app.providers.base import ProviderAdapter
from app.providers.mock_adapter import MockProviderAdapter


@lru_cache
def get_provider() -> ProviderAdapter:
    get_settings()  # Validate mode before enabling any adapter.
    return MockProviderAdapter()
