"""A local HTTP server that replays scripted provider responses.

Lets adapter tests drive the real provider SDKs end to end — request building,
SSE parsing, retries, connection handling — without any network access.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
from typing import Any


def sse(*events: dict[str, Any], named: bool = False) -> list[bytes]:
    """Encode JSON events as SSE frames; `named` adds the `event:` line Anthropic uses."""
    frames = []
    for event in events:
        prefix = f"event: {event['type']}\n" if named else ""
        frames.append(f"{prefix}data: {json.dumps(event, ensure_ascii=False)}\n\n".encode())
    return frames


class FakeProvider:
    """Serves one scripted response per request, in order.

    A response is {"status", "headers", "body": [bytes | float]} where a float
    is a pause in seconds before the next frame.
    """

    def __init__(self, responses: list[dict[str, Any]]) -> None:
        self._responses = list(responses)
        self.requests: list[dict[str, Any]] = []
        self.disconnects = 0
        self._server: asyncio.AbstractServer | None = None
        self.base_url = ""
        self._connections: set[asyncio.Task] = set()

    async def __aenter__(self) -> "FakeProvider":
        self._server = await asyncio.start_server(self._handle, "127.0.0.1", 0)
        port = self._server.sockets[0].getsockname()[1]
        self.base_url = f"http://127.0.0.1:{port}"
        return self

    async def __aexit__(self, *exc_info) -> None:
        self._server.close()
        tasks = list(self._connections)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        with contextlib.suppress(Exception):
            await asyncio.wait_for(self._server.wait_closed(), timeout=1)

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        task = asyncio.current_task()
        self._connections.add(task)
        try:
            head = (await reader.readuntil(b"\r\n\r\n")).decode("latin-1")
            request_line, *header_lines = head.strip().split("\r\n")
            method, target, _ = request_line.split(" ", 2)
            headers = {k.strip().lower(): v.strip() for k, v in (line.split(":", 1) for line in header_lines)}
            body = await reader.readexactly(int(headers.get("content-length", 0)))
            self.requests.append({
                "method": method,
                "target": target,
                "headers": headers,
                "json": json.loads(body) if body else None,
            })

            response = self._responses.pop(0) if self._responses else {"status": 500, "body": [b"unscripted"]}
            lines = [f"HTTP/1.1 {response.get('status', 200)} X", "Connection: close"]
            extra = {"content-type": "text/event-stream", **response.get("headers", {})}
            lines += [f"{name}: {value}" for name, value in extra.items()]
            writer.write(("\r\n".join(lines) + "\r\n\r\n").encode())
            await writer.drain()
            for frame in response.get("body", []):
                if isinstance(frame, (int, float)):
                    await asyncio.sleep(frame)
                    continue
                writer.write(frame)
                await writer.drain()
        except (ConnectionError, asyncio.IncompleteReadError):
            self.disconnects += 1
        finally:
            self._connections.discard(task)
            writer.close()
            with contextlib.suppress(Exception):
                await writer.wait_closed()
