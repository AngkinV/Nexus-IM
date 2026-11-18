<template>
  <div class="contact-directory">
    <div ref="listRef" class="directory-list">
      <!-- Portal: incoming friend requests -->
      <div class="portal-list">
        <button class="portal-row" type="button" @click="showRequests = !showRequests">
          <span class="portal-main">
            <span class="portal-icon">
              <span class="material-symbols-outlined">person_add</span>
            </span>
            <span class="portal-label">{{ $t('contact.newContacts') }}</span>
          </span>
          <span v-if="contactStore.pendingRequests.length > 0" class="portal-badge">
            {{ contactStore.pendingRequests.length }}
          </span>
          <span v-else class="portal-count">0</span>
        </button>

        <template v-if="showRequests">
          <div
            v-for="request in contactStore.pendingRequests"
            :key="request.id"
            class="contact-row request-row"
          >
            <div class="avatar-wrap">
              <img
                v-if="request.fromAvatarUrl && !failedAvatars['r' + request.id]"
                class="avatar-img"
                :src="request.fromAvatarUrl"
                alt=""
                @error="failedAvatars['r' + request.id] = true"
              />
              <div v-else class="avatar-initial" :data-initial="initialOf(request.fromNickname)"></div>
            </div>
            <div class="contact-info">
              <span class="contact-name">{{ request.fromNickname }}</span>
              <p class="contact-sub">{{ request.message || '@' + request.fromUsername }}</p>
            </div>
            <div class="request-actions">
              <button class="req-btn accept" :title="$t('common.accept')" type="button" @click="acceptRequest(request)">
                <span class="material-symbols-outlined">check</span>
              </button>
              <button class="req-btn" :title="$t('common.reject')" type="button" @click="rejectRequest(request)">
                <span class="material-symbols-outlined">close</span>
              </button>
            </div>
          </div>
        </template>
      </div>

      <div class="directory-divider"></div>

      <div v-if="filteredContacts.length === 0" class="empty-state">
        <div class="empty-icon">
          <span class="material-symbols-outlined">person</span>
        </div>
        <p class="empty-text">{{ $t('contact.noContacts') }}</p>
      </div>

      <!-- A–Z sections -->
      <div
        v-for="group in contactGroups"
        :key="group.letter"
        class="letter-group"
        :data-letter="group.letter"
      >
        <div class="letter-head">
          <span class="letter-key">{{ group.letter }}</span>
          <span class="letter-count">{{ $t('contact.memberCount', { n: group.items.length }) }}</span>
        </div>

        <div
          v-for="contact in group.items"
          :key="contact.id"
          class="contact-row"
          :class="{ selected: contact.id === selectedId }"
          @click="selectContact(contact)"
        >
          <div class="avatar-wrap">
            <img
              v-if="contact.avatar && !failedAvatars[contact.id]"
              class="avatar-img"
              :src="contact.avatar"
              alt=""
              @error="failedAvatars[contact.id] = true"
            />
            <div v-else class="avatar-initial" :data-initial="initialOf(contact.nickname)"></div>
            <span class="presence-dot" :class="{ online: contact.isOnline }"></span>
          </div>
          <div class="contact-info">
            <span class="contact-name">{{ contact.nickname }}</span>
            <p class="contact-sub">
              {{ contact.isOnline || !contact.lastSeen ? '@' + contact.username : formatLastSeen(contact.lastSeen) }}
            </p>
          </div>
          <div class="contact-actions">
            <button class="row-btn" type="button" :title="$t('chat.sendMessage')" @click.stop="startChat(contact)">
              <span class="material-symbols-outlined">chat_bubble</span>
            </button>
            <button class="row-btn danger" type="button" :title="$t('contact.removeContact')" @click.stop="deleteContact(contact)">
              <span class="material-symbols-outlined">delete</span>
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Fast alphabet index rail -->
    <div class="index-rail">
      <span class="rail-top" @click="scrollToTop">☆</span>
      <span
        v-for="letter in railLetters"
        :key="letter"
        :class="{ active: activeLetters.has(letter) }"
        @click="activeLetters.has(letter) && scrollToLetter(letter)"
      >{{ letter }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useContactStore } from '@/stores/contact'
import { useUserStore } from '@/stores/user'
import { useI18n } from 'vue-i18n'
import { ElMessageBox, ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'

dayjs.extend(relativeTime)

const { t } = useI18n()

const props = defineProps({
  searchQuery: {
    type: String,
    default: ''
  },
  selectedId: {
    type: [Number, String],
    default: null
  }
})

const emit = defineEmits(['select', 'chat'])

const contactStore = useContactStore()
const userStore = useUserStore()

const filteredContacts = computed(() => {
  if (!props.searchQuery) {
    return contactStore.contacts
  }
  const query = props.searchQuery.toLowerCase()
  return contactStore.contacts.filter(contact =>
    contact.nickname.toLowerCase().includes(query) ||
    contact.username.toLowerCase().includes(query)
  )
})

// ── Directory grouping: A–Z sections + index rail ──────────────────────────
// Chinese names are bucketed by pinyin initial using the locale collator, so
// no extra dependency or backend field is needed.
const LETTERS = 'ABCDEFGHJKLMNOPQRSTWXYZ'.split('')
const PINYIN_BOUNDARIES = '阿八嚓哒妸发旮哈讥咔垃痳拏噢妑七呥扨它穵夕丫帀'.split('')
const pinyinCollator = new Intl.Collator('zh-Hans-CN-u-co-pinyin')

const letterOf = (name) => {
  const ch = (name || '').trim().charAt(0)
  if (!ch) return '#'
  if (/[a-zA-Z]/.test(ch)) return ch.toUpperCase()
  if (/[\u4e00-\u9fa5]/.test(ch)) {
    for (let i = LETTERS.length - 1; i >= 0; i--) {
      if (pinyinCollator.compare(ch, PINYIN_BOUNDARIES[i]) >= 0) return LETTERS[i]
    }
  }
  return '#'
}

const initialOf = (name) => (name || '?').trim().charAt(0) || '?'

const contactGroups = computed(() => {
  const buckets = new Map()
  for (const c of filteredContacts.value) {
    const key = letterOf(c.nickname || c.username)
    if (!buckets.has(key)) buckets.set(key, [])
    buckets.get(key).push(c)
  }
  return [...buckets.entries()]
    .sort(([a], [b]) => (a === '#') - (b === '#') || a.localeCompare(b))
    .map(([letter, items]) => ({
      letter,
      items: items.sort((a, b) => pinyinCollator.compare(a.nickname || '', b.nickname || ''))
    }))
})

const activeLetters = computed(() => new Set(contactGroups.value.map((g) => g.letter)))
// The export's rail skips I/O/U/V (no pinyin initials); they appear only when
// a contact actually files under them.
const railLetters = computed(() => [
  ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('').filter((l) => !'IOUV'.includes(l) || activeLetters.value.has(l)),
  '#'
])
const scrollToTop = () => listRef.value?.scrollTo({ top: 0, behavior: 'smooth' })

const listRef = ref(null)
const scrollToLetter = (letter) => {
  listRef.value
    ?.querySelector(`[data-letter="${letter}"]`)
    ?.scrollIntoView({ block: 'start', behavior: 'smooth' })
}

// Friend requests collapse behind the "新的联系人" portal row.
const showRequests = ref(true)
const failedAvatars = ref({})

const selectContact = (contact) => {
  emit('select', contact)
}

const startChat = (contact) => {
  emit('chat', contact)
}

const formatLastSeen = (lastSeen) => {
  if (!lastSeen) return ''
  return `${t('chat.lastSeen')} ${dayjs(lastSeen).fromNow()}`
}

// Accept friend request
const acceptRequest = async (request) => {
  try {
    await contactStore.acceptRequest(request.id, userStore.currentUser?.id)
    ElMessage.success(t('contact.requestAccepted'))
  } catch (error) {
    console.error('Failed to accept request:', error)
    ElMessage.error(t('contact.acceptFailed'))
  }
}

// Reject friend request
const rejectRequest = async (request) => {
  try {
    await ElMessageBox.confirm(
      t('contact.confirmReject', { name: request.fromNickname }),
      t('contact.rejectRequest'),
      {
        confirmButtonText: t('common.ok'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )
    await contactStore.rejectRequest(request.id, userStore.currentUser?.id)
    ElMessage.success(t('contact.requestRejected'))
  } catch (error) {
    if (error !== 'cancel') {
      console.error('Failed to reject request:', error)
      ElMessage.error(t('contact.rejectFailed'))
    }
  }
}

// Delete contact with confirmation
const deleteContact = async (contact) => {
  try {
    await ElMessageBox.confirm(
      t('contact.confirmRemove', { name: contact.nickname }),
      t('contact.removeContact'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )

    // Call store method to remove contact
    await contactStore.removeContactRequest(userStore.currentUser?.id, contact.id)
    ElMessage.success(t('contact.removeSuccess'))
  } catch (error) {
    if (error !== 'cancel') {
      console.error('Failed to remove contact:', error)
      ElMessage.error(t('contact.removeFailed'))
    }
  }
}
</script>

<style scoped>
/* Prism c2 directory — values are the exported screen's Tailwind classes
   resolved to CSS (p-space-sm, pr-6 for the rail, space-y-space-md). */
.contact-directory {
  position: relative;
  display: flex;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.directory-list {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 8px 24px 8px 8px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  user-select: none;
  scrollbar-width: none;
}

.directory-list::-webkit-scrollbar {
  display: none;
}

/* ── Portal rows ────────────────────────────────────────── */
.portal-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.portal-row {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px;
  border: none;
  border-radius: 4px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.portal-row:hover {
  background: #1b1b1e;
}

.portal-main {
  display: flex;
  align-items: center;
  gap: 8px;
}

.portal-icon {
  width: 28px;
  height: 28px;
  border-radius: 4px;
  background: #2a2a2d;
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.portal-icon .material-symbols-outlined {
  font-size: 16px;
}

.portal-label {
  font-size: 13px;
  line-height: 18px;
  letter-spacing: 0.01em;
  font-weight: 500;
  color: #e4e1e6;
}

.portal-badge {
  padding: 0 6px;
  border-radius: 4px;
  background: #353438;
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #ffffff;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 12px;
  line-height: 16px;
  font-weight: 500;
  letter-spacing: 0.02em;
}

.portal-count {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 12px;
  line-height: 16px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: rgba(196, 199, 200, 0.7);
}

.directory-divider {
  height: 1px;
  margin: 0 4px;
  background: rgba(68, 71, 72, 0.2);
  flex-shrink: 0;
}

/* ── Letter sections ────────────────────────────────────── */
.letter-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
  scroll-margin-top: 8px;
}

.letter-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 2px 8px;
}

.letter-key {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 12px;
  line-height: 16px;
  font-weight: 600;
  letter-spacing: 0.05em;
  color: #ffffff;
}

.letter-count {
  line-height: 24px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11px;
  color: rgba(196, 199, 200, 0.6);
}

/* ── Contact row ────────────────────────────────────────── */
.contact-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  border-radius: 4px;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.contact-row:hover {
  background: #1b1b1e;
}

/* Selected: bg-surface-container + border-outline-variant/40, brighter ink */
.contact-row.selected {
  background: #1f1f22;
  box-shadow: inset 0 0 0 1px rgba(68, 71, 72, 0.4);
}

.contact-row.selected .contact-name {
  color: #ffffff;
}

.contact-row.selected .contact-sub {
  color: #c4c7c8;
}

.contact-row.selected .presence-dot {
  box-shadow: 0 0 0 2px #1f1f22;
}

.contact-row.selected .avatar-img,
.contact-row.selected .avatar-initial {
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.4);
}

.request-row {
  cursor: default;
}

.avatar-wrap {
  position: relative;
  flex-shrink: 0;
  width: 36px;
  height: 36px;
}

.avatar-img,
.avatar-initial {
  width: 36px;
  height: 36px;
  border-radius: 9999px;
  box-shadow: 0 0 0 1px rgba(68, 71, 72, 0.3);
}

.avatar-img {
  display: block;
  object-fit: cover;
  filter: grayscale(100%) contrast(1.25);
}

.avatar-initial {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #2a2a2d;
  color: #ffffff;
  font-weight: 500;
}

.avatar-initial[data-initial]::before {
  font-size: 13px;
}

.presence-dot {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 8px;
  height: 8px;
  border-radius: 9999px;
  background: #8e9192;
  box-shadow: 0 0 0 2px #0e0e11;
}

.presence-dot.online {
  background: #ffffff;
}

.contact-info {
  flex: 1;
  min-width: 0;
}

.contact-name {
  display: block;
  font-size: 13px;
  line-height: 18px;
  letter-spacing: 0.01em;
  font-weight: 500;
  color: #e4e1e6;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.contact-sub {
  margin: 0;
  font-size: 12px;
  line-height: 16px;
  letter-spacing: 0.02em;
  font-weight: 500;
  color: rgba(196, 199, 200, 0.7);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* Row actions surface on hover only, so the resting row matches the design. */
.contact-actions {
  position: absolute;
  top: 50%;
  right: 6px;
  transform: translateY(-50%);
  display: none;
  gap: 2px;
  padding: 2px;
  border-radius: 4px;
  background: #1b1b1e;
}

.contact-row:hover .contact-actions {
  display: flex;
}

.request-actions {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}

.row-btn,
.req-btn {
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #c4c7c8;
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.row-btn .material-symbols-outlined,
.req-btn .material-symbols-outlined {
  font-size: 16px;
}

.row-btn:hover,
.req-btn:hover {
  background: #2a2a2d;
  color: #ffffff;
}

.row-btn.danger:hover {
  background: rgba(255, 180, 171, 0.12);
  color: #ffb4ab;
}

.req-btn {
  background: #2a2a2d;
}

.req-btn.accept {
  background: #ffffff;
  color: #2f3131;
}

.req-btn.accept:hover {
  background: #e2e2e2;
  color: #2f3131;
}

/* ── Index rail ─────────────────────────────────────────── */
.index-rail {
  position: absolute;
  top: 8px;
  right: 4px;
  bottom: 8px;
  width: 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  color: #444748;
  user-select: none;
}

.index-rail span {
  transition: color 0.15s ease;
}

.index-rail .rail-top {
  cursor: pointer;
}

.index-rail .rail-top:hover {
  color: #ffffff;
}

.index-rail span.active {
  color: #ffffff;
  font-weight: 700;
  cursor: pointer;
}

/* ── Empty ──────────────────────────────────────────────── */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 48px 16px;
  text-align: center;
}

.empty-icon {
  width: 48px;
  height: 48px;
  border-radius: 9999px;
  background: #1b1b1e;
  color: #8e9192;
  display: flex;
  align-items: center;
  justify-content: center;
}

.empty-text {
  margin: 0;
  font-size: 13px;
  color: #8e9192;
}

@media (max-height: 640px) {
  .index-rail {
    display: none;
  }

  .directory-list {
    padding-right: 8px;
  }
}
</style>
