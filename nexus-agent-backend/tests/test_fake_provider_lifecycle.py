from __future__ import annotations

import asyncio

from fake_provider import FakeProvider


async def test_local_provider_closes_held_connections_on_exit():
    async with FakeProvider([{"body": [30.0]}]) as server:
        port = int(server.base_url.rsplit(":", 1)[1])
        reader, writer = await asyncio.open_connection("127.0.0.1", port)
        writer.write(b"POST /test HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2\r\n\r\n{}")
        await writer.drain()
        await reader.readuntil(b"\r\n\r\n")
    try:
        assert await asyncio.wait_for(reader.read(), 1) == b""
        assert not [task for task in asyncio.all_tasks() if task.get_coro().__qualname__ == "FakeProvider._handle"]
    finally:
        writer.close()
        await writer.wait_closed()
