from typing import Annotated

from fastapi import APIRouter, Depends

from app.providers.base import AdapterStatus, ProviderAdapter
from app.providers.factory import get_provider
from app.security.service_auth import require_service_auth

router = APIRouter(
    prefix="/internal/v1/ai",
    tags=["internal-bootstrap"],
    dependencies=[Depends(require_service_auth)],
)


@router.get("/status", response_model=AdapterStatus)
def adapter_status(provider: Annotated[ProviderAdapter, Depends(get_provider)]) -> AdapterStatus:
    return provider.status()
