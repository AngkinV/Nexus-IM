#!/usr/bin/env python3
"""Attack checklist for the public stack (docs/PUBLIC-ISOLATION-PLAN.md, phase 4).

Run on the Mac against the gateway on 127.0.0.1:18080 while the stack is up and NOT yet
reachable from the internet. It registers throwaway accounts, so wipe the stack afterwards.
Every refusal is paired with a control that must succeed, so a refusal for the wrong reason
shows up as a failed control. Exit code 0 only if every check passed.

    python3 deploy/public/acceptance.py [--big-uploads] [--public-mcp https://a-real-public-mcp-server/mcp]
"""
import json, os, re, subprocess, sys, time, urllib.error, urllib.request

BASE = 'http://127.0.0.1:18080'
ORIGIN = 'https://nexus-im-web-2026-fwohlcnoevd.qoder.zone'
HERE = os.path.dirname(os.path.abspath(__file__))
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
RESULTS = []
RUN = str(int(time.time()))[-7:]


def call(method, path, token=None, body=None, xff='203.0.113.10', origin=ORIGIN, raw=None, content_type=None):
    headers = {'X-Forwarded-For': xff}
    if origin: headers['Origin'] = origin
    if token: headers['Authorization'] = 'Bearer ' + token
    data = raw
    if body is not None:
        data = json.dumps(body).encode(); headers['Content-Type'] = 'application/json'
    if content_type: headers['Content-Type'] = content_type
    request = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with OPENER.open(request, timeout=120) as response:
            status, text = response.status, response.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as error:
        status, text = error.code, error.read().decode('utf-8', 'replace')
    except (urllib.error.URLError, ConnectionError):
        # The server answered and hung up before the whole body was sent (oversized uploads).
        return 0, 'connection closed by the server'
    try:
        return status, json.loads(text)
    except ValueError:
        return status, text[:200]


def check(name, ok, seen):
    RESULTS.append(bool(ok))
    print('%s  %-58s %s' % ('PASS' if ok else 'FAIL', name, str(seen)[:110]))


def message(payload):
    return payload.get('message') or payload.get('error') or '' if isinstance(payload, dict) else str(payload)


def invite(*args):
    out = subprocess.run(['bash', os.path.join(HERE, 'nexus-public.sh'), 'invite', *args], capture_output=True, text=True).stdout
    found = re.search(r'(?:[A-Z0-9]{4}-){5}[A-Z0-9]{4}', out)
    return found.group(0) if found else out


def register(name, code, xff='203.0.113.10'):
    body = {'username': name, 'email': name + '@example.test', 'nickname': name, 'password': 'Synthetic-' + name}
    if code is not None: body['inviteCode'] = code
    return call('POST', '/api/auth/register', body=body, xff=xff)


def multipart(field, filename, content):
    boundary = 'acceptance' + RUN
    head = ('--%s\r\nContent-Disposition: form-data; name="%s"; filename="%s"\r\nContent-Type: application/octet-stream\r\n\r\n'
            % (boundary, field, filename)).encode()
    return head + content + ('\r\n--%s--\r\n' % boundary).encode(), 'multipart/form-data; boundary=' + boundary


print('== registration ==')
status, policy = call('GET', '/api/auth/registration-policy')
check('policy asks for an invite and no email code', policy == {'inviteRequired': True, 'emailCodeRequired': False}, policy)
status, body = register('nobody' + RUN, None)
check('register without an invite is refused', status == 400 and '邀请码' in message(body), (status, message(body)))
status, body = register('nobody' + RUN, 'AAAA-BBBB-CCCC-DDDD-EEEE-FFFF')
check('register with a made-up invite is refused', status == 400 and '邀请码' in message(body), (status, message(body)))
status, body = call('POST', '/api/auth/send-code', body={'email': 'x' + RUN + '@example.test', 'type': 'REGISTER'})
check('no email code can be requested', status == 400, (status, message(body)))
code = invite('create', '--uses', '1', '--days', '1', '--note', 'acceptance')
alice = 'alice' + RUN
status, body = register(alice, code.lower())
token_a = body.get('token') if isinstance(body, dict) else None
check('control: a valid invite registers an account', status == 200 and token_a, status)
status, body = register('second' + RUN, code)
check('the same single-use invite cannot be used again', status == 400 and '邀请码' in message(body), (status, message(body)))
bob = 'bob' + RUN
status, body = register(bob, invite('create', '--uses', '1', '--days', '1', '--note', 'acceptance'))
token_b = body.get('token') if isinstance(body, dict) else None
check('control: a second invite registers a second account', status == 200 and token_b, status)

print('== sign-in lockout and gateway limits ==')
seen = [call('POST', '/api/auth/login', body={'usernameOrEmail': bob, 'password': 'wrong'}, xff='198.51.100.%d' % i)[0] for i in range(1, 9)]
check('8 wrong passwords from 8 addresses are each just refused', seen == [400] * 8, seen)
status, body = call('POST', '/api/auth/login', body={'usernameOrEmail': bob, 'password': 'Synthetic-' + bob}, xff='198.51.100.99')
check('then even the right password is locked out', status == 400 and '尝试次数过多' in message(body), (status, message(body)))
status, body = call('POST', '/api/auth/login', body={'usernameOrEmail': alice, 'password': 'Synthetic-' + alice}, xff='198.51.100.98')
check('control: another account still signs in', status == 200, status)
seen = [call('POST', '/api/auth/login', body={'usernameOrEmail': 'ghost' + RUN, 'password': 'x'}, xff='192.0.2.%d' % (int(RUN) % 250 + 1))[0] for _ in range(13)]
check('one address gets 10 sign-in attempts a minute, then 429', seen == [400] * 10 + [429] * 3, seen)

print('== requests the server would make on a user\'s behalf ==')
provider = lambda url: call('POST', '/api/agent/providers', token=token_a, body={
    'provider': 'openai', 'displayName': 'acceptance', 'baseUrl': url, 'defaultModel': 'm', 'apiKey': 'sk-acceptance-not-a-real-key'})
status, body = provider('https://api.deepseek.com/v1')
provider_id = (body.get('data') or {}).get('id') if isinstance(body, dict) else None
check('control: a public model endpoint is accepted', status == 200 and provider_id, (status, message(body)))
lan = subprocess.run(['ipconfig', 'getifaddr', 'en0'], capture_output=True, text=True).stdout.strip() or '10.0.0.1'
for label, url in [('this host (loopback)', 'http://127.0.0.1:8080/v1'), ('this host (IPv6 loopback)', 'http://[::1]:8100/v1'),
                   ('the Mac as the VM sees it', 'http://192.168.5.2:6379/v1'), ('the Mac on the home network', 'http://%s:3306/v1' % lan),
                   ('a private range', 'http://10.0.0.1/v1'), ('the tailnet range', 'http://100.100.100.100/v1'),
                   ('cloud metadata', 'http://169.254.169.254/latest'), ('the database container by name', 'http://mysql:3306/v1'),
                   ('the cache container by name', 'http://redis:6379/v1'), ('a public name that resolves to loopback', 'http://localtest.me:8080/v1')]:
    status, body = provider(url)
    check('model endpoint refused: ' + label, status == 400, (status, message(body)[:70]))
if provider_id:
    status, body = call('POST', '/api/agent/providers/%s/test' % provider_id, token=token_a)
    data = body.get('data') if isinstance(body, dict) else None
    check('control: the public endpoint is really contacted (key rejected by it)', status == 200 and isinstance(data, dict), (status, json.dumps(data, ensure_ascii=False)[:90]))

def mcp_state(url):
    status, body = call('POST', '/api/agent/mcp/servers', token=token_a, body={
        'displayName': 'acceptance', 'url': url, 'transport': 'streamable_http', 'enabled': True, 'timeoutSec': 8, 'resultMaxChars': 1000, 'allowedTools': []})
    data = body.get('data') if isinstance(body, dict) else None
    if status != 200 or not isinstance(data, dict):
        return 'refused when saved (%s)' % status
    status, body = call('POST', '/api/agent/mcp/servers/%s/test' % data['serverId'], token=token_a, body={'expectedConfigVersion': data.get('configVersion')})
    result = body.get('data') if isinstance(body, dict) else None
    return ((result or {}).get('status') or {}).get('state') or 'no state (%s)' % status


for label, url in [('this host', 'https://127.0.0.1:8080/mcp'), ('the Mac', 'https://192.168.5.2:7890/mcp'),
                   ('the database container', 'https://mysql:3306/mcp'), ('plain http', 'http://mirrors.aliyun.com/mcp')]:
    state = mcp_state(url)
    check('MCP server pointing at %s does not connect' % label, state != 'connected', state)
if '--public-mcp' in sys.argv:
    state = mcp_state(sys.argv[sys.argv.index('--public-mcp') + 1])
    check('control: a real public MCP server connects', state == 'connected', state)

print('== what the gateway exposes ==')
for label, method, path, origin, token, want in [
        ('internal readiness probe', 'GET', '/internal/funnel/readiness', ORIGIN, None, (404,)),
        ('internal agent API', 'POST', '/internal/agent/publish', ORIGIN, None, (404,)),
        ('actuator', 'GET', '/actuator/health', ORIGIN, None, (404,)),
        ('path traversal', 'GET', '/uploads/..%2f..%2fetc/passwd', ORIGIN, None, (404,)),
        ('another site calling the API', 'GET', '/api/auth/registration-policy', 'https://evil.example', None, (403,)),
        ('signed-out access to private data', 'GET', '/api/agent/sessions', ORIGIN, None, (401, 403)),
        ('a forged token', 'GET', '/api/agent/sessions', ORIGIN, 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.forged', (401, 403))]:
    status, body = call(method, path, token=token, origin=origin)
    check('refused: ' + label, status in want, status)
status, body = call('GET', '/api/agent/sessions', token=token_a)
check('control: the owner reads their own sessions', status == 200, status)

print('== one account reaching into another ==')
status, body = call('POST', '/api/agent/sessions', token=token_a, body={'entryMode': 'ASSISTANT_CHAT', 'title': 'acceptance'})
session = ((body.get('data') or {}).get('sessionId') or (body.get('data') or {}).get('id')) if isinstance(body, dict) else None
check('control: an account creates a session', status == 200 and session, (status, str(body)[:80]))
if session:
    check('control: its owner can read it', call('GET', '/api/agent/sessions/%s' % session, token=token_a)[0] == 200, '')
    status, body = call('GET', '/api/agent/sessions/%s/messages' % session, token=token_b)
    check('another account cannot read its messages', status in (403, 404), status)
    status, body = call('DELETE', '/api/agent/sessions/%s' % session, token=token_b)
    check('another account cannot delete it', status in (403, 404), status)
    check('control: it is still there for its owner', call('GET', '/api/agent/sessions/%s' % session, token=token_a)[0] == 200, '')

print('== quotas and closed features ==')
raw, ctype = multipart('file', 'note.txt', b'acceptance ' * 100)
status, body = call('POST', '/api/files/upload', token=token_a, raw=raw, content_type=ctype)
check('control: a small upload is stored', status == 200, (status, str(body)[:60]))
raw, ctype = multipart('file', 'model.vrm', b'x' * 64)
status, body = call('POST', '/api/companion/assets/model', token=token_a, raw=raw, content_type=ctype)
check('the shared model catalog is read-only', status == 403, (status, message(body)))
status, body = call('POST', '/api/agent/knowledge', token=token_a, body={'name': 'acceptance'})
check('knowledge bases are closed', status == 403, (status, message(body)))
raw, ctype = multipart('file', 'huge.bin', b'\0' * (66 * 1024 * 1024))
status, body = call('POST', '/api/files/upload', token=token_a, raw=raw, content_type=ctype)
check('a single request over 65 MB is refused', status in (0, 400, 413), (status, str(body)[:40]))
if '--big-uploads' in sys.argv:
    raw, ctype = multipart('file', 'big.bin', os.urandom(1024) * (60 * 1024))
    seen = [call('POST', '/api/files/upload', token=token_b, raw=raw, content_type=ctype)[0] for _ in range(9)]
    check('an account is stopped after 500 MB of uploads in a day', seen[:8] == [200] * 8 and seen[8] == 429, seen)
    check('control: the other account can still upload', call('POST', '/api/files/upload', token=token_a, raw=raw[:2000] + raw[-60:], content_type=ctype)[0] in (200, 400), '')

print('\n%d of %d checks passed' % (sum(RESULTS), len(RESULTS)))
sys.exit(0 if all(RESULTS) else 1)
