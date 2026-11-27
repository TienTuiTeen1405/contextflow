import secrets
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from starlette.middleware.base import RequestResponseEndpoint
from starlette.responses import Response

from app.api.internal.errors import failure_response, http_error, validation_error
from app.api.internal.router import router
from app.config import get_settings
from app.core.redaction import install_logging_redaction, install_redaction


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    install_logging_redaction()
    get_settings()
    yield


app = FastAPI(
    title="ContextFlow AI Service",
    version="0.1.0",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    lifespan=lifespan,
)
app.include_router(router)
app.add_exception_handler(RequestValidationError, validation_error)  # type: ignore[arg-type]
app.add_exception_handler(HTTPException, http_error)  # type: ignore[arg-type]


@app.middleware("http")
async def authenticate_before_body(
    request: Request, call_next: RequestResponseEndpoint
) -> Response:
    if request.url.path.startswith("/internal/v1/ai"):
        authorization = request.headers.get("Authorization", "")
        if not authorization.lower().startswith("bearer "):
            return failure_response(401, "SERVICE_AUTH_REQUIRED")
        supplied = authorization[7:]
        expected = get_settings().internal_service_token.get_secret_value()
        if not secrets.compare_digest(supplied.encode(), expected.encode()):
            return failure_response(403, "SERVICE_AUTH_INVALID")
    return await call_next(request)


install_redaction(app)


@app.get("/health", include_in_schema=False)
def health() -> dict[str, str]:
    return {"status": "UP", "service": "ai-service"}
