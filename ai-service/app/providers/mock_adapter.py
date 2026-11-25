from app.providers.base import AdapterStatus


class MockProviderAdapter:
    """Local bootstrap adapter: no external requests, credentials or synthetic usage."""

    def status(self) -> AdapterStatus:
        return AdapterStatus(mode="MOCK", external_calls_enabled=False)
