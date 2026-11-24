import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createPinia, setActivePinia } from 'pinia'
import { reactive } from 'vue'
import { resolveAvatarUrl } from '../utils/socialData.mjs'

// Load the real setup-store bodies, replacing only boundary imports. No HTTP,
// IndexedDB, local credentials or application services are touched by this suite.
const fixtureKey = '__nexusSocialStoreUnitFixture'
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
async function loadStore(file, f) {
    let source = await readFile(new URL(file, import.meta.url), 'utf8')
    source = source.replace(/from 'pinia'/g, `from '${import.meta.resolve('pinia')}'`).replace(/from 'vue'/g, `from '${import.meta.resolve('vue')}'`)
    source = source.replace(/from '@\/utils\/socialData.mjs'/g, `from '${new URL('../utils/socialData.mjs', import.meta.url).href}'`)
    const bindings = {
        '@/services/api': 'const { contactAPI, userAPI, chatAPI, resolveFileUrl } = f',
        '@/services/offlineStore': 'const offlineStore = f.offlineStore',
        '@/stores/user': 'const useUserStore = () => f.user',
        '@/stores/chat': 'const useChatStore = () => f.chat',
        '@/stores/contact': 'const useContactStore = () => f.contact',
        '@/stores/agent': "const AI_ASSISTANT_CHAT_ID = 'ai'; const buildAiAssistantChatItem = () => ({ id: 'ai' })",
        '@/locales/i18n': "const i18n = { global: { t: x => x, locale: { value: 'en' } } }"
    }
    source = source.replace(/^import .* from '(@\/[^']+)'$/gm, (line, key) => bindings[key] || line)
    source = `const f = globalThis.${fixtureKey};\n${source}`
    globalThis[fixtureKey] = f
    return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}#${Math.random()}`)
}
async function setup() {
    const memory = new Map()
    globalThis.localStorage = { getItem: k => memory.get(k) ?? null, setItem: (k, v) => memory.set(k, v), removeItem: k => memory.delete(k) }
    setActivePinia(createPinia())
    const f = {
        user: reactive({ currentUser: { id: 1 } }),
        resolveFileUrl: v => resolveAvatarUrl(v, 'https://server'), userAPI: {}, contactAPI: {},
        // Read receipts are recorded so the badge tests can assert on the PUT
        // that keeps a cleared unread count from coming back.
        readReceipts: [],
        savedChats: [],
        chatAPI: {
            markChatMessagesAsRead: async (chatId, userId) => { f.readReceipts.push([chatId, userId]) }
        },
        offlineStore: {
            saveContacts: async () => {},
            saveChats: async chats => { f.savedChats.push(chats.map(c => ({ id: c.id, unreadCount: c.unreadCount }))) },
            getContacts: async () => [], getChats: async () => [], deleteChat: async () => {}
        }
    }
    f.contact = (await loadStore('./contact.js', f)).useContactStore()
    f.chat = (await loadStore('./chat.js', f)).useChatStore()
    return f
}
test('real stores: group member is identity, not a friend; sparse profile reaches active peer', async () => {
    const f = await setup()
    f.chat.mergeChats([{ id: 10, type: 'group', members: [{ id: 7, nickname: 'member', avatarUrl: '/uploads/old.png', isOnline: true, role: 'admin' }] }], 1)
    assert.equal(f.contact.isContact(7), false)
    assert.equal(f.contact.getUserIdentity(7).nickname, 'member')
    f.chat.mergeChats([{ id: 11, type: 'direct', members: [{ id: 1 }, { id: 7 }] }], 1)
    f.chat.setActiveChat(f.chat.chats.find(c => c.id === 11))
    f.contact.updateUserPresence(7, { avatarUrl: '/uploads/new.png' })
    assert.equal(f.chat.activeChat.avatar, 'https://server/uploads/new.png')
    assert.equal(f.contact.getUserIdentity(7).isOnline, true)
    assert.equal(f.chat.chats.find(c => c.id === 10).members[0].role, 'admin')
    f.contact.invalidatePresence()
    assert.equal(f.chat.activeChat.online, null)
    assert.equal(f.contact.getUserIdentity(7).lastSeen, undefined)
})
test('real stores: duplicate HTTP+WS, late HTTP vs profile, account switching', async () => {
    const f = await setup(), request = deferred()
    f.contactAPI.getContacts = () => request.promise
    const pending = f.contact.fetchContacts(1)
    f.contact.addContact({ id: 99, userId: 7, avatarUrl: '/new.png', isOnline: false })
    request.resolve({ data: [{ id: 99, userId: 7, avatarUrl: '/old.png', isOnline: true, nickname: 'HTTP' }] })
    await pending
    assert.equal(f.contact.contacts.length, 1)
    assert.equal(f.contact.contacts[0].id, 7)
    assert.equal(f.contact.contacts[0].relationId, 99)
    assert.equal(f.contact.contacts[0].avatar, 'https://server/new.png')
    assert.equal(f.contact.contacts[0].isOnline, false)
    const token = f.contact.captureIdentityRevision()
    f.user.currentUser = { id: 2 }
    assert.equal(f.contact.ingestUserSnapshots([{ id: 7, isOnline: true }], token), false)
    assert.equal(f.contact.getUserIdentity(7), null)
    assert.equal(f.contact.contacts.length, 0)
    assert.equal(f.chat.chats.length, 0)
})
test('real contacts: route string IDs resolve and known offline sorts before unknown', async () => {
    const f = await setup()
    f.contact.addContact({ id: 9, nickname: 'A-hidden', isOnline: null })
    f.contact.addContact({ id: 8, nickname: 'B-away', isOnline: false })
    f.contact.addContact({ id: 7, nickname: 'Z-active', isOnline: true })
    assert.equal(f.contact.getContactById('7').nickname, 'Z-active')
    assert.deepEqual(f.contact.sortedContacts.map(c => c.id), [7, 8, 9])
    assert.deepEqual(f.contact.onlineContacts.map(c => c.id), [7])
    assert.deepEqual(f.contact.offlineContacts.map(c => c.id), [8])
})
test('real stores: contact removal beats older full snapshot; cache presence is not evidence', async () => {
    const f = await setup()
    f.contact.addContact({ id: 99, userId: 7, isOnline: true })
    const request = deferred(); f.contactAPI.getContacts = () => request.promise
    const pending = f.contact.fetchContacts(1)
    f.contact.removeContact(7)
    request.resolve({ data: [{ id: 99, userId: 7, isOnline: true }] }); await pending
    assert.equal(f.contact.contacts.length, 0)
    f.offlineStore.getContacts = async () => [{ id: 8, _socialOwner: 1, isOnline: true }, { id: 9, _socialOwner: 2, isOnline: true }]
    await f.contact.loadFromCache()
    assert.equal(f.contact.getUserIdentity(8).isOnline, null)
    assert.equal(f.contact.getUserIdentity(9), null)
})
test('real stores: opening a chat persists the read state so the badge stays gone', async () => {
    const f = await setup()
    const flush = () => new Promise(r => setTimeout(r, 0))
    const snapshot = () => [{ id: 11, type: 'direct', members: [{ id: 1 }, { id: 7 }], unreadCount: 1 }]

    f.chat.mergeChats(snapshot(), 1)
    assert.equal(f.chat.chats.find(c => c.id === 11).unreadCount, 1)

    f.chat.setActiveChat(f.chat.chats.find(c => c.id === 11))
    await flush()
    assert.equal(f.chat.chats.find(c => c.id === 11).unreadCount, 0)
    assert.deepEqual(f.readReceipts, [[11, 1]])
    assert.deepEqual(f.savedChats.at(-1), [{ id: 11, unreadCount: 0 }])

    // A later snapshot still carrying the old server count must not revive it;
    // because that means the reset has not landed yet, it self-heals with a
    // second receipt instead of trusting the stale number.
    f.chat.mergeChats(snapshot(), 1)
    await flush()
    assert.equal(f.chat.chats.find(c => c.id === 11).unreadCount, 0)
    assert.equal(f.chat.activeChat.unreadCount, 0)
    assert.deepEqual(f.readReceipts, [[11, 1], [11, 1]])

    // Re-activating the same chat sends no duplicate receipt.
    f.chat.setActiveChat(f.chat.chats.find(c => c.id === 11))
    await flush()
    assert.equal(f.readReceipts.length, 2)

    // A closed chat keeps the server count; reopening sends a fresh receipt.
    f.chat.setActiveChat(null)
    f.chat.mergeChats(snapshot(), 1)
    await flush()
    assert.equal(f.chat.chats.find(c => c.id === 11).unreadCount, 1)
    f.chat.setActiveChat(f.chat.chats.find(c => c.id === 11))
    await flush()
    assert.deepEqual(f.readReceipts, [[11, 1], [11, 1], [11, 1]])

    // The virtual AI chat has no backend row, so it must not send a receipt.
    f.chat.setActiveChat({ id: 'ai', isAi: true })
    await flush()
    assert.equal(f.readReceipts.length, 3)
})

test('real stores: mark-all-read also persists, and the AI chat is skipped', async () => {
    const f = await setup()
    const flush = () => new Promise(r => setTimeout(r, 0))
    f.chat.mergeChats([
        { id: 11, type: 'direct', members: [{ id: 1 }, { id: 7 }], unreadCount: 2 },
        { id: 12, type: 'direct', members: [{ id: 1 }, { id: 8 }], unreadCount: 1 }
    ], 1)

    f.chat.markAllChatsRead()
    await flush()
    assert.deepEqual(f.chat.chats.map(c => c.unreadCount), [0, 0])
    assert.equal(f.chat.totalUnreadCount, 0)
    assert.deepEqual(f.readReceipts.map(r => r[0]).sort((a, b) => a - b), [11, 12])

    // Everything is already read: no further requests.
    f.chat.markAllChatsRead()
    await flush()
    assert.equal(f.readReceipts.length, 2)
})
