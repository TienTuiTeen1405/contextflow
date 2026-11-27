"""Request-scoped credential redaction logging filter, traceback scrubber, and ASGI middleware."""

import logging
import re
import traceback
from collections.abc import Callable, Iterable, Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.api.internal.errors import failure_response
from app.config import get_settings

REDACTED = "[REDACTED]"
_MIN_DYNAMIC_SECRET_LEN = 4

_REDACTED_SECRETS: ContextVar[frozenset[str]] = ContextVar("redacted_secrets", default=frozenset())

_STATIC_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"AIza[0-9A-Za-z\-_]{10,}"), REDACTED),
    (re.compile(r"\bBearer\s+[A-Za-z0-9\-._~+/]+=*", re.IGNORECASE), f"Bearer {REDACTED}"),
    (
        re.compile(
            r"(x-provider-credential['\"]?\s*[:=]\s*['\"]?)([^\s'\",;}\]]+)",
            re.IGNORECASE,
        ),
        rf"\1{REDACTED}",
    ),
    (
        re.compile(
            r"(authorization['\"]?\s*[:=]\s*['\"]?(?:bearer\s+)?)([^\s'\",;}\]]+)",
            re.IGNORECASE,
        ),
        rf"\1{REDACTED}",
    ),
    (re.compile(r"\bsk-[A-Za-z0-9\-_]{12,}\b"), REDACTED),
)

_FILTER_INSTANCE: "CredentialRedactionFilter | None" = None
_BASE_LOG_RECORD_FACTORY: Callable[..., logging.LogRecord] | None = None
_REDACTED_FACTORY_INSTALLED = False

_logger = logging.getLogger(__name__)


def get_redacted_secrets() -> frozenset[str]:
    """Return active request-scoped secret strings registered for redaction."""
    return _REDACTED_SECRETS.get()


@contextmanager
def redacted_secrets_scope(secrets_iterable: Iterable[str]) -> Iterator[None]:
    """Register secret values for redaction in the active async context and clear on exit."""
    current = _REDACTED_SECRETS.get()
    additions = {
        secret
        for item in secrets_iterable
        if item
        for secret in (item, item.strip())
        if len(secret) >= _MIN_DYNAMIC_SECRET_LEN
    }
    if not additions:
        yield
        return
    token = _REDACTED_SECRETS.set(current | frozenset(additions))
    try:
        yield
    finally:
        _REDACTED_SECRETS.reset(token)


def redact_text(text: str) -> str:
    """Scrub dynamic request secrets and known credential patterns from a string."""
    result = text
    dynamic_secrets = _REDACTED_SECRETS.get()
    if dynamic_secrets:
        for secret in sorted(dynamic_secrets, key=len, reverse=True):
            if secret in result:
                result = result.replace(secret, REDACTED)
    for pattern, replacement in _STATIC_PATTERNS:
        result = pattern.sub(replacement, result)
    return result


def redact_value(value: object) -> object:
    """Recursively scrub strings within tuple/list/dict log arguments."""
    if isinstance(value, str):
        return redact_text(value)
    if isinstance(value, tuple):
        return tuple(redact_value(item) for item in value)
    if isinstance(value, list):
        return [redact_value(item) for item in value]
    if isinstance(value, dict):
        return {redact_value(k): redact_value(v) for k, v in value.items()}
    return value


def scrub_traceback(exc_info: tuple[Any, Any, Any]) -> str:
    """Format an exc_info tuple and scrub all secrets from the traceback output."""
    formatted = "".join(traceback.format_exception(*exc_info)).rstrip("\n")
    return redact_text(formatted)


def scrub_log_record(record: logging.LogRecord) -> None:
    """Scrub message, arguments, stack info, and exception tracebacks in place."""
    raw_formatted: str | None = None
    if record.args:
        try:
            raw_formatted = str(record.msg) % record.args
        except Exception:
            raw_formatted = None

    if isinstance(record.msg, str):
        record.msg = redact_text(record.msg)
    else:
        record.msg = redact_text(str(record.msg))

    if record.args:
        record.args = redact_value(record.args)  # type: ignore[assignment]
        if raw_formatted is not None:
            scrubbed_combined = redact_text(raw_formatted)
            try:
                post_formatted = str(record.msg) % record.args
            except Exception:
                post_formatted = ""
            if post_formatted != scrubbed_combined and scrubbed_combined != raw_formatted:
                record.msg = scrubbed_combined
                record.args = ()

    if record.exc_text:
        record.exc_text = redact_text(record.exc_text)

    if record.exc_info and any(record.exc_info):
        record.exc_text = scrub_traceback(record.exc_info)
        record.exc_info = None

    if record.stack_info:
        record.stack_info = redact_text(record.stack_info)


class CredentialRedactionFilter(logging.Filter):
    """Logging filter scrubbing registered credentials and key shapes from every LogRecord."""

    def filter(self, record: logging.LogRecord) -> bool:
        scrub_log_record(record)
        return True


def _get_filter() -> CredentialRedactionFilter:
    global _FILTER_INSTANCE
    if _FILTER_INSTANCE is None:
        _FILTER_INSTANCE = CredentialRedactionFilter()
    return _FILTER_INSTANCE


def _attach_filter_to_logger(target: logging.Logger) -> None:
    redaction_filter = _get_filter()
    if redaction_filter not in target.filters:
        target.addFilter(redaction_filter)
    for handler in target.handlers:
        if redaction_filter not in handler.filters:
            handler.addFilter(redaction_filter)


def install_logging_redaction() -> None:
    """Idempotently install global LogRecord factory scrubbing and logger/handler filters."""
    global _BASE_LOG_RECORD_FACTORY, _REDACTED_FACTORY_INSTALLED
    if not _REDACTED_FACTORY_INSTALLED:
        _BASE_LOG_RECORD_FACTORY = logging.getLogRecordFactory()
        base_factory = _BASE_LOG_RECORD_FACTORY

        def redacting_factory(*args: Any, **kwargs: Any) -> logging.LogRecord:
            record = base_factory(*args, **kwargs)
            scrub_log_record(record)
            return record

        logging.setLogRecordFactory(redacting_factory)
        _REDACTED_FACTORY_INSTALLED = True

    for logger_name in ("", "app", "uvicorn", "uvicorn.error", "uvicorn.access", "fastapi"):
        _attach_filter_to_logger(logging.getLogger(logger_name))


class RedactedError(RuntimeError):
    """Sanitized exception raised after scrubbing raw secrets from an unhandled error."""


def _extract_request_secrets(headers: list[tuple[bytes, bytes]]) -> list[str]:
    secrets_found: list[str] = []
    for raw_name, raw_value in headers:
        name = raw_name.decode("latin-1").lower()
        value = raw_value.decode("latin-1")
        if name == "x-provider-credential" and value:
            secrets_found.append(value)
        elif name == "authorization" and value:
            secrets_found.append(value)
            if value.lower().startswith("bearer "):
                token = value[7:].strip()
                if token:
                    secrets_found.append(token)
    try:
        configured_token = get_settings().internal_service_token.get_secret_value()
        if configured_token:
            secrets_found.append(configured_token)
    except Exception:
        pass
    return secrets_found


class RedactionMiddleware:
    """Outermost ASGI middleware registering request credentials and scrubbing unhandled errors."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        headers: list[tuple[bytes, bytes]] = list(scope.get("headers", ()))
        request_secrets = _extract_request_secrets(headers)

        async def send_wrapper(message: Message) -> None:
            await send(message)

        with redacted_secrets_scope(request_secrets):
            try:
                await self.app(scope, receive, send_wrapper)
            except Exception as exc:
                _logger.exception("Unhandled error during internal AI request")
                scrubbed_msg = redact_text(f"{type(exc).__name__}: {exc}")
                raise RedactedError(scrubbed_msg) from None


async def unhandled_error(_: Request, __: Exception) -> JSONResponse:
    """Safe fallback handler for unhandled server exceptions without leaking details."""
    return failure_response(
        502,
        "ADAPTER_UNAVAILABLE",
        category="UNKNOWN_REQUIRES_REVIEW",
    )


def install_redaction(app: FastAPI) -> None:
    """Install logging redaction, ASGI credential middleware, and unhandled error scrubber."""
    install_logging_redaction()
    app.add_exception_handler(Exception, unhandled_error)
    app.add_middleware(RedactionMiddleware)
