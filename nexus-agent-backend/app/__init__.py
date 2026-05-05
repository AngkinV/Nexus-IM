"""Package entry-point: wires the FastAPI app together."""
from __future__ import annotations

import logging

from fastapi import FastAPI

from .routes import router

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")


def create_app() -> FastAPI:
    app = FastAPI(title="Nexus Agent Backend", version="1.0.0")
    app.include_router(router)
    return app


app = create_app()
