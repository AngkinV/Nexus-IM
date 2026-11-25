import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeUser, normalizeChat, resolveAvatarUrl, createRevisionLedger, parseTypedEvent } from './socialData.mjs'

test('ContactDTO relationship id is never the user id; sparse and explicit empty differ', () => {
    assert.deepEqual(normalizeUser({ id: 91, userId: 7, avatarUrl: null, nickname: '' }), { id: 7, userId: 7, relationId: 91, avatarUrl: null, avatar: '', nickname: '' })
    assert.deepEqual(normalizeUser({ id: 7 }), { id: 7 })
    assert.equal(normalizeUser({ id: 7, isOnline: 'false' }).isOnline, undefined)
    assert.equal(normalizeUser({ id: 7, isOnline: null }).isOnline, null)
    assert.equal(normalizeUser({ id: 7, isOnline: false }).isOnline, false)
})
test('direct sender and receiver see the other user, not createdBy', () => {
    const dto = { id: 4, type: 'direct', createdBy: 1, members: [{ id: 1, avatarUrl: '/uploads/a.png', isOnline: true }, { id: 2, avatarUrl: '/uploads/b.png', isOnline: false }] }
    assert.equal(normalizeChat(dto, '1').contactId, 2)
    assert.equal(normalizeChat(dto, 2).contactId, 1)
    assert.equal(normalizeChat(dto, 1).online, false)
    assert.equal(normalizeChat(dto, 2).online, true)
    assert.equal(normalizeChat(dto, 1).avatar, '/uploads/b.png')
})
test('direct root cache aliases cannot override the actual peer presence or identity', () => {
    const dto = { id: 4, type: 'direct', isOnline: true, online: true,
        members: [{ id: 1 }, { id: 7, nickname: null, username: 'server-peer', isOnline: false }] }
    const chat = normalizeChat(dto, '1')
    assert.equal(Object.hasOwn(chat, 'isOnline'), false)
    assert.equal(chat.online, false)
    assert.equal(chat.status, 'offline')
    assert.equal(chat.name, 'server-peer')
    dto.members[1].isOnline = null
    assert.equal(normalizeChat(dto, 1).online, null)
})
test('group never borrows a member portrait or fabricates overall presence/count', () => {
    const group = normalizeChat({ id: 3, type: 'group', online: true, status: 'online', members: [{ id: 1, avatarUrl: '/person.png' }] }, 1)
    assert.equal(group.avatar, '')
    assert.equal(group.memberCount, 1)
    assert.ok(!('online' in group)); assert.ok(!('status' in group))
    assert.equal(normalizeChat({ id: 3, type: 'group' }, 1).memberCount, undefined)
    assert.equal(normalizeChat({ id: 3, type: 'group', memberCount: 0, members: [] }, 1).memberCount, 0)
})
test('URL protocol allowlist preserves uploads and local preview without /api', () => {
    const resolve = v => resolveAvatarUrl(v, 'http://localhost:8080/')
    for (const url of ['https://a/b', 'http://a/b', 'blob:http://localhost/id', 'data:image/png;base64,AA']) assert.equal(resolve(url), url)
    assert.equal(resolve('/uploads/a.png'), 'http://localhost:8080/uploads/a.png')
    for (const url of ['javascript:alert(1)', 'data:text/html,x', 'file:///etc/a', '//evil/a']) assert.equal(resolve(url), '')
})
test('legacy backend upload avatars use the current backend while external avatars remain external', () => {
    const base = 'https://lvmacbook-air.tail007e54.ts.net'
    for (const host of ['http://localhost:8080', 'http://127.0.0.1:8080', 'https://47.118.29.122']) {
        assert.equal(resolveAvatarUrl(`${host}/uploads/avatars/avatar.png?token=synthetic-old-token`, base), `${base}/uploads/avatars/avatar.png`)
    }
    assert.equal(resolveAvatarUrl('https://cdn.example.test/avatars/person.png', base), 'https://cdn.example.test/avatars/person.png')
    const credentialed = new URL('https://cdn.example.test/person.png')
    credentialed.username = 'fixture'
    assert.equal(resolveAvatarUrl(credentialed.href, base), '')
})
test('field revision: late snapshot cannot undo avatar/presence WS; unprovided fields survive', () => {
    const ledger = createRevisionLedger(); ledger.setOwner(1)
    let record = ledger.merge(7, {}, { id: 7, nickname: 'original', avatar: 'old', isOnline: true })
    const token = ledger.capture()
    record = ledger.merge(7, record, { avatar: 'new', isOnline: false })
    record = ledger.merge(7, record, { avatar: 'old', isOnline: true, nickname: 'HTTP' }, token)
    assert.equal(record.avatar, 'new'); assert.equal(record.isOnline, false); assert.equal(record.nickname, 'HTTP')
    record = ledger.merge(7, record, { avatar: '' }); assert.equal(record.avatar, '')
    record = ledger.merge(7, record, { avatar: undefined }); assert.equal(record.avatar, '')
})
test('account A→B→A drops old HTTP, duplicate updates upsert one key', () => {
    const ledger = createRevisionLedger(); ledger.setOwner(1); const token = ledger.capture()
    ledger.setOwner(2); assert.equal(ledger.merge(7, {}, { id: 7 }, token), null)
    ledger.setOwner(1); assert.equal(ledger.merge(7, {}, { id: 7 }, token), null)
    const map = new Map(); for (let i = 0; i < 2; i++) map.set(7, ledger.merge(7, map.get(7), { id: 7, nickname: 'real' }))
    assert.equal(map.size, 1)
})
test('disconnect invalidation is unknown, not offline, and beats an in-flight green snapshot', () => {
    const ledger = createRevisionLedger(); ledger.setOwner(1)
    let record = ledger.merge(7, {}, { isOnline: true, lastSeen: null }); const token = ledger.capture()
    record = ledger.merge(7, record, { isOnline: null })
    record = ledger.merge(7, record, { isOnline: true }, token)
    assert.equal(record.isOnline, null); assert.equal(record.lastSeen, null)
})
test('typed event rejects malformed frames without throwing', () => {
    for (const body of ['{', 'null', '[]', '{"type":"X","payload":[]}']) assert.equal(parseTypedEvent({ body }), null)
    assert.equal(parseTypedEvent({ body: '{"type":"CONTACT_STATUS_CHANGED","payload":{"userId":7,"isOnline":null}}' }).payload.isOnline, null)
})
