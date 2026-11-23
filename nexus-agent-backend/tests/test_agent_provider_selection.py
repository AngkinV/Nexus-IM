import pytest

from app import orchestrator
from app.config import get_settings
from app.schemas import InvokeRequest
from scope_support import install_scope_authority, make_scope


async def test_an_incomplete_explicit_channel_never_borrows_the_server_key(monkeypatch):
    monkeypatch.setattr(get_settings(), 'openai_api_key', 'environment-sentinel')
    # A real (synthetic) scope, so the turn reaches provider resolution instead of authz.
    install_scope_authority(monkeypatch, [orchestrator])
    req = InvokeRequest.model_validate({'actor': {'userId': 7}, 'traceId': 'selection',
        'session': {'sessionId': 'session', 'operationType': 'ASSISTANT_CHAT'}, 'input': {'text': 'hi'},
        'runId': 'run_select_0001',
        'scope': make_scope(7, session_id='session', run_id='run_select_0001').model_dump()})

    def forbidden(*args, **kwargs):
        pytest.fail('Invalid explicit configuration reached model or storage')

    monkeypatch.setattr(orchestrator, 'build_client', forbidden)
    monkeypatch.setattr(orchestrator, 'open_run_store', forbidden)
    events = [event async for event in orchestrator.run_agent(req, provider={'name':'custom','model':'x','apiKey':None})]
    assert next(event for event in events if event.name == 'error').data['reason'] == 'CONFIG'
    assert next(event for event in events if event.name == 'done').data['finishReason'] == 'error'


def test_keyless_ollama_uses_its_explicit_endpoint_and_model(monkeypatch):
    monkeypatch.setattr(get_settings(), 'openai_api_key', 'environment-sentinel')
    cfg = orchestrator._resolve_provider_config({'name':'ollama','baseUrl':'http://127.0.0.1:11434/v1','model':'local-model'})
    assert (cfg.name, cfg.base_url, cfg.model) == ('ollama','http://127.0.0.1:11434/v1','local-model')
    assert cfg.api_key != 'environment-sentinel'
