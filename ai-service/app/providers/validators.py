"""Strict validators for structured JSON generation outputs and 1536d embedding vectors."""

import json
import math
from collections.abc import Sequence
from typing import Any, Never

from jsonschema import Draft202012Validator, SchemaError, ValidationError
from pydantic import JsonValue
from referencing import Registry
from referencing.exceptions import NoSuchResource

from app.providers.error_mapper import AdapterOutputValidationError

EXPECTED_EMBEDDING_DIMENSION = 1536
MAX_EMBEDDING_BATCH_SIZE = 10


def _no_remote_schema(uri: str) -> Never:
    raise NoSuchResource(uri)


# Disallow any external HTTP/network schema fetching during response validation.
STRICT_SCHEMA_REGISTRY: Registry[Any] = Registry(retrieve=_no_remote_schema)  # type: ignore[call-arg]


def _reject_non_finite_constant(constant: str) -> Never:
    raise ValueError(f"Non-finite JSON constant not permitted: {constant}")


def parse_and_validate_generate_json(
    output_text: str,
    response_schema: dict[str, JsonValue] | None,
) -> dict[str, JsonValue] | list[JsonValue] | None:
    """Parse and strictly validate structured JSON against response_schema when requested."""
    if response_schema is None:
        return None

    try:
        Draft202012Validator.check_schema(response_schema)
    except SchemaError as exc:
        raise AdapterOutputValidationError(
            "INVALID_RESPONSE_SCHEMA", f"Invalid JSON schema: {exc.message}"
        ) from exc

    stripped = output_text.strip()
    if not stripped:
        raise AdapterOutputValidationError(
            "INVALID_ADAPTER_OUTPUT", "Empty output when response_schema requires JSON"
        )

    try:
        parsed = json.loads(stripped, parse_constant=_reject_non_finite_constant)
    except ValueError as exc:
        raise AdapterOutputValidationError(
            "INVALID_ADAPTER_OUTPUT", "Malformed JSON returned by provider"
        ) from exc

    if not isinstance(parsed, dict | list):
        raise AdapterOutputValidationError(
            "INVALID_ADAPTER_OUTPUT", "Structured JSON root must be an object or array"
        )

    try:
        Draft202012Validator(response_schema, registry=STRICT_SCHEMA_REGISTRY).validate(parsed)
    except (ValidationError, Exception) as exc:
        raise AdapterOutputValidationError(
            "INVALID_ADAPTER_OUTPUT", f"Provider JSON violated response_schema: {exc}"
        ) from exc

    return parsed


def validate_embedding_vectors(
    vectors: Sequence[Sequence[object]],
    expected_count: int,
    *,
    expected_dim: int = EXPECTED_EMBEDDING_DIMENSION,
) -> list[list[float]]:
    """Enforce batch size <= 10, exact count/1536 dimensions, and finite numeric entries."""
    if expected_count < 1 or expected_count > MAX_EMBEDDING_BATCH_SIZE:
        raise AdapterOutputValidationError(
            "PERMANENT_INPUT_ERROR",
            f"Embedding batch count {expected_count} outside [1, {MAX_EMBEDDING_BATCH_SIZE}]",
        )

    if len(vectors) != expected_count:
        raise AdapterOutputValidationError(
            "INVALID_ADAPTER_OUTPUT",
            f"Embedding vector count mismatch: expected {expected_count}, got {len(vectors)}",
        )

    validated: list[list[float]] = []
    for idx, raw_vec in enumerate(vectors):
        if len(raw_vec) != expected_dim:
            raise AdapterOutputValidationError(
                "INVALID_ADAPTER_OUTPUT",
                f"Embedding vector[{idx}] dimension mismatch: "
                f"expected {expected_dim}, got {len(raw_vec)}",
            )
        clean_vec: list[float] = []
        for elem in raw_vec:
            if isinstance(elem, bool) or not isinstance(elem, int | float):
                raise AdapterOutputValidationError(
                    "INVALID_ADAPTER_OUTPUT",
                    f"Embedding vector[{idx}] contains non-numeric element",
                )
            fval = float(elem)
            if not math.isfinite(fval):
                raise AdapterOutputValidationError(
                    "INVALID_ADAPTER_OUTPUT",
                    f"Embedding vector[{idx}] contains non-finite value",
                )
            clean_vec.append(fval)
        validated.append(clean_vec)

    return validated
