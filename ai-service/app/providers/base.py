from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Protocol

from pydantic import BaseModel, SecretStr

from app.schemas.contracts import (
    EmbeddingInput,
    EmbeddingResult,
    GenerateInput,
    GenerateResult,
    StreamInput,
    VerifyInput,
    VerifyResult,
)


@dataclass(frozen=True)
class AdapterStatus:
    mode: str
    external_calls_enabled: bool


class ProviderAdapter(Protocol):
    """Request-scoped adapter: one attempt, no retry or persistence."""

    def status(self) -> AdapterStatus: ...
    def stream(self, request: StreamInput) -> AsyncIterator[tuple[str, BaseModel]]: ...
    def generate(self, request: GenerateInput) -> GenerateResult: ...
    def embeddings(self, request: EmbeddingInput) -> EmbeddingResult: ...
    def verify(self, request: VerifyInput, credential: SecretStr) -> VerifyResult: ...
