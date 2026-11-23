"""T4B security boundary; T2/T4A/T1 supply the authoritative adapters."""
from .bridge import (
    AdapterFactory, BindingProvider, GuardedManagement, MCPBinding,
    SecureSessionFactory, principal_from_verified_actor,
)
from .execution import GuardedExecutor, SessionFactory, SessionResult, ToolSession
from .network import NetworkPolicy, ProtocolRules, SafeMCPTransport, secure_http_client
from .policy import ExecutionGate, ManagementGuard
from .redaction import SecretRedactor, untrusted_envelope
from .telemetry import RedactingSpanExporter, require_safe_trace_sinks
from .types import (
    AuthorityProvider, CallIdentity, CallJournal, CommittedCall, ExecutionContext,
    LiveAuthority, Permissions, Principal, ProvenanceProof, ProvenanceProvider,
    RunSnapshot, ScopeAuthorizer, ScopeProof, SecurityViolation, ServiceIdentity,
    ToolIdentity, ToolKey,
)

__all__ = [
    "AdapterFactory", "AuthorityProvider", "BindingProvider", "CallIdentity", "CallJournal",
    "CommittedCall", "ExecutionContext", "ExecutionGate", "GuardedExecutor", "GuardedManagement",
    "LiveAuthority", "MCPBinding", "ManagementGuard", "NetworkPolicy", "Permissions", "Principal",
    "ProtocolRules", "ProvenanceProof", "ProvenanceProvider", "RunSnapshot", "SafeMCPTransport",
    "ScopeAuthorizer", "ScopeProof", "SecretRedactor", "SecureSessionFactory", "SecurityViolation",
    "ServiceIdentity", "SessionFactory", "SessionResult", "ToolIdentity", "ToolKey", "ToolSession",
    "RedactingSpanExporter", "require_safe_trace_sinks",
    "principal_from_verified_actor", "secure_http_client", "untrusted_envelope",
]
