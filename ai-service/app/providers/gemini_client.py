"""Request-scoped Google Gen AI client factory with single-attempt retry policy."""

from collections.abc import AsyncIterator, Callable, Iterator
from contextlib import asynccontextmanager, contextmanager

from google import genai
from google.genai import types
from pydantic import SecretStr

GeminiClientBuilder = Callable[..., genai.Client]


def create_gemini_client(
    credential: SecretStr,
    *,
    timeout_seconds: float | None = None,
    api_version: str | None = None,
) -> genai.Client:
    """Create an isolated per-request Gemini SDK client with attempts=1 (no SDK retries)."""
    timeout_ms = max(1, int(round(timeout_seconds * 1000))) if timeout_seconds is not None else None
    http_options = types.HttpOptions(
        api_version=api_version,
        timeout=timeout_ms,
        retry_options=types.HttpRetryOptions(attempts=1),
    )
    return genai.Client(api_key=credential.get_secret_value(), http_options=http_options)


def _close_sync_client(client: object) -> None:
    close = getattr(client, "close", None)
    if close is not None:
        try:
            close()
        except Exception:
            pass


async def _close_async_client(client: object) -> None:
    aio = getattr(client, "aio", None)
    aclose = getattr(aio, "aclose", None) if aio is not None else None
    if aclose is not None:
        try:
            await aclose()
        except Exception:
            pass
    _close_sync_client(client)


@contextmanager
def request_scoped_gemini_client(
    credential: SecretStr,
    *,
    factory: GeminiClientBuilder = create_gemini_client,
    timeout_seconds: float | None = None,
    api_version: str | None = None,
) -> Iterator[genai.Client]:
    """Yield a synchronous request-scoped Gemini client and close it in finally."""
    client = factory(credential, timeout_seconds=timeout_seconds, api_version=api_version)
    try:
        yield client
    finally:
        _close_sync_client(client)


@asynccontextmanager
async def async_request_scoped_gemini_client(
    credential: SecretStr,
    *,
    factory: GeminiClientBuilder = create_gemini_client,
    timeout_seconds: float | None = None,
    api_version: str | None = None,
) -> AsyncIterator[genai.Client]:
    """Yield an async-capable request-scoped Gemini client and close it in finally."""
    client = factory(credential, timeout_seconds=timeout_seconds, api_version=api_version)
    try:
        yield client
    finally:
        await _close_async_client(client)
