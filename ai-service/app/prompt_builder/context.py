"""Request-scoped ContextVar bridge for passing BuiltPrompt without changing adapter signatures."""

from contextvars import ContextVar, Token

from app.prompt_builder.models import BuiltPrompt

_CURRENT_BUILT_PROMPT: ContextVar[BuiltPrompt | None] = ContextVar(
    "current_built_prompt", default=None
)


def set_current_built_prompt(built_prompt: BuiltPrompt) -> Token[BuiltPrompt | None]:
    """Bind the built prompt for the current async request context."""
    return _CURRENT_BUILT_PROMPT.set(built_prompt)


def get_current_built_prompt() -> BuiltPrompt | None:
    """Return the built prompt for the active request context, if any."""
    return _CURRENT_BUILT_PROMPT.get()


def reset_current_built_prompt(token: Token[BuiltPrompt | None]) -> None:
    """Restore the previous built prompt ContextVar value."""
    _CURRENT_BUILT_PROMPT.reset(token)
