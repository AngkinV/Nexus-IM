<template>
  <!-- Tavern model settings: the model channel for this character, plus the
       sampling knobs. Anchored under the header capsule, not a centred dialog —
       the conversation stays visible behind it.
       Everything shown is real: the model list comes from the account's chat
       providers, and the status line comes from /companion/model-status. -->
  <div v-if="open" class="ms-overlay" @click.self="close">
    <div class="ms-panel" role="dialog" :aria-label="$t('companion.modelSettings.title')">
      <header class="ms-head">
        <div class="ms-head-left">
          <span class="material-symbols-outlined">neurology</span>
          <h3>{{ $t('companion.modelSettings.title') }}</h3>
          <span class="ms-version">v2.7</span>
        </div>
        <button class="ms-icon-btn" type="button" :title="$t('common.close')" @click="close">
          <span class="material-symbols-outlined">close</span>
        </button>
      </header>

      <div class="ms-body">
        <!-- ── Model list ─────────────────────────────────────────────── -->
        <section class="ms-section">
          <div class="ms-section-head">
            <span class="ms-label">
              <span class="material-symbols-outlined">smart_toy</span>
              <span>{{ $t('companion.modelSettings.baseModel') }}</span>
            </span>
            <span class="ms-hint">{{ $t('companion.modelSettings.hotReload') }}</span>
          </div>

          <div class="ms-cards">
            <button
              v-for="option in modelOptions"
              :key="option.id"
              class="ms-card"
              :class="{ 'is-active': option.id === selectedProviderId }"
              type="button"
              @click="selectedProviderId = option.id"
            >
              <div class="ms-card-main">
                <div class="ms-card-icon" :class="{ 'is-active': option.id === selectedProviderId }">
                  <span class="material-symbols-outlined">{{ option.icon }}</span>
                </div>
                <div class="ms-card-text">
                  <div class="ms-card-title">
                    <span class="ms-card-name">{{ option.name }}</span>
                    <span v-if="option.tag" class="ms-tag">{{ option.tag }}</span>
                  </div>
                  <p class="ms-card-desc">{{ option.desc }}</p>
                  <div class="ms-card-meta">
                    <span v-if="option.model">{{ option.model }}</span>
                    <span v-if="option.model && option.kind">·</span>
                    <span v-if="option.kind">{{ option.kind }}</span>
                  </div>
                </div>
              </div>
              <div class="ms-card-state">
                <span class="ms-dot" :class="{ 'is-on': option.id === selectedProviderId }"></span>
              </div>
            </button>

            <p v-if="!modelOptions.length" class="ms-empty">
              {{ $t('companion.modelSettings.noProvider') }}
            </p>
          </div>
        </section>

        <!-- ── Endpoint config ────────────────────────────────────────── -->
        <section class="ms-section">
          <div class="ms-section-head">
            <span class="ms-label">
              <span class="material-symbols-outlined">settings_ethernet</span>
              <span>{{ $t('companion.modelSettings.endpointConfig') }}</span>
            </span>
            <button class="ms-link" type="button" @click="endpointOpen = !endpointOpen">
              <span>{{ endpointOpen ? $t('companion.modelSettings.collapse') : $t('companion.modelSettings.expand') }}</span>
              <span class="material-symbols-outlined">{{ endpointOpen ? 'expand_less' : 'expand_more' }}</span>
            </button>
          </div>

          <div v-if="endpointOpen" class="ms-endpoint">
            <!-- Base URL + ping -->
            <div class="ms-field">
              <div class="ms-field-head">
                <label>
                  <span class="material-symbols-outlined">link</span>
                  <span>{{ $t('companion.modelSettings.baseUrl') }}</span>
                </label>
                <span class="ms-mono ms-muted">{{ pingLabel }}</span>
              </div>
              <div class="ms-row">
                <input v-model="endpoint" class="ms-input" type="text" placeholder="https://api.example.com/v1" />
                <button class="ms-btn" type="button" :disabled="testing" @click="testConnection">
                  <span class="material-symbols-outlined" :class="{ 'ms-spin': testing }">
                    {{ testing ? 'progress_activity' : 'network_ping' }}
                  </span>
                  <span>{{ testing ? $t('companion.modelSettings.testing') : $t('companion.modelSettings.test') }}</span>
                </button>
              </div>
            </div>

            <!-- API key -->
            <div class="ms-field">
              <div class="ms-field-head">
                <label>
                  <span class="material-symbols-outlined">key</span>
                  <span>{{ $t('companion.modelSettings.apiKey') }}</span>
                </label>
                <span class="ms-mono ms-muted">
                  {{ hasStoredKey ? $t('companion.modelSettings.keyStored') : $t('companion.modelSettings.keyOptional') }}
                </span>
              </div>
              <div class="ms-row ms-row--tight">
                <input
                  v-model="apiKey"
                  class="ms-input"
                  :type="keyVisible ? 'text' : 'password'"
                  :placeholder="$t('companion.modelSettings.apiKeyPlaceholder')"
                  autocomplete="off"
                />
                <button class="ms-icon-btn" type="button" @click="keyVisible = !keyVisible">
                  <span class="material-symbols-outlined">{{ keyVisible ? 'visibility_off' : 'visibility' }}</span>
                </button>
              </div>
            </div>

            <!-- Model identifier -->
            <div class="ms-field">
              <div class="ms-field-head">
                <label>
                  <span class="material-symbols-outlined">developer_board</span>
                  <span>{{ $t('companion.modelSettings.modelId') }}</span>
                </label>
                <span class="ms-mono ms-muted">{{ $t('companion.modelSettings.modelIdHint') }}</span>
              </div>
              <div class="ms-row">
                <input v-model="modelName" class="ms-input" type="text" placeholder="gpt-4o-mini" />
                <select v-model="modelName" class="ms-select">
                  <option value="" disabled>{{ $t('companion.modelSettings.presets') }}</option>
                  <option v-for="preset in presets" :key="preset.value" :value="preset.value">{{ preset.label }}</option>
                </select>
              </div>
            </div>
          </div>
        </section>

        <!-- ── Inference dynamics ─────────────────────────────────────── -->
        <section class="ms-section ms-section--divided">
          <div class="ms-section-head">
            <span class="ms-label">
              <span class="material-symbols-outlined">tune</span>
              <span>{{ $t('companion.modelSettings.dynamics') }}</span>
            </span>
            <span class="ms-mono ms-accent">{{ $t('companion.modelSettings.fineTune') }}</span>
          </div>

          <div class="ms-slider">
            <div class="ms-slider-head">
              <span class="ms-slider-label">
                <span class="material-symbols-outlined">thermostat</span>
                <span>{{ $t('companion.modelSettings.temperature') }}</span>
              </span>
              <span class="ms-mono ms-accent">{{ temperature.toFixed(2) }}</span>
            </div>
            <input v-model.number="temperature" type="range" min="0.1" max="1.5" step="0.05" />
            <div class="ms-slider-scale">
              <span>{{ $t('companion.modelSettings.tempLow') }}</span>
              <span>{{ $t('companion.modelSettings.tempMid') }}</span>
              <span>{{ $t('companion.modelSettings.tempHigh') }}</span>
            </div>
          </div>

          <div class="ms-slider">
            <div class="ms-slider-head">
              <span class="ms-slider-label">
                <span class="material-symbols-outlined">shield_lock</span>
                <span>{{ $t('companion.modelSettings.personaLock') }}</span>
              </span>
              <span class="ms-mono ms-accent">{{ personaLock }}%</span>
            </div>
            <input v-model.number="personaLock" type="range" min="50" max="100" step="1" />
            <div class="ms-slider-scale">
              <span>{{ $t('companion.modelSettings.lockLow') }}</span>
              <span>{{ $t('companion.modelSettings.lockMid') }}</span>
              <span>{{ $t('companion.modelSettings.lockHigh') }}</span>
            </div>
          </div>

          <div class="ms-field ms-field--inline">
            <div>
              <span class="ms-slider-label">
                <span class="material-symbols-outlined">history</span>
                <span>{{ $t('companion.modelSettings.contextHorizon') }}</span>
              </span>
              <p class="ms-field-note">{{ $t('companion.modelSettings.contextNote') }}</p>
            </div>
            <select v-model.number="contextHorizon" class="ms-select">
              <option :value="128000">{{ $t('companion.modelSettings.horizon128') }}</option>
              <option :value="64000">{{ $t('companion.modelSettings.horizon64') }}</option>
              <option :value="32000">{{ $t('companion.modelSettings.horizon32') }}</option>
            </select>
          </div>
        </section>

        <!-- Status: what the channel actually is right now. -->
        <p v-if="statusNote" class="ms-status" :class="{ 'is-bad': statusBad }">{{ statusNote }}</p>
      </div>

      <footer class="ms-foot">
        <button class="ms-btn ms-btn--ghost" type="button" @click="resetDefaults">
          <span class="material-symbols-outlined">restart_alt</span>
          <span>{{ $t('companion.modelSettings.reset') }}</span>
        </button>
        <div class="ms-foot-right">
          <button class="ms-btn" type="button" @click="close">{{ $t('common.cancel') }}</button>
          <button class="ms-btn ms-btn--primary" type="button" :disabled="saving" @click="save">
            <span class="material-symbols-outlined">check</span>
            <span>{{ saving ? $t('common.saving') : $t('companion.modelSettings.saveApply') }}</span>
          </button>
        </div>
      </footer>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useCompanionStore } from '@/stores/companion'
import { useAgentProvidersStore } from '@/stores/agentProviders'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  open: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open', 'saved'])

const { t } = useI18n()
const companionStore = useCompanionStore()
const providersStore = useAgentProvidersStore()
const userStore = useUserStore()

const selectedProviderId = ref(null)
const endpoint = ref('')
const apiKey = ref('')
const keyVisible = ref(false)
const modelName = ref('')
const endpointOpen = ref(false)
const temperature = ref(0.85)
const personaLock = ref(92)
const contextHorizon = ref(128000)
const saving = ref(false)
const testing = ref(false)
const pingState = ref('idle') // idle | ok | fail
const pingDetail = ref('')
const status = ref(null)

const presets = computed(() => [
  { value: 'gpt-4o-mini', label: 'GPT-4o mini' },
  { value: 'claude-3-5-sonnet', label: 'Claude 3.5 Sonnet' },
  { value: 'deepseek-chat', label: 'DeepSeek Chat' },
  { value: 'qwen2.5:72b-instruct', label: 'Qwen 2.5 72B' }
])

// The account's chat providers, read live. No invented models: if the user has
// configured none, the list is empty and says so.
const modelOptions = computed(() =>
  (providersStore.providers || [])
    .filter((p) => !p.purpose || p.purpose === 'chat')
    .map((p) => ({
      id: p.id,
      name: p.displayName || p.provider,
      icon: 'smart_toy',
      tag: p.isDefault ? t('companion.modelSettings.defaultTag') : '',
      desc: p.hasApiKey ? t('companion.modelSettings.providerReady') : t('companion.modelSettings.providerNoKey'),
      model: p.defaultModel || '',
      kind: p.baseUrl || ''
    }))
)

const hasStoredKey = computed(() => !!status.value?.hasApiKey)

const pingLabel = computed(() => {
  if (testing.value) return t('companion.modelSettings.testing')
  if (pingState.value === 'ok') return t('companion.modelSettings.pingOk')
  if (pingState.value === 'fail') return t('companion.modelSettings.pingFail')
  return t('companion.modelSettings.pingIdle')
})

const statusNote = computed(() => {
  const s = status.value
  if (!s) return ''
  if (s.usable) {
    return s.borrowed
      ? t('companion.modelSettings.usingDefault', { model: s.modelName || '—' })
      : t('companion.modelSettings.usingBound', { model: s.modelName || '—' })
  }
  const map = {
    no_binding: t('companion.modelSettings.missingBinding'),
    no_endpoint: t('companion.modelSettings.missingEndpoint'),
    no_credential: t('companion.modelSettings.missingCredential'),
    no_api_key: t('companion.modelSettings.missingKey')
  }
  return map[s.missing] || t('companion.modelSettings.missingUnknown')
})

const statusBad = computed(() => !!status.value && !status.value.usable)

function close() {
  emit('update:open', false)
}

async function loadStatus() {
  const roleId = companionStore.activeRoleId
  const userId = userStore.currentUser?.id
  if (!roleId || !userId) return
  status.value = await companionStore.fetchModelChannel(userId, roleId)
}

onMounted(async () => {
  await providersStore.fetchAll()
  const def = providersStore.providers.find((p) => p.isDefault && (!p.purpose || p.purpose === 'chat'))
    || providersStore.providers.find((p) => !p.purpose || p.purpose === 'chat')
  if (def) {
    selectedProviderId.value = def.id
    endpoint.value = def.baseUrl || ''
    modelName.value = def.defaultModel || ''
  }
  await loadStatus()
})

// Every time the panel opens, re-read the real channel — it may have changed
// in the AI assistant's own settings.
watch(() => props.open, async (isOpen) => {
  if (!isOpen) return
  pingState.value = 'idle'
  pingDetail.value = ''
  await providersStore.fetchAll()
  await loadStatus()
})

watch(selectedProviderId, (id) => {
  const p = providersStore.providers.find((x) => x.id === id)
  if (!p) return
  if (p.baseUrl) endpoint.value = p.baseUrl
  if (p.defaultModel) modelName.value = p.defaultModel
})

async function testConnection() {
  const roleId = companionStore.activeRoleId
  const userId = userStore.currentUser?.id
  if (!roleId || !userId) return
  testing.value = true
  pingState.value = 'idle'
  try {
    // Save first: the probe runs against stored state, so an unsaved URL would
    // test the old one and lie.
    await persistBinding()
    const result = await companionStore.testModelChannel(userId, roleId)
    if (result?.ok) {
      pingState.value = 'ok'
      pingDetail.value = result.url || ''
    } else {
      pingState.value = 'fail'
      pingDetail.value = result?.error || result?.missing || ''
    }
    await loadStatus()
  } finally {
    testing.value = false
  }
}

async function persistBinding() {
  const roleId = companionStore.activeRoleId
  const userId = userStore.currentUser?.id
  if (!roleId || !userId) return
  const provider = providersStore.providers.find((p) => p.id === selectedProviderId.value)
  await companionStore.bindModel(userId, roleId, {
    provider: provider?.provider || 'openai-compatible',
    modelName: modelName.value.trim(),
    endpoint: endpoint.value.trim(),
    temperature: temperature.value,
    personaLock: personaLock.value,
    contextHorizon: contextHorizon.value
  })
}

async function save() {
  const roleId = companionStore.activeRoleId
  const userId = userStore.currentUser?.id
  if (!roleId || !userId) return
  saving.value = true
  try {
    // A key typed here is stored against the selected provider, so the tavern
    // and the AI assistant share one credential.
    if (apiKey.value.trim() && selectedProviderId.value) {
      const provider = providersStore.providers.find((p) => p.id === selectedProviderId.value)
      if (provider) {
        // Same credential the AI assistant uses: upsert on (provider, purpose).
        // A blank key here would clear it, so only the key is passed through.
        await providersStore.upsert({
          provider: provider.provider,
          purpose: provider.purpose || 'chat',
          displayName: provider.displayName,
          baseUrl: provider.baseUrl,
          defaultModel: provider.defaultModel,
          apiKey: apiKey.value.trim(),
          makeDefault: !!provider.isDefault
        })
      }
      apiKey.value = ''
      await providersStore.fetchAll()
    }
    await persistBinding()
    await loadStatus()
    ElMessage.success(t('common.savedSuccess'))
    emit('saved')
    close()
  } catch (err) {
    ElMessage.error(err?.message || t('common.operationFailed'))
  } finally {
    saving.value = false
  }
}

function resetDefaults() {
  temperature.value = 0.85
  personaLock.value = 92
  contextHorizon.value = 128000
}
</script>

<style scoped>
.ms-overlay {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: rgba(6, 6, 8, 0.35);
  display: flex;
  justify-content: flex-end;
  align-items: flex-start;
  padding: 64px 24px 24px;
}

.ms-panel {
  width: 100%;
  max-width: 448px;
  max-height: calc(100vh - 100px);
  display: flex;
  flex-direction: column;
  border-radius: 12px;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.45);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.6);
  overflow: hidden;
}

.ms-head {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid rgba(68, 71, 72, 0.3);
}

.ms-head-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.ms-head-left .material-symbols-outlined {
  font-size: 18px;
  color: #ffffff;
}

.ms-head-left h3 {
  margin: 0;
  color: #ffffff;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.ms-version {
  padding: 1px 6px;
  border-radius: 3px;
  border: 1px solid rgba(68, 71, 72, 0.4);
  background: #1f1f22;
  color: #8e9192;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
}

.ms-icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #8e9192;
  cursor: pointer;
  flex-shrink: 0;
}

.ms-icon-btn:hover {
  background: #2a2a2d;
  color: #ffffff;
}

.ms-icon-btn .material-symbols-outlined {
  font-size: 16px;
}

.ms-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ms-body::-webkit-scrollbar {
  width: 6px;
}

.ms-body::-webkit-scrollbar-thumb {
  background: #353438;
  border-radius: 9999px;
}

.ms-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ms-section--divided {
  padding-top: 14px;
  border-top: 1px solid rgba(68, 71, 72, 0.25);
}

.ms-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 2px;
}

.ms-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: rgba(196, 199, 200, 0.7);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.ms-label .material-symbols-outlined {
  font-size: 13px;
  color: #ffffff;
}

.ms-hint,
.ms-muted {
  color: rgba(142, 145, 146, 0.6);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
}

.ms-mono {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
}

.ms-accent {
  color: #ffffff;
}

.ms-cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ms-card {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 12px;
  border-radius: 8px;
  border: 1px solid rgba(68, 71, 72, 0.3);
  background: #131316;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
  transition: border-color 0.15s, background 0.15s;
}

.ms-card:hover {
  border-color: rgba(68, 71, 72, 0.6);
  background: #1b1b1e;
}

.ms-card.is-active {
  border-color: #ffffff;
  background: #1f1f22;
}

.ms-card-main {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-width: 0;
}

.ms-card-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 4px;
  background: #2a2a2d;
  color: #8e9192;
  flex-shrink: 0;
}

.ms-card-icon.is-active {
  background: #ffffff;
  color: #131316;
}

.ms-card-icon .material-symbols-outlined {
  font-size: 15px;
}

.ms-card-text {
  min-width: 0;
}

.ms-card-title {
  display: flex;
  align-items: center;
  gap: 6px;
}

.ms-card-name {
  color: #e4e1e6;
  font-size: 12.5px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ms-card.is-active .ms-card-name {
  color: #ffffff;
}

.ms-tag {
  padding: 0 5px;
  border-radius: 3px;
  border: 1px solid rgba(68, 71, 72, 0.4);
  background: #2a2a2d;
  color: #8e9192;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 9px;
  white-space: nowrap;
}

.ms-card-desc {
  margin: 3px 0 0;
  color: rgba(196, 199, 200, 0.7);
  font-size: 11.5px;
  line-height: 1.5;
}

.ms-card-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 5px;
  color: rgba(142, 145, 146, 0.75);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ms-card-state {
  flex-shrink: 0;
  padding-top: 4px;
}

.ms-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 9999px;
  background: rgba(68, 71, 72, 0.6);
}

.ms-dot.is-on {
  background: #ffffff;
  box-shadow: 0 0 8px rgba(255, 255, 255, 0.7);
}

.ms-empty {
  margin: 0;
  padding: 14px 0;
  color: #6f7274;
  font-size: 12px;
  text-align: center;
}

.ms-link {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border: none;
  background: transparent;
  color: #ffffff;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  cursor: pointer;
}

.ms-link .material-symbols-outlined {
  font-size: 12px;
}

.ms-endpoint {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ms-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ms-field--inline {
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px;
  border-radius: 8px;
  background: rgba(27, 27, 30, 0.5);
  border: 1px solid rgba(68, 71, 72, 0.2);
}

.ms-field-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 2px;
}

.ms-field-head label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #e4e1e6;
  font-size: 11px;
}

.ms-field-head label .material-symbols-outlined {
  font-size: 13px;
  color: #ffffff;
}

.ms-field-note {
  margin: 3px 0 0;
  color: rgba(142, 145, 146, 0.7);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  line-height: 1.5;
}

.ms-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.ms-row--tight {
  gap: 2px;
}

.ms-input {
  flex: 1;
  min-width: 0;
  padding: 6px 10px;
  border-radius: 4px;
  border: 1px solid rgba(68, 71, 72, 0.3);
  background: #1b1b1e;
  color: #ffffff;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11px;
  outline: none;
  transition: border-color 0.15s;
}

.ms-input:focus {
  border-color: #ffffff;
}

.ms-input::placeholder {
  color: rgba(142, 145, 146, 0.5);
}

.ms-select {
  padding: 6px 8px;
  border-radius: 4px;
  border: 1px solid rgba(68, 71, 72, 0.3);
  background: #1f1f22;
  color: #e4e1e6;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  outline: none;
  cursor: pointer;
}

.ms-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 10px;
  border-radius: 4px;
  border: 1px solid rgba(68, 71, 72, 0.4);
  background: #2a2a2d;
  color: #ffffff;
  font-family: inherit;
  font-size: 11px;
  cursor: pointer;
  white-space: nowrap;
  transition: border-color 0.15s, background 0.15s;
}

.ms-btn:hover:not(:disabled) {
  border-color: #ffffff;
}

.ms-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ms-btn .material-symbols-outlined {
  font-size: 13px;
}

.ms-btn--ghost {
  background: transparent;
  border-color: transparent;
  color: #8e9192;
}

.ms-btn--ghost:hover {
  background: #1f1f22;
  color: #ffffff;
}

.ms-btn--primary {
  background: #ffffff;
  border-color: #ffffff;
  color: #131316;
  font-weight: 500;
}

.ms-btn--primary:hover:not(:disabled) {
  background: #d0d0d5;
}

.ms-spin {
  animation: ms-spin 0.9s linear infinite;
}

@keyframes ms-spin {
  to { transform: rotate(360deg); }
}

.ms-slider {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 10px;
  border-radius: 8px;
  background: rgba(27, 27, 30, 0.5);
  border: 1px solid rgba(68, 71, 72, 0.2);
}

.ms-slider-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.ms-slider-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #e4e1e6;
  font-size: 12px;
}

.ms-slider-label .material-symbols-outlined {
  font-size: 14px;
  color: #ffffff;
}

.ms-slider input[type='range'] {
  width: 100%;
  height: 3px;
  accent-color: #ffffff;
  cursor: pointer;
}

.ms-slider-scale {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: rgba(142, 145, 146, 0.6);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 9.5px;
}

.ms-status {
  margin: 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: rgba(27, 27, 30, 0.7);
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #c4c7c8;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10.5px;
  line-height: 1.5;
}

.ms-status.is-bad {
  border-color: rgba(255, 180, 171, 0.35);
  color: #ffb4ab;
}

.ms-foot {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid rgba(68, 71, 72, 0.3);
  background: rgba(27, 27, 30, 0.8);
}

.ms-foot-right {
  display: flex;
  align-items: center;
  gap: 6px;
}

@media (max-width: 640px) {
  .ms-overlay {
    padding: 56px 12px 12px;
  }

  .ms-panel {
    max-width: none;
    max-height: calc(100vh - 76px);
  }
}
</style>
