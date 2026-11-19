<template>
  <!-- Prism role dossier: who the companion is, how far the bond has come, and
       what the role remembers. Lives beside the role conversation, replacing
       the floating panel's memory + growth tabs. -->
  <aside class="dossier">
    <div class="dossier-head">
      <div class="dossier-avatar">
        <img v-if="activeRole?.avatarUrl" :src="activeRole.avatarUrl" alt="" />
        <span v-else class="material-symbols-outlined">person</span>
      </div>
      <div class="dossier-id">
        <div class="dossier-name">{{ activeRole?.name || $t('companion.defaultName') }}</div>
        <div class="dossier-sub">{{ activeRole?.presence || activeRole?.tone || $t('companion.defaultStatus') }}</div>
      </div>
      <button class="dossier-close" type="button" :title="$t('common.close')" @click="emit('close')">
        <span class="material-symbols-outlined">close</span>
      </button>
    </div>

    <div class="dossier-scroll">
      <section class="dossier-section">
        <div class="dossier-section-head">{{ $t('companion.coGrowth') }}</div>
        <div v-if="growth" class="growth-list">
          <div v-for="stat in growthStats" :key="stat.key" class="growth-row">
            <div class="growth-label">
              <span>{{ stat.label }}</span>
              <span class="growth-value">{{ stat.value }}%</span>
            </div>
            <div class="growth-track">
              <div class="growth-fill" :style="{ width: `${stat.value}%` }"></div>
            </div>
          </div>
        </div>
        <p v-else class="dossier-empty">{{ $t('companion.emptyGrowth') }}</p>
      </section>

      <!-- The character card: what she is told about herself. Both fields go
           into the prompt as written; empty means nothing is invented for her. -->
      <section v-if="activeRole" class="dossier-section">
        <div class="dossier-section-head">{{ $t('companion.card.title') }}</div>
        <label class="card-label" for="dossier-card-background">{{ $t('companion.card.background') }}</label>
        <textarea
          id="dossier-card-background"
          v-model="cardBackground"
          class="dossier-textarea"
          rows="3"
          maxlength="600"
          :placeholder="$t('companion.card.backgroundPlaceholder')"
        ></textarea>
        <label class="card-label" for="dossier-card-greeting">{{ $t('companion.card.greeting') }}</label>
        <textarea
          id="dossier-card-greeting"
          v-model="cardGreeting"
          class="dossier-textarea"
          rows="2"
          maxlength="300"
          :placeholder="$t('companion.card.greetingPlaceholder')"
        ></textarea>
        <div class="card-foot">
          <button class="dossier-btn" type="button" :disabled="!cardDirty || cardSaving" @click="saveCard">
            {{ $t('companion.card.save') }}
          </button>
        </div>
      </section>

      <!-- Past evenings. Read-only: the archive is for remembering, not editing. -->
      <section v-if="sessions.length" class="dossier-section">
        <div class="dossier-section-head">
          <span>{{ $t('companion.session.history') }}</span>
          <span class="dossier-count">{{ sessions.length }}</span>
        </div>
        <div class="session-list">
          <div v-for="item in sessions" :key="item.id" class="session-row">
            <div class="session-row-head">
              <span class="session-scene">{{ $t(`companion.sceneNames.${item.sceneKey}`) }}</span>
              <span class="session-when">{{ dayjs(item.closedAt).format('MM-DD HH:mm') }}</span>
            </div>
            <p v-if="item.closingLine" class="session-line">{{ item.closingLine }}</p>
            <p v-if="item.summary" class="session-summary">{{ item.summary }}</p>
          </div>
        </div>
      </section>

      <section class="dossier-section">
        <div class="dossier-section-head">
          <span>{{ $t('companion.memorySection') }}</span>
          <span class="dossier-count">{{ memories.length }}</span>
        </div>

        <div class="memory-new">
          <select v-model="newMemoryType" class="dossier-select">
            <option value="short_term">{{ $t('companion.shortTerm') }}</option>
            <option value="mid">{{ $t('companion.midTerm') }}</option>
            <option value="long_term">{{ $t('companion.longTerm') }}</option>
          </select>
          <input
            v-model="newMemoryContent"
            class="dossier-input"
            type="text"
            :placeholder="$t('companion.memoryPlaceholder')"
            @keyup.enter="addMemory"
          />
          <button class="dossier-btn" type="button" @click="addMemory">{{ $t('companion.saveMemory') }}</button>
        </div>

        <p v-if="!memories.length" class="dossier-empty">{{ $t('companion.emptyMemory') }}</p>
        <div v-else class="memory-list">
          <div v-for="memory in memories" :key="memory.id" class="memory-card">
            <div class="memory-content">{{ memory.content }}</div>
            <div class="memory-foot">
              <span class="memory-type">{{ typeLabel(memory.type) }}</span>
              <span v-if="memory.confirmed" class="memory-chip">{{ $t('companion.confirmed') }}</span>
              <div class="memory-actions">
                <button
                  v-if="!memory.confirmed"
                  class="memory-act"
                  type="button"
                  :title="$t('companion.confirm')"
                  @click="confirmMemory(memory)"
                >
                  <span class="material-symbols-outlined">check</span>
                </button>
                <button
                  class="memory-act"
                  type="button"
                  :title="$t('companion.delete')"
                  @click="removeMemory(memory)"
                >
                  <span class="material-symbols-outlined">delete</span>
                </button>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  </aside>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useCompanionStore } from '@/stores/companion'
import { useUserStore } from '@/stores/user'
import dayjs from 'dayjs'

const emit = defineEmits(['close'])

const { t } = useI18n()
const userStore = useUserStore()
const companionStore = useCompanionStore()
const { activeRole, activeRoleId, memories, growth, sessionHistory: sessions } = storeToRefs(companionStore)

// The archive is fetched when the panel opens, not on app load — it is
// reference material, and most visits never look at it.
onMounted(async () => {
  const userId = userStore.currentUser?.id
  if (userId && activeRoleId.value) {
    await companionStore.fetchSessionHistory(userId, activeRoleId.value)
  }
})

watch(activeRoleId, async (roleId) => {
  const userId = userStore.currentUser?.id
  if (userId && roleId) await companionStore.fetchSessionHistory(userId, roleId)
})

// ── Character card ──────────────────────────────────────────────────────────
const cardBackground = ref('')
const cardGreeting = ref('')
const cardSaving = ref(false)
const cardDirty = computed(() =>
  cardBackground.value.trim() !== (activeRole.value?.background || '') ||
  cardGreeting.value.trim() !== (activeRole.value?.greeting || '')
)

// Follow the character on screen; a saved card comes back through the store.
watch(
  () => [activeRole.value?.id, activeRole.value?.background, activeRole.value?.greeting],
  () => {
    cardBackground.value = activeRole.value?.background || ''
    cardGreeting.value = activeRole.value?.greeting || ''
  },
  { immediate: true }
)

async function saveCard() {
  const roleId = activeRoleId.value
  if (!roleId || cardSaving.value) return
  cardSaving.value = true
  try {
    // Empty strings on purpose: the server reads "" as "clear this field".
    await companionStore.updateRole(userStore.currentUser?.id, roleId, {
      background: cardBackground.value.trim(),
      greeting: cardGreeting.value.trim()
    })
    ElMessage.success(t('companion.card.saved'))
  } catch {
    ElMessage.error(t('common.operationFailed'))
  } finally {
    cardSaving.value = false
  }
}

const newMemoryType = ref('short_term')
const newMemoryContent = ref('')

const growthStats = computed(() => [
  { key: 'intimacy', label: t('companion.intimacy'), value: growth.value?.intimacy ?? 0 },
  { key: 'trust', label: t('companion.trust'), value: growth.value?.trust ?? 0 },
  { key: 'stability', label: t('companion.stability'), value: growth.value?.stability ?? 0 },
  { key: 'coGrowth', label: t('companion.coGrowth'), value: growth.value?.coGrowth ?? 0 }
])

const TYPE_LABEL = {
  short_term: 'companion.shortTerm',
  mid: 'companion.midTerm',
  long_term: 'companion.longTerm'
}
const typeLabel = (type) => (TYPE_LABEL[type] ? t(TYPE_LABEL[type]) : type)

const userId = () => userStore.currentUser?.id

async function reload() {
  const roleId = activeRoleId.value
  if (!userId() || !roleId) return
  await Promise.all([
    companionStore.fetchMemories(userId(), roleId),
    companionStore.fetchGrowth(userId(), roleId)
  ])
}

watch(activeRoleId, reload, { immediate: true })

async function addMemory() {
  const content = newMemoryContent.value.trim()
  if (!content || !activeRoleId.value) return
  await companionStore.createMemory(userId(), activeRoleId.value, newMemoryType.value, content)
  newMemoryContent.value = ''
  ElMessage.success(t('common.savedSuccess'))
}

async function confirmMemory(memory) {
  await companionStore.confirmMemory(userId(), memory.id)
}

async function removeMemory(memory) {
  await companionStore.deleteMemory(userId(), memory.id)
  ElMessage.success(t('common.delete') + ' ' + t('common.savedSuccess'))
}
</script>

<style scoped>
.dossier {
  width: 300px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: #0e0e11;
  border-left: 1px solid rgba(68, 71, 72, 0.3);
}

.dossier-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 14px 12px;
  border-bottom: 1px solid rgba(68, 71, 72, 0.2);
  flex-shrink: 0;
}

.dossier-avatar {
  width: 36px;
  height: 36px;
  border-radius: 9999px;
  overflow: hidden;
  flex-shrink: 0;
  background: #2a2a2d;
  border: 1px solid rgba(68, 71, 72, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c4c7c8;
}

.dossier-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  filter: grayscale(1);
}

.dossier-id {
  flex: 1;
  min-width: 0;
}

.dossier-name {
  color: #ffffff;
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dossier-sub {
  margin-top: 2px;
  color: #8e9192;
  font-size: 11.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dossier-close {
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #8e9192;
  cursor: pointer;
  flex-shrink: 0;
}

.dossier-close:hover {
  background: #1f1f22;
  color: #ffffff;
}

.dossier-close .material-symbols-outlined {
  font-size: 17px;
}

.dossier-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.dossier-scroll::-webkit-scrollbar {
  width: 6px;
}

.dossier-scroll::-webkit-scrollbar-thumb {
  background: #353438;
  border-radius: 9999px;
}

/* Past evenings: a compact ledger. The closing line is what she said, the
   summary is what she kept — both are hers, neither is generated on the fly. */
.session-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.session-row {
  padding: 8px 10px;
  border-radius: 8px;
  background: #131316;
  border: 1px solid rgba(68, 71, 72, 0.3);
}

.session-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.session-scene {
  color: #e4e1e6;
  font-size: 12px;
  font-weight: 500;
}

.session-when {
  color: rgba(142, 145, 146, 0.7);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  flex-shrink: 0;
}

.session-line {
  margin: 4px 0 0;
  color: rgba(196, 199, 200, 0.85);
  font-size: 11.5px;
  line-height: 1.55;
}

.session-summary {
  margin: 4px 0 0;
  padding-left: 8px;
  border-left: 1px solid rgba(68, 71, 72, 0.5);
  color: rgba(142, 145, 146, 0.8);
  font-size: 11px;
  line-height: 1.5;
}

.dossier-section-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  color: #8e9192;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.dossier-count {
  margin-left: auto;
  padding: 0 6px;
  border-radius: 4px;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #c4c7c8;
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
  letter-spacing: 0;
}

.growth-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.growth-label {
  display: flex;
  justify-content: space-between;
  margin-bottom: 5px;
  color: #c4c7c8;
  font-size: 12px;
}

.growth-value {
  color: #ffffff;
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
  font-size: 11px;
}

.growth-track {
  height: 3px;
  border-radius: 9999px;
  background: #1f1f22;
  overflow: hidden;
}

.growth-fill {
  height: 100%;
  border-radius: 9999px;
  background: #ffffff;
  transition: width 0.3s ease;
}

.memory-new {
  display: flex;
  gap: 6px;
  margin-bottom: 10px;
}

.dossier-select,
.dossier-input {
  height: 28px;
  border-radius: 4px;
  background: #131316;
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #e4e1e6;
  font-family: inherit;
  font-size: 12px;
  padding: 0 8px;
  outline: none;
  min-width: 0;
}

.dossier-select {
  flex: 0 0 68px;
}

.dossier-input {
  flex: 1;
}

.dossier-input::placeholder {
  color: #6f7274;
}

.dossier-select:focus,
.dossier-input:focus {
  border-color: #8e9192;
}

.dossier-btn {
  flex-shrink: 0;
  height: 28px;
  padding: 0 10px;
  border-radius: 4px;
  border: 1px solid rgba(68, 71, 72, 0.4);
  background: #1b1b1e;
  color: #ffffff;
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
}

.dossier-btn:hover:not(:disabled) {
  background: #2a2a2d;
}

.dossier-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.card-label {
  display: block;
  margin: 8px 0 4px;
  color: #8e9192;
  font-size: 11px;
}

.dossier-textarea {
  display: block;
  box-sizing: border-box;
  width: 100%;
  padding: 6px 8px;
  border-radius: 4px;
  background: #131316;
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #e4e1e6;
  font-family: inherit;
  font-size: 12px;
  line-height: 1.55;
  resize: vertical;
  outline: none;
}

.dossier-textarea::placeholder {
  color: #6f7274;
}

.dossier-textarea:focus {
  border-color: #8e9192;
}

.card-foot {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

.memory-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.memory-card {
  padding: 10px;
  border-radius: 8px;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.3);
}

.memory-content {
  color: #e4e1e6;
  font-size: 12.5px;
  line-height: 1.55;
  word-break: break-word;
}

.memory-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}

.memory-type {
  color: #8e9192;
  font-size: 11px;
}

.memory-chip {
  padding: 1px 6px;
  border-radius: 4px;
  background: #2a2a2d;
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #c4c7c8;
  font-size: 10px;
}

.memory-actions {
  margin-left: auto;
  display: flex;
  gap: 2px;
}

.memory-act {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #8e9192;
  cursor: pointer;
}

.memory-act:hover {
  background: #2a2a2d;
  color: #ffffff;
}

.memory-act .material-symbols-outlined {
  font-size: 15px;
}

.dossier-empty {
  margin: 0;
  padding: 10px 0;
  color: #6f7274;
  font-size: 12px;
}
</style>
