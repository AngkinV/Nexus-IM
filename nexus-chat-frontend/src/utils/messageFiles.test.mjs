import test from 'node:test'
import assert from 'node:assert/strict'
import { createMessageFiles } from './messageFiles.mjs'

const SERVER_BASE = 'https://backend.example.test'
const START_TIME = 1_800_000_000_000
const OLD_TOKEN = 'synthetic.old-account.jwt'
const NEW_TOKEN = 'synthetic.new-account.jwt'

const capability = (fileId, expiresAt = START_TIME + 120_000, signature = 'synthetic-capability') => ({
  fileId,
  expiresAt,
  previewUrl: `/api/files/preview/${encodeURIComponent(fileId)}?expires=${expiresAt}&sig=${signature}-preview`,
  downloadUrl: `/api/files/download/${encodeURIComponent(fileId)}?expires=${expiresAt}&sig=${signature}-download`
})

const assertCapabilitySources = (files, message, result, kinds = ['preview', 'download']) => {
  for (const kind of kinds) {
    const source = files.source(message, kind)
    assert.equal(source, new URL(result[`${kind}Url`], SERVER_BASE).href)
    const url = new URL(source)
    assert.equal(url.origin, SERVER_BASE)
    assert.equal(url.username, '')
    assert.equal(url.password, '')
    assert.equal(url.pathname.includes('/api/api/'), false)
    for (const token of [OLD_TOKEN, NEW_TOKEN]) assert.equal(source.includes(token), false)
    for (const name of ['token', 'access_token', 'Authorization']) assert.equal(url.searchParams.has(name), false)
  }
}

const deferred = () => {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}

test('/api/files and /files metadata normalize without a double /api prefix', async t => {
  const cases = [
    ['API paths', { previewUrl: '/api/files/preview/file-a', downloadUrl: '/api/files/download/file-a' }],
    ['server-relative paths', { previewUrl: '/files/preview/file-a', downloadUrl: '/files/download/file-a' }],
    ['fileUrl fallback', { fileUrl: '/files/download/file-a' }],
    ['absolute paths with stale tokens', {
      previewUrl: `${SERVER_BASE}/api/files/preview/file-a?token=${OLD_TOKEN}`,
      downloadUrl: `${SERVER_BASE}/files/download/file-a?access_token=${OLD_TOKEN}`
    }],
    ['content fallback', { content: `/api/files/preview/file-a?token=${OLD_TOKEN}` }]
  ]
  for (const [name, message] of cases) {
    await t.test(name, async () => {
      const calls = []
      const result = capability('file-a')
      const files = createMessageFiles({
        serverBase: `${SERVER_BASE}/api`,
        now: () => START_TIME,
        getAccess: async reference => { calls.push(reference); return result }
      })
      assert.equal(files.source(message), '')
      assert.equal(files.source(message, 'download'), '')
      await files.refresh([message])
      assert.deepEqual(calls, ['/api/files/download/file-a'])
      assertCapabilitySources(files, message, result, message.content ? ['preview'] : undefined)
      if (message.content) assert.equal(files.source(message, 'download'), '')
    })
  }
})

test('/uploads references use the backend host, never /api/uploads', async () => {
  const calls = []
  const message = { fileUrl: `/uploads/chat/photo.png?token=${OLD_TOKEN}` }
  const avatar = { fileUrl: '/uploads/avatars/profile.png' }
  const result = capability('uploaded-file')
  const files = createMessageFiles({
    serverBase: `${SERVER_BASE}/api`,
    now: () => START_TIME,
    getAccess: async reference => { calls.push(reference); return result }
  })
  await files.refresh([message, avatar])
  assert.deepEqual(calls, ['/uploads/chat/photo.png'])
  assertCapabilitySources(files, message, result)
  assert.equal(files.source(avatar), `${SERVER_BASE}/uploads/avatars/profile.png`)
  assert.equal(files.source(avatar, 'download'), `${SERVER_BASE}/uploads/avatars/profile.png`)
})

test('fileId takes priority and deduplicates preview/download and concurrent refreshes', async () => {
  const calls = []
  let changes = 0
  const result = capability('file-id')
  const message = {
    fileId: 'file-id',
    previewUrl: 'https://cdn.example.test/unrelated.png',
    downloadUrl: '/api/files/download/other-file'
  }
  const files = createMessageFiles({
    serverBase: SERVER_BASE,
    now: () => START_TIME,
    onChange: () => { changes++ },
    getAccess: async reference => { calls.push(reference); return result }
  })
  await Promise.all([files.refresh([message, { ...message }]), files.refresh([message])])
  assert.deepEqual(calls, ['/api/files/download/file-id'])
  assert.equal(changes, 1)
  assertCapabilitySources(files, message, result)
  await files.refresh([message])
  assert.deepEqual(calls, ['/api/files/download/file-id'])
  assert.equal(changes, 1)
})

test('public HTTP(S) assets remain unchanged and never call the signing API', async () => {
  const calls = []
  const messages = [
    {
      previewUrl: 'https://media.example.test/images/photo.png?size=large',
      downloadUrl: 'https://downloads.example.test/files/report.pdf?revision=2'
    },
    { fileUrl: 'http://cdn.example.test/audio/track.mp3' },
    { content: 'https://media.example.test/images/content.jpg' },
    { previewUrl: '//media.example.test/images/photo.png', downloadUrl: '//downloads.example.test/files/report.pdf' }
  ]
  const files = createMessageFiles({
    serverBase: SERVER_BASE,
    now: () => START_TIME,
    getAccess: async reference => { calls.push(reference); return capability('unexpected') }
  })
  await files.refresh(messages)
  for (const message of messages) {
    assert.equal(files.source(message), new URL(message.previewUrl || message.fileUrl || message.content, SERVER_BASE).href)
    assert.equal(files.source(message, 'download'), message.downloadUrl || message.fileUrl
      ? new URL(message.downloadUrl || message.fileUrl, SERVER_BASE).href
      : '')
    for (const kind of ['preview', 'download']) {
      for (const token of [OLD_TOKEN, NEW_TOKEN]) assert.equal(files.source(message, kind).includes(token), false)
    }
  }
  assert.deepEqual(calls, [])
})

test('unsafe URL schemes never become preview or download sources', async () => {
  const calls = []
  const values = ['javascript:alert(1)', 'JaVaScRiPt:alert(1)', 'data:text/html,unsafe', 'file:///private/file', 'blob:https://backend.example.test/file', 'ftp://files.example.test/file']
  const messages = values.map(value => ({ previewUrl: value, downloadUrl: value, fileUrl: value, content: value }))
  const files = createMessageFiles({
    serverBase: SERVER_BASE,
    now: () => START_TIME,
    getAccess: async reference => { calls.push(reference); return capability('unexpected') }
  })
  await files.refresh(messages)
  for (const message of messages) {
    assert.equal(files.source(message), '')
    assert.equal(files.source(message, 'download'), '')
  }
  assert.deepEqual(calls, [])
})

test('cached capabilities renew near expiry and expired URLs are not returned', async () => {
  let clock = START_TIME
  let requests = 0
  let latest
  const message = { fileId: 'renewed-file' }
  const files = createMessageFiles({
    serverBase: SERVER_BASE,
    now: () => clock,
    getAccess: async () => {
      latest = capability('renewed-file', clock + 90_000, `synthetic-${++requests}`)
      return latest
    }
  })
  await files.refresh([message])
  assertCapabilitySources(files, message, latest)
  clock += 30_000
  await files.refresh([message])
  assert.equal(requests, 1)
  clock += 30_001
  await files.refresh([message])
  assert.equal(requests, 2)
  assertCapabilitySources(files, message, latest)
  clock = latest.expiresAt
  assert.equal(files.source(message), '')
  assert.equal(files.source(message, 'download'), '')
  await files.refresh([message])
  assert.equal(requests, 3)
  assertCapabilitySources(files, message, latest)
})

test('expired or tampered capability destinations cannot be used', async t => {
  const valid = capability('protected-file')
  const cases = [
    ['expired capability', { ...valid, expiresAt: START_TIME }],
    ['invalid expiry', { ...valid, expiresAt: String(valid.expiresAt) }],
    ['foreign preview origin', { ...valid, previewUrl: `https://attacker.example.test${valid.previewUrl}` }],
    ['foreign download origin', { ...valid, downloadUrl: `https://attacker.example.test${valid.downloadUrl}` }],
    ['wrong preview destination', { ...valid, previewUrl: '/uploads/protected-file' }],
    ['wrong download destination', { ...valid, downloadUrl: valid.previewUrl }],
    ['unsafe destination scheme', { ...valid, previewUrl: 'javascript:alert(1)' }],
    ['credential-bearing destination', { ...valid, previewUrl: `https://${OLD_TOKEN}@backend.example.test${valid.previewUrl}` }]
  ]
  for (const [name, result] of cases) {
    await t.test(name, async () => {
      const calls = []
      const message = { fileId: 'protected-file', fileUrl: '/api/files/download/protected-file' }
      const files = createMessageFiles({
        serverBase: SERVER_BASE,
        now: () => START_TIME,
        getAccess: async reference => { calls.push(reference); return result }
      })
      await files.refresh([message])
      assert.deepEqual(calls, ['/api/files/download/protected-file'])
      assert.equal(files.source(message), '')
      assert.equal(files.source(message, 'download'), '')
    })
  }
})

test('a synchronous authorization failure clears pending state so refresh can retry', async () => {
  let requests = 0
  const message = { fileId: 'retry-file' }
  const result = capability('retry-file')
  const files = createMessageFiles({
    serverBase: SERVER_BASE,
    now: () => START_TIME,
    getAccess: () => {
      if (++requests === 1) throw new Error('Synthetic authorization failure')
      return result
    }
  })
  await files.refresh([message])
  assert.equal(files.source(message), '')
  assert.equal(files.source(message, 'download'), '')
  await files.refresh([message])
  assert.equal(requests, 2)
  assertCapabilitySources(files, message, result)
})

test('absolute legacy public avatar URLs use the current backend without stale query credentials', async () => {
  const messages = [{ fileUrl: 'http://localhost:8080/uploads/avatars/avatar.png?token=synthetic-old-token' }]
  const files = createMessageFiles({ serverBase: SERVER_BASE, getAccess: () => { throw new Error('Avatar signing must not run') } })
  await files.refresh(messages)
  assert.equal(files.source(messages[0]), `${SERVER_BASE}/uploads/avatars/avatar.png`)
})

test('minute polling can renew capabilities early enough to avoid an expired-link gap', async () => {
  let clock = START_TIME
  let requests = 0
  const message = { fileId: 'minute-refresh' }
  const files = createMessageFiles({
    serverBase: SERVER_BASE, renewBeforeMs: 90_000, now: () => clock,
    getAccess: async () => capability('minute-refresh', clock + 120_000, `synthetic-${++requests}`)
  })
  await files.refresh([message])
  clock += 60_000
  await files.refresh([message])
  assert.equal(requests, 2)
  assert.ok(files.source(message))
  clock += 60_000
  await files.refresh([message])
  assert.equal(requests, 3)
  assert.ok(files.source(message))
})

test('reset/account switch ignores stale responses and preserves the new request', async t => {
  for (const order of ['old response first', 'new response first']) {
    await t.test(order, async () => {
      const oldResponse = deferred()
      const newResponse = deferred()
      const oldResult = capability('shared-file', START_TIME + 120_000, 'old-capability')
      const newResult = capability('shared-file', START_TIME + 120_000, 'new-capability')
      const message = { fileId: 'shared-file' }
      const calls = []
      let token = OLD_TOKEN
      let changes = 0
      const files = createMessageFiles({
        serverBase: SERVER_BASE,
        now: () => START_TIME,
        onChange: () => { changes++ },
        getAccess: reference => {
          calls.push({ reference, token })
          return token === OLD_TOKEN ? oldResponse.promise : newResponse.promise
        }
      })
      const oldRefresh = files.refresh([message])
      await Promise.resolve()
      files.reset()
      token = NEW_TOKEN
      const newRefresh = files.refresh([message])
      await Promise.resolve()
      if (order === 'old response first') {
        oldResponse.resolve(oldResult)
        await oldRefresh
        assert.equal(files.source(message), '')
        assert.equal(changes, 1)
        const duplicateRefresh = files.refresh([message])
        await Promise.resolve()
        newResponse.resolve(newResult)
        await Promise.all([newRefresh, duplicateRefresh])
      } else {
        newResponse.resolve(newResult)
        await newRefresh
        assertCapabilitySources(files, message, newResult)
        oldResponse.resolve(oldResult)
        await oldRefresh
      }
      assert.deepEqual(calls, [
        { reference: '/api/files/download/shared-file', token: OLD_TOKEN },
        { reference: '/api/files/download/shared-file', token: NEW_TOKEN }
      ])
      assert.equal(changes, 2)
      assertCapabilitySources(files, message, newResult)
      files.reset()
      assert.equal(files.source(message), '')
      assert.equal(files.source(message, 'download'), '')
    })
  }
})
