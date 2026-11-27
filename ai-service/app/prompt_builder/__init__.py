"""Prompt A-E serialization; keep B/D outside system_instruction and escape XML delimiters."""

from app.prompt_builder.builder import PromptBuilder, build_prompt, get_prompt_builder
from app.prompt_builder.context import (
    get_current_built_prompt,
    reset_current_built_prompt,
    set_current_built_prompt,
)
from app.prompt_builder.models import BuiltPrompt, BuiltTurn, PromptContextSummary
from app.prompt_builder.xml import escape_xml, escape_xml_attr

__all__ = [
    "BuiltPrompt",
    "BuiltTurn",
    "PromptBuilder",
    "PromptContextSummary",
    "build_prompt",
    "escape_xml",
    "escape_xml_attr",
    "get_current_built_prompt",
    "get_prompt_builder",
    "reset_current_built_prompt",
    "set_current_built_prompt",
]
