from __future__ import annotations

import asyncio
import shutil
import socket

import pytest
import redis.asyncio as redis

from app import memory as memory_mod
from app.memory import ShortTermMemory
from scope_support import install_scope_authority, make_scope


@pytest.fixture
async def isolated_redis(tmp_path):
    binary = shutil.which("redis-server")
    if not binary:
        pytest.skip("redis-server is required for the isolated atomicity test")
    with socket.socket() as address:
        address.bind(("127.0.0.1", 0))
        port = address.getsockname()[1]
    process = await asyncio.create_subprocess_exec(
        binary, "--bind", "127.0.0.1", "--port", str(port), "--save", "", "--appendonly", "no",
        "--dir", str(tmp_path), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
    )
    client = redis.Redis(host="127.0.0.1", port=port, decode_responses=True)
    try:
        async def ready():
            while True:
                line = await process.stdout.readline()
                if not line:
                    raise RuntimeError("isolated Redis exited before readiness")
                if b"Ready to accept connections" in line:
                    return
        await asyncio.wait_for(ready(), 5)
        await client.ping()
        yield client
    finally:
        await client.aclose()
        if process.returncode is None:
            process.terminate()
        await asyncio.wait_for(process.wait(), 5)


@pytest.fixture(autouse=True)
def _accept_scope(monkeypatch):
    """No live Java authority: accept exactly the synthetic scope these tests issue."""
    install_scope_authority(monkeypatch, [memory_mod])


async def test_run_message_is_atomically_written_once_under_concurrency(isolated_redis):
    memory = ShortTermMemory(isolated_redis)
    scope = make_scope(7, session_id="s1", run_id="run_001")
    results = await asyncio.gather(*[
        memory.append(7, "s1", "assistant", "正文", run_id="run_001", scope=scope) for _ in range(20)
    ])
    assert results.count(True) == 1
    assert await memory.recent(7, "s1", scope=scope) == [{"role": "assistant", "content": "正文"}]


async def test_deduplication_is_scoped_to_owner_and_session(isolated_redis):
    memory = ShortTermMemory(isolated_redis)
    for user_id, session_id in ((7, "s1"), (8, "s1"), (7, "s2")):
        scope = make_scope(user_id, session_id=session_id, run_id="run_001")
        await memory.append(user_id, session_id, "user", "你好", run_id="run_001", scope=scope)
        assert await memory.recent(user_id, session_id, scope=scope) == [{"role": "user", "content": "你好"}]


async def test_failed_write_does_not_claim_success_or_suppress_retry(isolated_redis):
    memory = ShortTermMemory(isolated_redis)
    scope = make_scope(7, session_id="s1", run_id="run_001")
    key = memory._messages_key(7, "s1", scope.scopeKey)
    await isolated_redis.set(key, "wrong-type")
    await memory.append(7, "s1", "assistant", "正文", run_id="run_001", scope=scope)
    await isolated_redis.delete(key)
    memory._skip_until = 0
    await memory.append(7, "s1", "assistant", "正文", run_id="run_001", scope=scope)
    assert await memory.recent(7, "s1", scope=scope) == [{"role": "assistant", "content": "正文"}]
