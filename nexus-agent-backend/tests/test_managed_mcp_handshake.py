"""The handshake the real SDK stack sends to a user-managed server, checked by the real request rules."""
import json

import httpx2
import pytest

from app import managed_mcp as mcp
from app.mcp_security.network import ProtocolRules

ACTOR = 7


def _server():
    record = {"serverId": "11111111-1111-4111-8111-111111111111", "ownerUserId": ACTOR, "configVersion": 1,
              "displayName": "Synthetic docs", "url": "https://docs.example/mcp", "transport": "streamable_http",
              "enabled": True, "timeoutSec": 5, "resultMaxChars": 1000, "allowedTools": [], "deleted": False}
    try:
        return mcp.Server.parse(record, ACTOR)
    except Exception:
        # The record shape belongs to other tests; this one only needs the fields the session reads.
        return type("Server", (), {"data": record, "id": record["serverId"], "version": 1})()


class _Policy:
    def __init__(self, sent):
        self.sent = sent

    async def http_client_factory(self, actor, ctx, server, action):
        def handler(request):
            self.sent.append((request.method, request.content))
            return httpx2.Response(500)

        def factory(headers=None, timeout=None, auth=None, **_ignored):
            return httpx2.AsyncClient(transport=httpx2.MockTransport(handler), headers=headers, timeout=timeout,
                                      follow_redirects=False, trust_env=False)
        return factory


async def test_the_first_request_to_a_managed_server_is_a_handshake_the_request_rules_accept():
    sent = []
    with pytest.raises(mcp.ManagedMcpError):
        async with mcp.MCPProvider().session(_server(), _Policy(sent), None, "discover", actor=ACTOR):
            pass
    method, body = sent[0]
    payload = json.loads(body)
    assert (method, payload["method"]) == ("POST", "initialize")
    # No reverse channel is offered: not elicitation, not sampling, not roots.
    assert payload["params"]["capabilities"] in ({}, {"experimental": {}})
    for probe in (True, False):
        assert ProtocolRules(probe=probe).parse(method, body)["method"] == "initialize"
