"""The opt-in that lets users bring their own MCP servers: any origin, public addresses only."""
import pytest

from app.config import Settings
from app.mcp_policy_adapter import RegistryBindingProvider
from app.mcp_security.network import NetworkPolicy
from app.mcp_security.types import SecurityViolation

LISTED = frozenset({"https://docs.example"})


def _code(call):
    with pytest.raises(SecurityViolation) as refused:
        call()
    return refused.value.code


async def _resolved(policy, *answers):
    async def resolver(host, port):
        return answers
    return await policy.pinned_host(resolver)


def test_it_is_off_unless_configured_and_then_the_allowlist_decides():
    assert Settings.model_fields["mcp_allow_any_public_origin"].default is False
    assert NetworkPolicy("https://docs.example/mcp", True, LISTED).any_public_origin is False
    assert _code(lambda: NetworkPolicy("https://elsewhere.example/mcp", True, LISTED)) == "UNSAFE_ENDPOINT"
    assert _code(lambda: NetworkPolicy("https://elsewhere.example/mcp", True, frozenset())) == "UNSAFE_ENDPOINT"


async def test_when_on_an_unlisted_origin_is_accepted_and_pinned_to_its_checked_public_address():
    policy = NetworkPolicy("https://elsewhere.example/mcp", True, frozenset(), any_public_origin=True)
    assert await _resolved(policy, "1.1.1.1", "2606:4700:4700::1111") == "1.1.1.1"


@pytest.mark.parametrize("answers", [("127.0.0.1",), ("10.0.0.5",), ("192.168.5.2",), ("100.100.1.2",), ("169.254.169.254",),
                                     ("1.1.1.1", "192.168.1.10"), ("fd7a:115c:a1e0::1",), ("::ffff:127.0.0.1",), ()])
async def test_when_on_a_name_with_any_non_public_address_is_still_refused(answers):
    policy = NetworkPolicy("https://elsewhere.example/mcp", True, frozenset(), any_public_origin=True)
    with pytest.raises(SecurityViolation) as refused:
        await _resolved(policy, *answers)
    assert refused.value.code == "DNS_DENIED"


@pytest.mark.parametrize("url,code", [
    ("https://127.0.0.1:8080/mcp", "DNS_DENIED"), ("https://192.168.5.2:7890/mcp", "DNS_DENIED"),
    ("https://10.0.0.5/mcp", "DNS_DENIED"), ("https://100.100.1.2/mcp", "DNS_DENIED"), ("https://[::1]:8100/mcp", "DNS_DENIED"),
    ("https://mysql:3306/mcp", "UNSAFE_ENDPOINT"), ("https://localhost/mcp", "UNSAFE_ENDPOINT"),
    ("http://elsewhere.example/mcp", "UNSAFE_ENDPOINT"), ("https://user@elsewhere.example/mcp", "UNSAFE_ENDPOINT"),
])
def test_when_on_private_literals_bare_names_plain_http_and_credentials_are_still_refused(url, code):
    assert _code(lambda: NetworkPolicy(url, True, frozenset(), any_public_origin=True)) == code


def test_when_on_the_internal_java_endpoint_cannot_be_named_as_an_external_server():
    assert _code(lambda: NetworkPolicy("https://java.example/internal", True, frozenset(), any_public_origin=True,
                                       internal_endpoint="https://java.example/internal")) == "UNSAFE_ENDPOINT"
    assert _code(lambda: NetworkPolicy("https://elsewhere.example/mcp", True, frozenset(), any_public_origin="yes")) == "UNSAFE_ENDPOINT"


class _Server:
    def __init__(self, url):
        self.data = {"url": url, "deleted": False, "credential": None}


class _Authority:
    def __init__(self, server, service):
        self.server, self.service = server, service

    async def resolve(self, principal, service_id):
        return self.server

    def _identity(self, server):
        return self.service


class _Service:
    service_id = "s-1"

    def __init__(self, endpoint):
        self.endpoint = endpoint


class _NoSecrets:
    def deny_secret(self, value):
        pass


class _Built(Exception):
    pass


@pytest.mark.parametrize("flag", [False, True])
async def test_the_setting_reaches_the_policy_that_guards_each_users_server(flag):
    service = _Service("https://elsewhere.example/mcp")
    provider = RegistryBindingProvider(_Authority(_Server(service.endpoint), service), allowed_origins=(),
                                       any_public_origin=flag, forbidden_secrets=_NoSecrets())
    seen = {}

    def recording_policy(endpoint, **options):
        seen.update(options, endpoint=endpoint)
        raise _Built()

    provider.network = type("Network", (), {"NetworkPolicy": staticmethod(recording_policy)})
    with pytest.raises(_Built):
        await provider.binding(service, None, None)
    assert seen == {"endpoint": service.endpoint, "external": True, "allowed_origins": frozenset(),
                    "allow_http": False, "any_public_origin": flag}
