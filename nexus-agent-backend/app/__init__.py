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


def _scope_binding(request):
    """What a run's reads are authorized under: stable across the grants a resume re-issues."""
    scope = getattr(request, "scope", None)
    return None if scope is None else [scope.scopeKey, scope.sessionGeneration]


def install_run_policies() -> None:
    """Install, once per process, who decides whether a stored result may be reused or a call repeated."""
    from . import orchestrator
    from .scope_authorizers import install_scope_authorizers, scope_replay_authorizer, scope_reuse_authorizer

    if getattr(orchestrator.reuse_authorizer, "parties", None):
        return
    install_scope_authorizers(scope_reuse_authorizer, scope_replay_authorizer,
                              compose=orchestrator.install_authorizers)
    orchestrator.install_authorizers(binding=_scope_binding)


@contextlib.asynccontextmanager
async def _lifespan(_app: FastAPI):
    init_tracing()
    install_run_policies()
    yield
    await close_memory()


def create_app() -> FastAPI:
    # Fail at startup, not on the first request, when the configuration is invalid.
    get_settings()
    external_servers()
    app = FastAPI(title="Nexus Agent Backend", version="1.0.0", lifespan=_lifespan)
    app.include_router(router)
    app.include_router(model_router)
    from .mcp_management_routes import router as mcp_management_router
    from .mcp_policy_adapter import management_policy
    from .managed_mcp import managed_registry
    if managed_registry.policy is None:
        # Missing T4B keeps default-deny; calls additionally require real T1/T2/run adapters.
        managed_registry.policy = management_policy()
    app.include_router(mcp_management_router)
    return app


app = create_app()
