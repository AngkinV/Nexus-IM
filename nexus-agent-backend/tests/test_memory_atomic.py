from __future__ import annotations

import asyncio
import shutil
import socket

import pytest
import redis.asyncio as redis

from app.memory import ShortTermMemory


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


async def test_run_message_is_atomically_written_once_under_concurrency(isolated_redis):
    memory = ShortTermMemory(isolated_redis)
    results = await asyncio.gather(*[
        memory.append(7, "s1", "assistant", "正文", run_id="run_001") for _ in range(20)
    ])
    assert results.count(True) == 1
    assert await memory.recent(7, "s1") == [{"role": "assistant", "content": "正文"}]


async def test_deduplication_is_scoped_to_owner_and_session(isolated_redis):
    memory = ShortTermMemory(isolated_redis)
    for user_id, session_id in ((7, "s1"), (8, "s1"), (7, "s2")):
        await memory.append(user_id, session_id, "user", "你好", run_id="run_001")
        assert await memory.recent(user_id, session_id) == [{"role": "user", "content": "你好"}]


async def test_failed_write_does_not_claim_success_or_suppress_retry(isolated_redis):
    memory = ShortTermMemory(isolated_redis)
    await isolated_redis.set(memory._messages_key(7, "s1"), "wrong-type")
    await memory.append(7, "s1", "assistant", "正文", run_id="run_001")
    await isolated_redis.delete(memory._messages_key(7, "s1"))
    memory._skip_until = 0
    await memory.append(7, "s1", "assistant", "正文", run_id="run_001")
    assert await memory.recent(7, "s1") == [{"role": "assistant", "content": "正文"}]
