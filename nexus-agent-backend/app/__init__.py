"""Package entry-point: wires the FastAPI app together."""
from __future__ import annotations

import sys

if sys.version_info < (3, 11):
    # Said here, before the imports below fail on a package the old interpreter cannot install.
    raise SystemExit(
        f"nexus-agent-backend needs Python 3.11 or newer; this interpreter is {sys.version.split()[0]} "
        f"({sys.executable}). Start it with .venv311/bin/python main.py"
    )

import contextlib
import logging

from fastapi import FastAPI

from .config import get_settings
from .mcp_tools import external_servers
from .memory import close_memory
from .model_routes import router as model_router
from .routes import router
from .tracing import init_tracing

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")


@contextlib.asynccontextmanager
async def _lifespan(_app: FastAPI):
    init_tracing()
    yield
    await close_memory()


def create_app() -> FastAPI:
    # Fail at startup, not on the first request, when the configuration is invalid.
    get_settings()
    external_servers()
    app = FastAPI(title="Nexus Agent Backend", version="1.0.0", lifespan=_lifespan)
    app.include_router(router)
    app.include_router(model_router)
    return app


app = create_app()
