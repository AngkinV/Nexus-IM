"""T2 actual executor/connection regressions. Authored only; never run here."""
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
import pytest
from app.mcp_tools import ToolExecutor, _Entry
from app.scope import AuthorizedScope, ScopeDenied, narrow_scope, scope_hash
from test_scope_v1 import grant


def contacts_only():
    d = grant(()).model_dump()
    d['contactUserIds'] = [7]
    d['scopeKey'] = scope_hash(d)
    return AuthorizedScope.model_validate(d)


class Adapter:
    def __init__(self, remote):
        self.tools = [SimpleNamespace(name='get_message_by_id', ainvoke=remote)]
        self.list_tools = AsyncMock(return_value=self.tools)

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        pass


def executor(scope):
    e = ToolExecutor(1, 'fixture', scope=scope)
    e._scope_client.validate = AsyncMock(side_effect=lambda s: s)
    return e


def entries():
    # Public alias deliberately differs: decisions must use remote_name.
    return {'message_alias': _Entry(None, 'get_message_by_id', {})}


@pytest.mark.asyncio
async def test_contacts_only_schemas_and_message_denied_before_adapter():
    e = executor(contacts_only())
    tools = {}
    for name, props in [('get_recent_messages', {'chat_id': {'type': 'integer'}}),
                        ('get_chat_profile', {'chat_id': {'type': 'integer'}}),
                        ('get_message_by_id', {'message_id': {'type': 'integer'}}),
                        ('get_user_profile', {'user_id': {'type': 'integer'}})]:
        tools[name] = _Entry(None, name, {'function': {'name': name,
            'parameters': {'type': 'object', 'properties': props}}})
    tools.update(entries())
    remote = AsyncMock()
    returned = []
    with patch('app.mcp_tools.catalog', AsyncMock(return_value=tools)), \
         patch('app.mcp_tools._adapter') as factory:
        schemas = await e.schemas()
        assert [s['function']['name'] for s in schemas] == ['get_user_profile']
        assert schemas[0]['function']['parameters']['properties']['user_id']['enum'] == [7]
        with pytest.raises(ScopeDenied):
            returned.append(await e.execute('message_alias', {'message_id': 202}))
        factory.assert_not_called()
        assert remote.await_count == 0 and returned == []
    with patch('app.mcp_tools.catalog', AsyncMock()) as cat:
        assert await executor(grant(())).schemas() == []
        cat.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize('replacement', ['child', 'grant'])
async def test_parent_bound_then_replacement_denied_and_new_child_positive(replacement):
    parent = grant((101, 202))
    child = narrow_scope(parent, chat_ids=[101])
    changed = child if replacement == 'child' else parent.model_copy(update={
        'grantId': 'fresh', 'authorizationVersion': 2})
    remote = AsyncMock(return_value=SimpleNamespace(content='{"body":"A"}', status='success', artifact=None))
    e = executor(parent)
    with patch('app.mcp_tools.catalog', AsyncMock(return_value=entries())), \
         patch('app.mcp_tools._adapter', side_effect=lambda *a: Adapter(remote)) as factory:
        async with e:
            await e._tools_of(None)  # bind actual executor headers to AB, no business invocation
            e._scope = changed
            returned = []
            with pytest.raises(ScopeDenied):
                returned.append(await e.execute('message_alias', {'message_id': 202}))
            assert remote.await_count == 0 and returned == []
            assert factory.call_count == 1
        fresh = executor(child)
        async with fresh:
            for _ in range(2):
                result, _ = await fresh.execute('message_alias', {'message_id': 101})
                assert result == {'body': 'A'}
            assert remote.await_count == 2
            assert factory.call_count == 2  # same-scope calls reuse one connection
            assert factory.call_args.args[1]['X-Agent-Scope'] == child.header()


@pytest.mark.asyncio
async def test_scope_changes_during_ainvoke_result_not_released():
    parent = grant((101, 202))
    e = executor(parent)
    async def invoke(call):
        e._scope = narrow_scope(parent, chat_ids=[101])
        return SimpleNamespace(content='{"body":"B-PRIVATE"}', status='success', artifact=None)
    remote = AsyncMock(side_effect=invoke)
    returned = []
    with patch('app.mcp_tools.catalog', AsyncMock(return_value=entries())), \
         patch('app.mcp_tools._adapter', side_effect=lambda *a: Adapter(remote)):
        async with e:
            with pytest.raises(ScopeDenied):
                returned.append(await e.execute('message_alias', {'message_id': 202}))
    assert remote.await_count == 1  # already dispatched under parent; release must be zero
    assert returned == []


@pytest.mark.asyncio
async def test_empty_scope_runs_no_nexus_tool_even_one_that_names_no_resource():
    """schemas() offers nothing under an empty scope; execute() must agree."""
    e = executor(grant(()))
    tools = {'list_alias': _Entry(None, 'list_my_chats', {}),
             'find_alias': _Entry(None, 'find_user_by_username', {})}
    returned = []
    with patch('app.mcp_tools.catalog', AsyncMock(return_value=tools)), \
         patch('app.mcp_tools._adapter') as factory:
        assert await e.schemas() == []
        for name, args in (('list_alias', {}), ('find_alias', {'username': 'bob'})):
            with pytest.raises(ScopeDenied):
                returned.append(await e.execute(name, args))
        factory.assert_not_called()  # no connection, so no call reached Java
    assert returned == []
