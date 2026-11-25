import secrets
from typing import Annotated

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.config import Settings, get_settings

bearer = HTTPBearer(auto_error=False)


def require_service_auth(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> None:
    if credentials is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Service token required"
        )
    if not secrets.compare_digest(
        credentials.credentials.encode("utf-8"),
        settings.internal_service_token.get_secret_value().encode("utf-8"),
    ):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Service access denied")
