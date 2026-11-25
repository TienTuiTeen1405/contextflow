from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class AdapterStatus:
    mode: str
    external_calls_enabled: bool


class ProviderAdapter(Protocol):
    """Bootstrap seam. Task DTOs and execution methods are added in M1 from BranOS-04-6."""

    def status(self) -> AdapterStatus: ...
