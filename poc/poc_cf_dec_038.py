# /// script
# requires-python = ">=3.12"
# dependencies = [
#     "google-genai>=3.5.0,<4",
#     "python-dotenv>=1.0,<2",
# ]
# ///
"""PoC Gate CF-DEC-038 experimental verification script for ContextFlow V6.

Supports:
- `--self-test`: offline deterministic verification across all 5 PoC checks + redaction.
- `--write-report`: live execution against Gemini API using `GEMINI_KEY_A` and `GEMINI_KEY_B`.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
import time
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from dotenv import dotenv_values
from google import genai
from google.genai import errors as genai_errors
from google.genai import types

_POC_EXECUTION_ERRORS = (
    genai_errors.APIError,
    genai_errors.ContinuationInterruptedError,
    OSError,
    RuntimeError,
    TypeError,
    ValueError,
)

DEFAULT_TEXT_MODEL = "gemini-3.8-flash"
DEFAULT_EMBEDDING_MODEL = "gemini-embedding-2"
DEFAULT_REPORT_PATH = Path(__file__).resolve().parent / "CF-DEC-038-REPORT.md"
REDACTED = "[REDACTED]"

_PLACEHOLDER_PREFIXES = (
    "replace-with-",
    "your-",
    "example-",
    "dummy-",
    "test-placeholder",
)
_STATIC_SECRET_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"AIza[0-9A-Za-z\-_]{10,}"), REDACTED),
    (
        re.compile(r"\bBearer\s+[A-Za-z0-9\-._~+/]+=*", re.IGNORECASE),
        f"Bearer {REDACTED}",
    ),
    (
        re.compile(r"(api_key['\"]?\s*[:=]\s*['\"]?)([^\s'\",;}\]]+)", re.IGNORECASE),
        rf"\1{REDACTED}",
    ),
)


class SecretRedactor:
    """Redacts exact API keys and key-like tokens from logs, payloads, and reports."""

    def __init__(self, secrets: Iterable[str] = ()) -> None:
        self._secrets: tuple[str, ...] = tuple(
            sorted(
                {s.strip() for s in secrets if s and len(s.strip()) >= 4},
                key=len,
                reverse=True,
            )
        )

    def scrub_text(self, text: str) -> str:
        result = text
        for secret in self._secrets:
            if secret in result:
                result = result.replace(secret, REDACTED)
        for pattern, replacement in _STATIC_SECRET_PATTERNS:
            result = pattern.sub(replacement, result)
        return result

    def scrub_value(self, value: Any) -> Any:
        if isinstance(value, str):
            return self.scrub_text(value)
        if isinstance(value, list):
            return [self.scrub_value(item) for item in value]
        if isinstance(value, tuple):
            return [self.scrub_value(item) for item in value]
        if isinstance(value, dict):
            return {self.scrub_value(k): self.scrub_value(v) for k, v in value.items()}
        return value


@dataclass(frozen=True)
class PoCConfig:
    """Resolved runtime configuration for PoC execution."""

    key_a: str
    key_b: str
    text_model: str = DEFAULT_TEXT_MODEL
    embedding_model: str = DEFAULT_EMBEDDING_MODEL


@dataclass
class PoCTestResult:
    """Structured result for one PoC Gate CF-DEC-038 verification item."""

    test_id: str
    name: str
    passed: bool
    summary: str
    metrics: dict[str, Any] = field(default_factory=dict)
    evidence: dict[str, Any] = field(default_factory=dict)


@dataclass
class PoCRunResult:
    """Aggregate PoC Gate result across all 5 mandatory checks."""

    mode: str
    executed_at_utc: str
    sdk_version: str
    text_model: str
    embedding_model: str
    tests: list[PoCTestResult]
    t2b_cross_key_accepted: bool | None

    @property
    def gate_decision(self) -> str:
        return "PASS" if all(item.passed for item in self.tests) else "BLOCKER"


def _is_usable_secret(value: str | None) -> bool:
    if not value:
        return False
    stripped = value.strip()
    if not stripped:
        return False
    return not any(stripped.lower().startswith(prefix) for prefix in _PLACEHOLDER_PREFIXES)


def load_env_config(
    repo_root: Path | None = None,
) -> tuple[PoCConfig | None, list[str]]:
    """Load GEMINI_KEY_A, GEMINI_KEY_B, and model settings without printing secrets."""
    root = repo_root or Path(__file__).resolve().parents[1]
    merged: dict[str, str] = {}
    for candidate in (root / ".env", root / "poc" / ".env"):
        if candidate.exists():
            for key, value in dotenv_values(candidate).items():
                if value is not None:
                    merged[key] = value
    for key in (
        "GEMINI_KEY_A",
        "GEMINI_KEY_B",
        "GEMINI_TEXT_MODEL",
        "GEMINI_EMBEDDING_MODEL",
    ):
        env_val = os.environ.get(key)
        if env_val is not None:
            merged[key] = env_val

    missing: list[str] = []
    key_a = merged.get("GEMINI_KEY_A", "").strip()
    key_b = merged.get("GEMINI_KEY_B", "").strip()
    if not _is_usable_secret(key_a):
        missing.append("GEMINI_KEY_A")
    if not _is_usable_secret(key_b):
        missing.append("GEMINI_KEY_B")
    if missing:
        return None, missing

    return (
        PoCConfig(
            key_a=key_a,
            key_b=key_b,
            text_model=merged.get("GEMINI_TEXT_MODEL", DEFAULT_TEXT_MODEL).strip()
            or DEFAULT_TEXT_MODEL,
            embedding_model=merged.get("GEMINI_EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL).strip()
            or DEFAULT_EMBEDDING_MODEL,
        ),
        [],
    )


def create_gemini_client(api_key: str) -> genai.Client:
    """Create a request-scoped Google Gen AI client with retry attempts locked to 1."""
    return genai.Client(
        api_key=api_key,
        http_options=types.HttpOptions(retry_options=types.HttpRetryOptions(attempts=1)),
    )


def _dump_obj(obj: Any) -> Any:
    if obj is None:
        return None
    if hasattr(obj, "model_dump"):
        return obj.model_dump(mode="json", exclude_none=True)
    if isinstance(obj, dict):
        return dict(obj)
    return str(obj)


def _extract_attr_or_key(obj: Any, *names: str) -> Any:
    for name in names:
        if isinstance(obj, dict) and name in obj:
            return obj[name]
        if hasattr(obj, name):
            return getattr(obj, name)
    return None


def analyze_usage(usage_obj: Any) -> dict[str, Any]:
    """Inspect provider usage preserving explicit nulls and checking thought-token inclusion."""
    if usage_obj is None:
        return {
            "raw": None,
            "input_tokens": None,
            "output_tokens": None,
            "total_tokens": None,
            "cached_tokens": None,
            "reasoning_tokens": None,
            "total_includes_reasoning": None,
        }
    raw = _dump_obj(usage_obj)
    input_tokens = _extract_attr_or_key(usage_obj, "total_input_tokens", "input_tokens")
    output_tokens = _extract_attr_or_key(usage_obj, "total_output_tokens", "output_tokens")
    total_tokens = _extract_attr_or_key(usage_obj, "total_tokens")
    cached_tokens = _extract_attr_or_key(usage_obj, "total_cached_tokens", "cached_tokens")
    reasoning_tokens = _extract_attr_or_key(
        usage_obj, "total_thought_tokens", "reasoning_tokens", "thought_tokens"
    )

    total_includes_reasoning: bool | None = None
    if (
        isinstance(total_tokens, int)
        and isinstance(input_tokens, int)
        and isinstance(output_tokens, int)
        and isinstance(reasoning_tokens, int)
        and reasoning_tokens > 0
    ):
        if total_tokens == input_tokens + output_tokens + reasoning_tokens:
            total_includes_reasoning = True
        elif total_tokens == input_tokens + output_tokens:
            total_includes_reasoning = False

    return {
        "raw": raw,
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "total_tokens": total_tokens,
        "cached_tokens": cached_tokens,
        "reasoning_tokens": reasoning_tokens,
        "total_includes_reasoning": total_includes_reasoning,
    }


def _reconstruct_steps_from_stream(
    completed_interaction: Any,
    started_steps: dict[int, dict[str, Any]],
    text_deltas: dict[int, list[str]],
    thought_deltas: dict[int, list[str]],
    thought_signatures: dict[int, str],
) -> list[dict[str, Any]]:
    raw_steps = _extract_attr_or_key(completed_interaction, "steps")
    if raw_steps:
        return [_dump_obj(step) for step in raw_steps]

    reconstructed: list[dict[str, Any]] = []
    for index in sorted(started_steps):
        step = dict(started_steps[index])
        step_type = step.get("type")
        if step_type == "model_output" and index in text_deltas:
            step["content"] = [{"type": "text", "text": "".join(text_deltas[index])}]
        elif step_type == "thought":
            if index in thought_signatures:
                step["signature"] = thought_signatures[index]
            if index in thought_deltas:
                step["summary"] = [{"type": "text", "text": "".join(thought_deltas[index])}]
        reconstructed.append(step)
    return reconstructed


def _extract_interaction_text(steps: list[dict[str, Any]]) -> str:
    parts: list[str] = []
    for step in steps:
        if step.get("type") == "model_output":
            for item in step.get("content") or []:
                if isinstance(item, dict) and item.get("type") == "text":
                    parts.append(str(item.get("text", "")))
    return "".join(parts)


def run_poc(
    config: PoCConfig,
    *,
    client_factory: Callable[[str], Any] = create_gemini_client,
    mode: str = "LIVE",
) -> PoCRunResult:
    """Execute all 5 mandatory CF-DEC-038 PoC tests and return redacted results."""
    redactor = SecretRedactor([config.key_a, config.key_b])
    client_a = client_factory(config.key_a)
    client_b = client_factory(config.key_b)
    sdk_version = getattr(genai, "__version__", "unknown")
    executed_at = datetime.now(UTC).isoformat().replace("+00:00", "Z")

    tests: list[PoCTestResult] = []
    turn1_steps: list[dict[str, Any]] = []
    completed_interaction: Any = None
    memory_token = "CF-DEC-038-ANCHOR-749"

    # Test 1: Streaming & store=false
    t_start = time.perf_counter()
    ttft_ms: float | None = None
    event_types: list[str] = []
    stream_text_chunks: list[str] = []
    started_steps: dict[int, dict[str, Any]] = {}
    text_deltas: dict[int, list[str]] = {}
    thought_deltas: dict[int, list[str]] = {}
    thought_signatures: dict[int, str] = {}

    try:
        stream = client_a.interactions.create(
            model=config.text_model,
            system_instruction=(
                "You are verifying ContextFlow stateless streaming. Keep replies brief and exact."
            ),
            input=(
                f"Acknowledge token {memory_token} in one short sentence "
                "and explain stateless store=false in one phrase."
            ),
            store=False,
            stream=True,
        )
        for event in stream:
            etype = str(_extract_attr_or_key(event, "event_type") or "")
            event_types.append(etype)
            if etype == "step.start":
                idx = int(_extract_attr_or_key(event, "index") or 0)
                started_steps[idx] = _dump_obj(_extract_attr_or_key(event, "step")) or {}
            elif etype == "step.delta":
                idx = int(_extract_attr_or_key(event, "index") or 0)
                delta = _extract_attr_or_key(event, "delta")
                dtype = _extract_attr_or_key(delta, "type")
                if dtype == "text":
                    chunk = str(_extract_attr_or_key(delta, "text") or "")
                    if chunk:
                        if ttft_ms is None:
                            ttft_ms = round((time.perf_counter() - t_start) * 1000.0, 2)
                        stream_text_chunks.append(chunk)
                        text_deltas.setdefault(idx, []).append(chunk)
                elif dtype == "thought_summary":
                    content = _extract_attr_or_key(delta, "content")
                    text_val = _extract_attr_or_key(content, "text")
                    if text_val:
                        thought_deltas.setdefault(idx, []).append(str(text_val))
                elif dtype == "thought_signature":
                    sig = _extract_attr_or_key(delta, "signature")
                    if sig:
                        thought_signatures[idx] = str(sig)
            elif etype == "interaction.completed":
                completed_interaction = _extract_attr_or_key(event, "interaction")

        stream_text = "".join(stream_text_chunks)
        turn1_steps = _reconstruct_steps_from_stream(
            completed_interaction,
            started_steps,
            text_deltas,
            thought_deltas,
            thought_signatures,
        )
        test1_pass = (
            "interaction.completed" in event_types
            and len(stream_text_chunks) >= 1
            and bool(stream_text.strip())
            and ttft_ms is not None
        )
        tests.append(
            PoCTestResult(
                test_id="Test 1",
                name="Streaming & store=false",
                passed=test1_pass,
                summary=(
                    f"Received {len(stream_text_chunks)} text deltas; "
                    f"TTFT={ttft_ms}ms; completed={'interaction.completed' in event_types}."
                ),
                metrics={
                    "ttft_ms": ttft_ms,
                    "delta_count": len(stream_text_chunks),
                    "event_types_seen": sorted(set(event_types)),
                },
                evidence=redactor.scrub_value(
                    {
                        "stream_text_preview": stream_text[:240],
                        "step_types": [s.get("type") for s in turn1_steps],
                    }
                ),
            )
        )
    except _POC_EXECUTION_ERRORS as exc:
        tests.append(
            PoCTestResult(
                test_id="Test 1",
                name="Streaming & store=false",
                passed=False,
                summary=redactor.scrub_text(f"Streaming error: {type(exc).__name__}: {exc}"),
                evidence=redactor.scrub_value({"error": str(exc)}),
            )
        )

    # Test 2: Completion & Usage inspection
    usage_obj = _extract_attr_or_key(completed_interaction, "usage")
    usage_info = analyze_usage(usage_obj)
    test2_pass = (
        completed_interaction is not None
        and usage_info["raw"] is not None
        and (
            usage_info["input_tokens"] is not None
            or usage_info["output_tokens"] is not None
            or usage_info["total_tokens"] is not None
        )
    )
    tests.append(
        PoCTestResult(
            test_id="Test 2",
            name="Completion & Usage Null Semantics",
            passed=test2_pass,
            summary=(
                f"usage present={usage_info['raw'] is not None}; "
                f"total_includes_reasoning={usage_info['total_includes_reasoning']}."
            ),
            metrics={
                "input_tokens": usage_info["input_tokens"],
                "output_tokens": usage_info["output_tokens"],
                "total_tokens": usage_info["total_tokens"],
                "cached_tokens": usage_info["cached_tokens"],
                "reasoning_tokens": usage_info["reasoning_tokens"],
                "total_includes_reasoning": usage_info["total_includes_reasoning"],
            },
            evidence=redactor.scrub_value({"raw_usage": usage_info["raw"]}),
        )
    )

    # Ensure Turn 1 has native steps even if streaming omitted them (fallback non-stream check)
    if not turn1_steps:
        try:
            turn1_nonstream = client_a.interactions.create(
                model=config.text_model,
                input=f"Remember token {memory_token}. Reply with OK and the token.",
                store=False,
                stream=False,
            )
            turn1_steps = [
                _dump_obj(s) for s in (_extract_attr_or_key(turn1_nonstream, "steps") or [])
            ]
        except _POC_EXECUTION_ERRORS:
            turn1_steps = []

    has_thought_signature = any(
        isinstance(step, dict) and step.get("type") == "thought" and bool(step.get("signature"))
        for step in turn1_steps
    )

    # Test 3: Stateless Multi-Turn Native Continuity (Key A -> Key A)
    replay_payload = [
        *turn1_steps,
        {
            "type": "user_input",
            "content": [
                {
                    "type": "text",
                    "text": (
                        "What exact CF-DEC-038 anchor token did I ask you to acknowledge "
                        "in the previous turn? Reply with the exact token."
                    ),
                }
            ],
        },
    ]
    try:
        turn2 = client_a.interactions.create(
            model=config.text_model,
            input=replay_payload,
            store=False,
            stream=False,
        )
        turn2_steps = [_dump_obj(s) for s in (_extract_attr_or_key(turn2, "steps") or [])]
        turn2_text = str(
            _extract_attr_or_key(turn2, "output_text") or _extract_interaction_text(turn2_steps)
        )
        recalled = memory_token in turn2_text
        tests.append(
            PoCTestResult(
                test_id="Test 3",
                name="Stateless Multi-Turn Native Continuity",
                passed=bool(turn1_steps) and recalled,
                summary=(
                    f"Replayed {len(turn1_steps)} native steps (has_thought_signature="
                    f"{has_thought_signature}); recalled_token={recalled}."
                ),
                metrics={
                    "replayed_steps_count": len(turn1_steps),
                    "has_thought_signature": has_thought_signature,
                    "recalled_token": recalled,
                },
                evidence=redactor.scrub_value(
                    {
                        "replayed_step_types": [s.get("type") for s in turn1_steps],
                        "turn2_output_preview": turn2_text[:240],
                    }
                ),
            )
        )
    except _POC_EXECUTION_ERRORS as exc:
        tests.append(
            PoCTestResult(
                test_id="Test 3",
                name="Stateless Multi-Turn Native Continuity",
                passed=False,
                summary=redactor.scrub_text(
                    f"Multi-turn replay failed: {type(exc).__name__}: {exc}"
                ),
                evidence=redactor.scrub_value({"error": str(exc)}),
            )
        )

    # Test 4: Key Rotation T2B (Key A steps -> Key B replay)
    t2b_accepted: bool | None = None
    try:
        turn_b = client_b.interactions.create(
            model=config.text_model,
            input=replay_payload,
            store=False,
            stream=False,
        )
        turn_b_steps = [_dump_obj(s) for s in (_extract_attr_or_key(turn_b, "steps") or [])]
        turn_b_text = str(
            _extract_attr_or_key(turn_b, "output_text") or _extract_interaction_text(turn_b_steps)
        )
        t2b_accepted = memory_token in turn_b_text
        tests.append(
            PoCTestResult(
                test_id="Test 4",
                name="Key Rotation T2B (Cross-Key Replay)",
                passed=True,
                summary=(
                    f"Key B accepted Key A native_output_steps={t2b_accepted} "
                    f"(has_thought_signature={has_thought_signature})."
                ),
                metrics={
                    "cross_key_accepted": t2b_accepted,
                    "has_thought_signature": has_thought_signature,
                    "epoch_invalidation_required": not t2b_accepted,
                },
                evidence=redactor.scrub_value({"turn_b_output_preview": turn_b_text[:240]}),
            )
        )
    except _POC_EXECUTION_ERRORS as exc:
        # Cross-key thought_signature rejection is an expected empirical outcome for T2B.
        t2b_accepted = False
        tests.append(
            PoCTestResult(
                test_id="Test 4",
                name="Key Rotation T2B (Cross-Key Replay)",
                passed=bool(turn1_steps),
                summary=redactor.scrub_text(
                    f"Key B rejected Key A native steps ({type(exc).__name__}); "
                    "epoch invalidation required on key rotation."
                ),
                metrics={
                    "cross_key_accepted": False,
                    "has_thought_signature": has_thought_signature,
                    "epoch_invalidation_required": True,
                },
                evidence=redactor.scrub_value({"error": f"{type(exc).__name__}: {exc}"}),
            )
        )

    # Test 5: 1536d Embedding with prompt prefix (no task_type field)
    sample_text = (
        "ContextFlow sử dụng pgvector HNSW 1536 chiều cho Hybrid Retrieval "
        "và cổng kiểm soát CF-DEC-038."
    )
    prefixed_query = f"task: search result | query: {sample_text}"
    try:
        emb_resp = client_a.models.embed_content(
            model=config.embedding_model,
            contents=prefixed_query,
            config=types.EmbedContentConfig(output_dimensionality=1536),
        )
        embeddings = _extract_attr_or_key(emb_resp, "embeddings") or []
        values = list(_extract_attr_or_key(embeddings[0], "values") or []) if embeddings else []
        all_finite = bool(values) and all(
            isinstance(v, int | float) and math.isfinite(float(v)) for v in values
        )
        test5_pass = len(embeddings) == 1 and len(values) == 1536 and all_finite
        tests.append(
            PoCTestResult(
                test_id="Test 5",
                name="Gemini Embedding 2 (1536d)",
                passed=test5_pass,
                summary=f"Vector count={len(embeddings)}, dim={len(values)}, all_finite={all_finite}.",
                metrics={
                    "vector_count": len(embeddings),
                    "dimensions": len(values),
                    "all_finite": all_finite,
                },
                evidence=redactor.scrub_value(
                    {
                        "formatted_input": prefixed_query,
                        "sample_head": values[:5],
                    }
                ),
            )
        )
    except _POC_EXECUTION_ERRORS as exc:
        tests.append(
            PoCTestResult(
                test_id="Test 5",
                name="Gemini Embedding 2 (1536d)",
                passed=False,
                summary=redactor.scrub_text(f"Embedding failed: {type(exc).__name__}: {exc}"),
                evidence=redactor.scrub_value({"error": str(exc)}),
            )
        )

    return PoCRunResult(
        mode=mode,
        executed_at_utc=executed_at,
        sdk_version=sdk_version,
        text_model=config.text_model,
        embedding_model=config.embedding_model,
        tests=tests,
        t2b_cross_key_accepted=t2b_accepted,
    )


def render_markdown_report(result: PoCRunResult, redactor: SecretRedactor) -> str:
    """Render a sanitized Markdown PoC report for Core Lead and Branch 3 sign-off."""
    lines = [
        "# PoC Gate CF-DEC-038 Report",
        "",
        f"- **Decision:** `{result.gate_decision}`",
        f"- **Execution Mode:** `{result.mode}`",
        f"- **Executed At (UTC):** `{result.executed_at_utc}`",
        f"- **SDK (`google-genai`):** `{result.sdk_version}`",
        f"- **Text Model:** `{result.text_model}`",
        f"- **Embedding Model:** `{result.embedding_model}`",
        "",
        "## Summary Matrix",
        "",
        "| Test | Name | Result | Summary |",
        "|---|---|---|---|",
    ]
    for item in result.tests:
        status = "PASS" if item.passed else "FAIL"
        lines.append(f"| {item.test_id} | {item.name} | `{status}` | {item.summary} |")

    lines.extend(["", "## Architectural Recommendation for Core Lead", ""])
    if result.t2b_cross_key_accepted:
        lines.append(
            "- **T2B Key Rotation (`ContinuityEpoch`):** Cross-key replay with `GEMINI_KEY_B` "
            "succeeded; Core may preserve the active `ContinuityEpoch` across compatible key "
            "rotations while continuing to enforce `store=false` and strict model/interface "
            "compatibility checks."
        )
    else:
        lines.append(
            "- **T2B Key Rotation (`ContinuityEpoch`):** Cross-key replay with `GEMINI_KEY_B` "
            "was rejected or did not preserve continuity; Core MUST invalidate the active "
            "`ContinuityEpoch` and re-seed from canonical Turns whenever a user rotates API keys."
        )

    lines.extend(["", "## Redacted Evidence", ""])
    for item in result.tests:
        payload = json.dumps(
            {"metrics": item.metrics, "evidence": item.evidence},
            indent=2,
            ensure_ascii=False,
        )
        lines.extend(
            [
                f"### {item.test_id}: {item.name}",
                "",
                "```json",
                payload,
                "```",
                "",
            ]
        )

    return redactor.scrub_text("\n".join(lines))


# --- Offline deterministic stubs for --self-test ---


@dataclass
class _StubObj:
    data: dict[str, Any]

    def __getattr__(self, name: str) -> Any:
        if name in self.data:
            return self.data[name]
        raise AttributeError(name)

    def model_dump(self, **_kwargs: Any) -> dict[str, Any]:
        return dict(self.data)


class _StubInteractions:
    def __init__(self, api_key: str, *, reject_cross_key: bool = False) -> None:
        self.api_key = api_key
        self.reject_cross_key = reject_cross_key

    def create(self, **kwargs: Any) -> Any:
        assert kwargs.get("store") is False, "store=False invariant violated"
        assert "previous_interaction_id" not in kwargs, "previous_interaction_id must be omitted"
        model = kwargs.get("model", DEFAULT_TEXT_MODEL)
        user_input = kwargs.get("input")
        stream = bool(kwargs.get("stream", False))

        if stream:
            anchor = "CF-DEC-038-ANCHOR-749"
            sig = f"stub-sig-owner={self.api_key}"
            steps = [
                _StubObj(
                    {
                        "type": "user_input",
                        "content": [{"type": "text", "text": str(user_input)}],
                    }
                ),
                _StubObj(
                    {
                        "type": "thought",
                        "signature": sig,
                        "summary": [{"type": "text", "text": f"Remembering {anchor}."}],
                    }
                ),
                _StubObj(
                    {
                        "type": "model_output",
                        "content": [
                            {
                                "type": "text",
                                "text": f"Acknowledged {anchor} with stateless store=false.",
                            }
                        ],
                    }
                ),
            ]
            usage = _StubObj(
                {
                    "total_input_tokens": 25,
                    "total_output_tokens": 12,
                    "total_thought_tokens": 8,
                    "total_tokens": 45,
                    "total_cached_tokens": None,
                }
            )
            return iter(
                [
                    _StubObj({"event_type": "interaction.created"}),
                    _StubObj({"event_type": "step.start", "index": 0, "step": steps[1]}),
                    _StubObj(
                        {
                            "event_type": "step.delta",
                            "index": 0,
                            "delta": _StubObj({"type": "thought_signature", "signature": sig}),
                        }
                    ),
                    _StubObj({"event_type": "step.stop", "index": 0}),
                    _StubObj({"event_type": "step.start", "index": 1, "step": steps[2]}),
                    _StubObj(
                        {
                            "event_type": "step.delta",
                            "index": 1,
                            "delta": _StubObj({"type": "text", "text": f"Acknowledged {anchor} "}),
                        }
                    ),
                    _StubObj(
                        {
                            "event_type": "step.delta",
                            "index": 1,
                            "delta": _StubObj(
                                {"type": "text", "text": "with stateless store=false."}
                            ),
                        }
                    ),
                    _StubObj({"event_type": "step.stop", "index": 1}),
                    _StubObj(
                        {
                            "event_type": "interaction.completed",
                            "interaction": _StubObj(
                                {
                                    "id": "stub-1",
                                    "model": model,
                                    "steps": steps,
                                    "usage": usage,
                                }
                            ),
                        }
                    ),
                ]
            )

        assert isinstance(user_input, list), "Multi-turn input must be a step list"
        for step in user_input:
            if isinstance(step, dict) and step.get("type") == "thought":
                sig = str(step.get("signature", ""))
                if self.reject_cross_key and f"owner={self.api_key}" not in sig:
                    raise RuntimeError(
                        f"Cross-key thought_signature rejected for key={self.api_key}"
                    )

        recalled_text = "Exact token is CF-DEC-038-ANCHOR-749."
        return _StubObj(
            {
                "id": "stub-2",
                "model": model,
                "output_text": recalled_text,
                "steps": [
                    _StubObj(
                        {
                            "type": "model_output",
                            "content": [{"type": "text", "text": recalled_text}],
                        }
                    )
                ],
            }
        )


class _StubModels:
    def embed_content(self, *, model: str, contents: str, config: Any) -> Any:
        assert model == DEFAULT_EMBEDDING_MODEL
        assert "task:" in contents
        dim = getattr(config, "output_dimensionality", None)
        assert dim == 1536
        assert getattr(config, "task_type", None) is None
        vector = [math.sin((idx + 1) * 0.01) for idx in range(1536)]
        return _StubObj({"embeddings": [_StubObj({"values": vector})]})


class StubGeminiClient:
    """Offline SDK stub mirroring google-genai 3.6.1 Interactions + Embeddings surfaces."""

    def __init__(self, api_key: str, *, reject_cross_key: bool = False) -> None:
        self.interactions = _StubInteractions(api_key, reject_cross_key=reject_cross_key)
        self.models = _StubModels()


def run_self_test() -> int:
    """Run deterministic offline self-tests across all 5 PoC checks and redaction."""
    fake_a = "AIzaSyStubOfflineKeyAlpha1234567890"
    fake_b = "AIzaSyStubOfflineKeyBeta0987654321"
    cfg = PoCConfig(key_a=fake_a, key_b=fake_b)
    redactor = SecretRedactor([fake_a, fake_b])

    for reject_cross_key in (True, False):
        result = run_poc(
            cfg,
            client_factory=lambda key, reject=reject_cross_key: StubGeminiClient(
                key, reject_cross_key=reject
            ),
            mode=f"OFFLINE_STUB_T2B_REJECT={reject_cross_key}",
        )
        assert result.gate_decision == "PASS", f"Expected PASS, got {result}"
        assert len(result.tests) == 5
        assert result.tests[1].metrics["cached_tokens"] is None
        assert result.tests[1].metrics["total_includes_reasoning"] is True
        assert result.tests[3].metrics["cross_key_accepted"] is (not reject_cross_key)
        assert result.tests[4].metrics["dimensions"] == 1536

        report = render_markdown_report(result, redactor)
        assert fake_a not in report
        assert fake_b not in report
        assert "AIza" not in report
        assert REDACTED in report or not reject_cross_key

    print("PASS: CF-DEC-038 offline self-test verified 5/5 checks and 100% key redaction.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="CF-DEC-038 Gemini API PoC Gate runner")
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run deterministic offline self-test using SDK stubs (no network calls).",
    )
    parser.add_argument(
        "--write-report",
        action="store_true",
        help="Execute live PoC checks against Gemini API and write CF-DEC-038-REPORT.md.",
    )
    parser.add_argument(
        "--report-path",
        type=Path,
        default=DEFAULT_REPORT_PATH,
        help="Target Markdown report path for --write-report.",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test()

    if not args.write_report:
        parser.print_help()
        return 1

    config, missing = load_env_config()
    if config is None:
        print(
            f"BLOCKED: Missing required live credentials ({', '.join(missing)}). "
            "Provide GEMINI_KEY_A and GEMINI_KEY_B in poc/.env or environment variables.",
            file=sys.stderr,
        )
        return 2

    redactor = SecretRedactor([config.key_a, config.key_b])
    result = run_poc(config, mode="LIVE")
    report_text = render_markdown_report(result, redactor)
    args.report_path.write_text(report_text, encoding="utf-8", newline="\n")
    print(f"Wrote PoC report to {args.report_path} (decision={result.gate_decision}).")
    return 0 if result.gate_decision == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
