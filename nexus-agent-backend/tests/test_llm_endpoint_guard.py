"""The connect-time check on model endpoints users enter: where it may and may not reach."""
from types import SimpleNamespace

import httpx
import pytest

from app.config import Settings
from app.llm import base


class _Recorder:
    """Stands in for the real transport, so nothing is ever sent."""

    def __init__(self):
        self.sent = []

    async def handle_async_request(self, request):
        self.sent.append(request)
        return httpx.Response(200)


class _Probe(base._PinnedDestination, _Recorder):
    pass


def _mode(monkeypatch, allow_private):
    monkeypatch.setattr(base, "get_settings", lambda: SimpleNamespace(model_endpoint_allow_private_network=allow_private))


NON_PUBLIC = [
    "http://127.0.0.1:11434/v1", "http://[::1]:11434/v1", "http://10.0.0.5/v1", "http://172.16.3.4/v1",
    "http://192.168.1.10/v1", "http://100.64.0.1/v1", "http://100.100.1.2:8100/v1", "http://[fd7a:115c:a1e0::1]/v1",
    "http://[::ffff:127.0.0.1]:8080/v1", "http://[::ffff:192.168.1.10]/v1",
]
NEVER = ["http://169.254.169.254/latest", "http://0.0.0.0/v1", "http://[fe80::1]/v1", "http://100.100.100.200/v1",
         "http://224.0.0.1/v1", "ftp://1.1.1.1/v1", "http://user:pw@1.1.1.1/v1"]


@pytest.mark.parametrize("url", NON_PUBLIC + NEVER)
async def test_strict_mode_sends_nothing_to_this_host_private_networks_or_the_tailnet(monkeypatch, url):
    _mode(monkeypatch, False)
    probe = _Probe()
    with pytest.raises(base.UnsafeEndpoint):
        await probe.handle_async_request(httpx.Request("POST", url))
    assert probe.sent == []


@pytest.mark.parametrize("url", NEVER)
async def test_some_destinations_are_refused_even_when_private_networks_are_allowed(monkeypatch, url):
    _mode(monkeypatch, True)
    probe = _Probe()
    with pytest.raises(base.UnsafeEndpoint):
        await probe.handle_async_request(httpx.Request("POST", url))
    assert probe.sent == []


async def test_by_default_a_local_model_server_is_still_reachable(monkeypatch):
    _mode(monkeypatch, True)
    probe = _Probe()
    await probe.handle_async_request(httpx.Request("POST", "http://127.0.0.1:11434/v1/chat"))
    assert probe.sent[0].url.host == "127.0.0.1"
    assert Settings.model_fields["model_endpoint_allow_private_network"].default is True


async def _resolving(monkeypatch, *addresses):
    import asyncio
    import socket

    async def fake(host, port, **_kwargs):
        return [(socket.AF_INET6 if ":" in address else socket.AF_INET, socket.SOCK_STREAM, 6, "", (address, port))
                for address in addresses]

    monkeypatch.setattr(asyncio.get_running_loop(), "getaddrinfo", fake)


async def test_strict_mode_connects_to_the_checked_address_and_keeps_the_name_for_tls(monkeypatch):
    _mode(monkeypatch, False)
    await _resolving(monkeypatch, "2606:4700:4700::1111", "1.1.1.1")
    probe = _Probe()
    await probe.handle_async_request(httpx.Request("POST", "https://models.example.test/v1/chat"))
    sent = probe.sent[0]
    assert sent.url.host == "1.1.1.1"
    assert sent.extensions["sni_hostname"] == "models.example.test"
    assert sent.headers["host"] == "models.example.test"


@pytest.mark.parametrize("answer", [("10.0.0.5",), ("1.1.1.1", "127.0.0.1"), ("100.101.102.103",), ("1.1.1.1", "fd00::5")])
async def test_strict_mode_refuses_a_name_when_any_address_it_resolves_to_is_not_public(monkeypatch, answer):
    _mode(monkeypatch, False)
    await _resolving(monkeypatch, *answer)
    probe = _Probe()
    with pytest.raises(base.UnsafeEndpoint):
        await probe.handle_async_request(httpx.Request("POST", "https://models.example.test/v1/chat"))
    assert probe.sent == []
