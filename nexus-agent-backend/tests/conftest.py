"""Pytest config: enable async tests by default and put project root on sys.path."""
import atexit
import os
import shutil
import sys
import tempfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

# Settings reads ".env" and "./data/chroma" relative to the working directory.
# Run from an empty sandbox so tests never pick up a developer's real API keys
# and never write to (or wipe) the real vector store.
_SANDBOX = tempfile.mkdtemp(prefix="nexus-agent-tests-")
os.chdir(_SANDBOX)
atexit.register(shutil.rmtree, _SANDBOX, ignore_errors=True)

# Tests never reach the network; a developer's proxy settings must not change
# how the HTTP clients under test are built.
for _name in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY"):
    os.environ.pop(_name, None)
    os.environ.pop(_name.lower(), None)


@pytest.fixture(autouse=True)
def _fresh_tool_catalog():
    """The tool catalog is cached per process; one test's fake server must not leak into the next."""
    from app import mcp_tools

    mcp_tools.reset_catalog()
    yield
    mcp_tools.reset_catalog()


def pytest_collection_modifyitems(config, items):
    for item in items:
        if "asyncio" not in item.keywords and item.get_closest_marker("asyncio") is None:
            # auto-mark coroutine tests as asyncio so we don't need a marker on every test
            if hasattr(item, "obj") and getattr(item.obj, "__code__", None) and item.obj.__code__.co_flags & 0x100:
                item.add_marker(pytest.mark.asyncio)
