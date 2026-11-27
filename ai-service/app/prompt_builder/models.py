"""Typed outputs produced by PromptBuilder from a validated PromptEnvelope."""

from dataclasses import dataclass, field
from typing import Literal

from app.schemas.contracts import NativeStep


@dataclass(frozen=True)
class BuiltTurn:
    """A single turn ready for downstream provider adapters."""

    role: Literal["user", "model"]
    content: str | None = None
    native_output_steps: list[NativeStep] = field(default_factory=list)
    turn_ref: str | None = None
    is_current: bool = False


@dataclass(frozen=True)
class PromptContextSummary:
    """Factual metadata summary of compiled context blocks received in the request."""

    mandatory_rules_count: int
    overridable_rules_count: int
    active_skills_count: int
    has_personal_instructions: bool
    has_project_information: bool
    has_personal_profile: bool
    history_sources_count: int
    knowledge_base_sources_count: int
    source_of_truth_sources_count: int
    explicit_references_count: int
    continuity_turns_count: int
    historical_quotes_count: int
    current_user_blocks_count: int
    primary_intent: str
    evidence_sufficiency: str
    retrieval_status: str


@dataclass(frozen=True)
class BuiltPrompt:
    """Compiled prompt preserving strict separation between system instructions and data."""

    system_instruction: str
    input_turns: list[BuiltTurn]
    summary: PromptContextSummary
