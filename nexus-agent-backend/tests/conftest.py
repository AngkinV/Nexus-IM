"""Pytest config: enable async tests by default and put project root on sys.path."""
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def pytest_collection_modifyitems(config, items):
    for item in items:
        if "asyncio" not in item.keywords and item.get_closest_marker("asyncio") is None:
            # auto-mark coroutine tests as asyncio so we don't need a marker on every test
            if hasattr(item, "obj") and getattr(item.obj, "__code__", None) and item.obj.__code__.co_flags & 0x100:
                item.add_marker(pytest.mark.asyncio)
