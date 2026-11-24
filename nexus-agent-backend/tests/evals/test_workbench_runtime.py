import asyncio
import hashlib

import pytest
from app import orchestrator
from app.config import get_settings
from eval_harness import install


@pytest.fixture(autouse=True)
def isolated(tmp_path, monkeypatch):
    monkeypatch.setattr(get_settings(), 'checkpoint_path', str(tmp_path / 'workbench.sqlite'))


def scenario(turns, **options):
    return {'id': 'workbench', 'operation': 'ASSISTANT_CHAT', 'input': {'text': 'Review the attached note.'}, 'model': turns, 'options': options}


async def test_native_reasoning_has_real_node_and_public_order_not_answer(monkeypatch):
    rig = install(scenario([[{'reasoning': 'native'}, {'text': 'answer'}, {'usage': [5, 2]}, {'finish': 'stop'}]]), monkeypatch)
    events = await rig.run()
    public = events[:-1]
    assert [e.data.get('sequence') for e in public] == list(range(1, len(public) + 1))
    assert all(e.data['runId'] == events[0].data['runId'] and e.data['at'] >= 0 for e in public)
    node = next(e.data for e in public if e.name == 'stage' and e.data.get('phase') == 'model')
    assert node['parentIds'] == ['context']
    # The parent it names is a step that was itself reported under that id, exactly once.
    assert [e.data['phase'] for e in public if e.name == 'stage' and e.data.get('nodeId') == 'context'] == ['context']
    assert next(e.data for e in public if e.name == 'reasoning')['nodeId'] == node['nodeId']
    assert public[0].data['model'] == 'eval-model'
    assert public[-2].data['usageReported'] is True
    assert events[-1].data['result']['answer'] == 'answer'


async def test_attached_evidence_and_fenced_artifacts_are_tied_to_the_producing_run(monkeypatch):
    case = scenario([[{'text': '```json\n{"items":[{"title":"Follow up"}]}\n```'}, {'finish': 'stop'}]])
    case['input']['attachments'] = [{'id':'att_test_001','fileName':'note.txt','content':'Unique source evidence','sha256':hashlib.sha256(b'Unique source evidence').hexdigest()}]
    rig = install(case, monkeypatch)
    events = await rig.run()
    sources = [s for e in events if e.name == 'sources' for s in e.data['items']]
    assert [(s['id'],s['attachmentId']) for s in sources] == [('A1','att_test_001')]
    assert any('Unique source evidence' in m['content'] for m in rig.model.calls[0]['messages'])
    output = next(e.data['artifact'] for e in events if e.name == 'artifact' and e.data['artifact']['kind'] == 'json')
    assert output['sourceIds'] == ['A1'] and output['runId'] == events[0].data['runId']
    assert next(e.data for e in events if e.name == 'usage')['usageReported'] is False


async def test_opt_in_analysts_overlap_and_only_joined_synthesis_reaches_memory(monkeypatch):
    analyses = [[{'wait':'join'}, {'reasoning':f'role thought {i}'}, {'text':f'finding {i}'}, {'finish':'stop'}] for i in range(3)]
    rig = install(scenario([*analyses, [{'text':'joined result'}, {'finish':'stop'}]], collaborationEnabled=True), monkeypatch)
    stream = orchestrator.run_agent(rig.request())
    try:
        events = []
        async def through_model_starts():
            async for e in stream:
                events.append(e)
                if sum(evt.name=='stage' and evt.data.get('phase')=='model' and evt.data.get('status')=='start' for evt in events) == 3:
                    return
        await asyncio.wait_for(through_model_starts(), 1.5)
        assert {evt.data['agentId'] for evt in events if evt.name=='stage' and evt.data.get('phase')=='model'} == {'code','doc','arch'}
        rig.model.gate('join').set()
        events += [e async for e in stream]
    finally:
        rig.model.gate('join').set(); await stream.aclose()
    assert len(rig.model.calls) == 4
    assert ''.join(e.data['text'] for e in events if e.name=='delta') == 'joined result'
    assert len([e for e in events if e.name=='agent_output']) == 3
    synth = next(e.data for e in events if e.name=='stage' and e.data.get('agentId')=='synth' and e.data['status']=='start')
    assert len(synth['parentIds'])==3
    assert rig.memory.turns[-1] == ['assistant','joined result']
    graph = next(e.data['artifact'] for e in events if e.name=='artifact' and e.data['artifact']['kind']=='graph')
    assert graph['sizeBytes'] == len(graph['content'].encode())
