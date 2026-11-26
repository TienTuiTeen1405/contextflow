"""Request-scoped credential redaction for logs, tracebacks, and unhandled errors."""

from app.core.redaction import (
    REDACTED,
    CredentialRedactionFilter,
    RedactedError,
    RedactionMiddleware,
    get_redacted_secrets,
    install_logging_redaction,
    install_redaction,
    redact_text,
    redact_value,
    redacted_secrets_scope,
    scrub_log_record,
)

__all__ = [
    "REDACTED",
    "CredentialRedactionFilter",
    "RedactedError",
    "RedactionMiddleware",
    "get_redacted_secrets",
    "install_logging_redaction",
    "install_redaction",
    "redact_text",
    "redact_value",
    "redacted_secrets_scope",
    "scrub_log_record",
]
