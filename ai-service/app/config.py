from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="CF_", env_file=".env", extra="ignore", hide_input_in_errors=True
    )

    provider_mode: Literal["MOCK"] = "MOCK"
    internal_service_token: SecretStr = Field(min_length=24)


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
