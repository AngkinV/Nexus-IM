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
        <button class="ms-icon-btn" type="button" :aria-label="$t('common.close')" :title="$t('common.close')" @click="close">
          <span class="material-symbols-outlined">close</span>
        </button>
      </header>

      <div class="ms-body">
        <section class="ms-section">
          <div class="ms-section-head">
            <span class="ms-label">{{ $t('companion.modelSettings.channel') }}</span>
            <button class="ms-btn" type="button" @click="accountSettings = true">{{ $t('agent.providers.manage') }}</button>
          </div>
          <el-input
            v-if="providersStore.chatProviders.length > 5" v-model="channelQuery" size="small" clearable
            :placeholder="$t('agent.providers.switcher.search')" :aria-label="$t('agent.providers.switcher.search')" />
          <div class="ms-cards" role="group" :aria-label="$t('companion.modelSettings.channel')">
            <button v-for="p in shownChannels" :key="p.id" type="button" class="ms-card"
              :class="{ 'is-active': p.id === draft.credentialId }" :aria-pressed="p.id === draft.credentialId"
              :disabled="busy" @click="chooseChannel(p.id)">
              <span class="ms-card-title">
                <span class="ms-card-icon" :class="{ 'is-active': p.id === draft.credentialId }" aria-hidden="true"><span class="material-symbols-outlined">smart_toy</span></span>
                <span class="ms-card-text">
                  <span class="ms-card-name">{{ p.displayName || p.provider }}</span>
                  <span class="ms-card-sub">{{ presetLabel(vendorOf(p)) }}<template v-if="p.defaultModel"> · {{ p.defaultModel }}</template></span>
                </span>
                <span v-if="p.isDefault" class="ms-tag">{{ $t('agent.providers.default') }}</span>
              </span>
              <span class="ms-card-state" aria-hidden="true"><span class="ms-dot" :class="{ 'is-on': p.id === draft.credentialId }"></span></span>
            </button>
            <p v-if="!loading && !providersStore.chatProviders.length" class="ms-empty">{{ $t('companion.modelSettings.noProvider') }}</p>
            <p v-else-if="!shownChannels.length" class="ms-empty">{{ $t('agent.providers.switcher.noMatch') }}</p>
          </div>
        </section>

        <section class="ms-section">
          <span class="ms-label">{{ $t('companion.modelSettings.modelId') }}</span>
          <ProviderModelPicker v-model="draft.modelName" :provider-id="channel?.id" :label="$t('companion.modelSettings.modelId')"
            :disabled="busy || !channel" :unavailable-hint="$t('companion.modelSettings.chooseChannel')" />
          <p v-if="!draft.modelName && channel?.defaultModel" class="ms-field-note">{{ $t('companion.modelSettings.inheritModel', { model: channel.defaultModel }) }}</p>
          <label class="ms-slider-label"><input v-model="draft.enabled" type="checkbox" :disabled="busy" /> {{ $t('companion.modelSettings.enableBinding') }}</label>
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
              <span class="ms-mono ms-accent">{{ draft.temperature.toFixed(2) }}</span>
            </div>
            <input v-model.number="draft.temperature" :aria-label="$t('companion.modelSettings.temperature')" :disabled="busy" type="range" min="0.1" max="1.5" step="0.05" />
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
              <span class="ms-mono ms-accent">{{ draft.personaLock }}%</span>
            </div>
            <input v-model.number="draft.personaLock" :aria-label="$t('companion.modelSettings.personaLock')" :disabled="busy" type="range" min="50" max="100" step="1" />
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
            <select v-model.number="draft.contextHorizon" class="ms-select" :aria-label="$t('companion.modelSettings.contextHorizon')" :disabled="busy">
              <option v-if="![128000, 64000, 32000].includes(draft.contextHorizon)" :value="draft.contextHorizon">{{ draft.contextHorizon }}</option>
              <option :value="128000">{{ $t('companion.modelSettings.horizon128') }}</option>
              <option :value="64000">{{ $t('companion.modelSettings.horizon64') }}</option>
              <option :value="32000">{{ $t('companion.modelSettings.horizon32') }}</option>
            </select>
          </div>
        </section>

        <!-- Status: what the channel actually is right now. -->
        <p v-if="statusNote" role="status" class="ms-status" :class="{ 'is-bad': !status?.usable }">{{ statusNote }}</p>
        <div class="ms-row">
          <button class="ms-btn" type="button" :disabled="!canTest" :title="$t('agent.providers.testHint')" @click="testConnection">
            {{ $t(testing ? 'companion.modelSettings.testing' : 'agent.providers.test') }}
          </button>
          <span class="ms-field-note" role="status">{{ dirty ? $t('agent.providers.testHint') : probeNote }}</span>
        </div>
      </div>

      <footer class="ms-foot">
        <button class="ms-btn ms-btn--ghost" type="button" :disabled="busy" @click="Object.assign(draft, samplingDefaults)">
          <span class="material-symbols-outlined">restart_alt</span>
          <span>{{ $t('companion.modelSettings.reset') }}</span>
        </button>
        <div class="ms-foot-right">
          <button class="ms-btn" type="button" @click="close">{{ $t('common.cancel') }}</button>
          <button class="ms-btn ms-btn--primary" type="button" :disabled="!canSave" @click="save">
            <span class="material-symbols-outlined">check</span>
            <span>{{ saving ? $t('common.loading') : $t('companion.modelSettings.saveApply') }}</span>
          </button>
        </div>
      </footer>
    </div>
  </div>
  <AgentProviderSettings v-if="open" v-model:visible="accountSettings" />
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useCompanionStore } from '@/stores/companion'
import { useAgentProvidersStore, filterChannels, presetLabel, vendorOf } from '@/stores/agentProviders'
import { useUserStore } from '@/stores/user'
import AgentProviderSettings from '@/components/agent/AgentProviderSettings.vue'
import ProviderModelPicker from '@/components/agent/ProviderModelPicker.vue'

const props = defineProps({
  open: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open', 'saved'])

const { t } = useI18n()
const companionStore = useCompanionStore()
const providersStore = useAgentProvidersStore()
const userStore = useUserStore()

const accountSettings = ref(false)
const samplingDefaults = { temperature: 0.85, personaLock: 92, contextHorizon: 128000 }
function bindingFrom(s = {}) {
  return {
    credentialId: s.credentialId ?? null,
    modelName: Object.hasOwn(s, 'explicitModelName') ? (s.explicitModelName || '') : (s.borrowed ? '' : s.modelName ?? ''), enabled: s.enabled ?? true,
    temperature: s.temperature ?? samplingDefaults.temperature,
    personaLock: s.personaLock ?? samplingDefaults.personaLock,
    contextHorizon: s.contextHorizon ?? samplingDefaults.contextHorizon
  }
}
const draft = ref(bindingFrom())
const status = ref(null)
const original = ref(null)
const probeNote = ref('')
const loading = ref(false), saving = ref(false), testing = ref(false)
const busy = computed(() => loading.value || saving.value || testing.value)
const channel = computed(() => providersStore.chatProviders.find(p => p.id === draft.value.credentialId))
// The bound channel stays in view whatever the search says, so the selection is never hidden.
const channelQuery = ref('')
const shownChannels = computed(() => {
  const matches = filterChannels(providersStore.chatProviders, channelQuery.value)
  return channel.value && !matches.includes(channel.value) ? [channel.value, ...matches] : matches
})
const dirty = computed(() => !!original.value && Object.keys(original.value).some(k => draft.value[k] !== original.value[k]))
const effectiveModel = computed(() => draft.value.modelName.trim() || channel.value?.defaultModel || '')
const incompatible = computed(() => providersStore.getModelsForProvider(channel.value?.id)
  .find(m => m.id === effectiveModel.value)?.chat === false)
const canSave = computed(() => !!status.value && !busy.value && channel.value && effectiveModel.value && !incompatible.value)
const canTest = computed(() => canSave.value && !dirty.value && draft.value.enabled && status.value.usable)
let generation = 0

const statusNote = computed(() => {
  const s = status.value
  if (loading.value) return t('common.loading')
  if (!s) return t('companion.modelSettings.missingUnknown')
  if (s.enabled === false) return t('common.disabled')
  if (s.usable) {
    return s.borrowed
      ? t('companion.modelSettings.usingDefault', { model: s.modelName || '—' })
      : t('companion.modelSettings.usingBound', { model: s.modelName || '—' })
  }
  const map = {
    no_binding: t('companion.modelSettings.missingBinding'),
    no_model: t('companion.modelSettings.noModel'),
    no_endpoint: t('companion.modelSettings.missingEndpoint'),
    no_credential: t('companion.modelSettings.missingCredential'),
    no_api_key: t('companion.modelSettings.missingKey'),
    endpoint_mismatch: t('modelError.endpoint_mismatch'),
    no_provider: t('modelError.no_provider'),
    unreadable_api_key: t('modelError.unreadable_api_key')
  }
  return map[s.missing] || t('companion.modelSettings.missingUnknown')
})

function chooseChannel(id) {
  if (id === draft.value.credentialId) return
  draft.value.credentialId = id
  draft.value.modelName = ''
}

function close() { emit('update:open', false) }

// Never let a previous role, account, or opening of this panel populate a new draft.
watch([() => props.open, () => userStore.currentUser?.id, () => companionStore.activeRoleId],
  async ([open, userId, roleId], _, onCleanup) => {
    const version = ++generation
    onCleanup(() => { ++generation })
    accountSettings.value = false
    channelQuery.value = ''
    loading.value = saving.value = testing.value = false
    probeNote.value = ''
    status.value = original.value = null
    draft.value = bindingFrom()
    if (!open || !roleId || !userId) return
    loading.value = true
    const [description] = await Promise.all([companionStore.fetchModelChannel(userId, roleId), providersStore.fetchAll()])
    if (version !== generation) return
    status.value = description
    if (description) {
      draft.value = bindingFrom(description)
      original.value = { ...draft.value }
    }
    loading.value = false
  }, { immediate: true, flush: 'sync' })

async function testConnection() {
  if (!canTest.value) return
  const version = generation
  testing.value = true
  try {
    const result = await companionStore.testModelChannel(userStore.currentUser.id, companionStore.activeRoleId)
    if (version !== generation) return
    const error = result?.error || result?.modelError
    probeNote.value = result?.ok ? t('companion.modelSettings.pingOk') : [
      t('companion.modelSettings.pingFail'), error?.code, error?.summary || (typeof error === 'string' && error) || result?.missing,
      error?.retryAfterSeconds != null && t('companion.session.retryAfter', { seconds: error.retryAfterSeconds })
    ].filter(Boolean).join(' · ')
  } finally {
    if (version === generation) testing.value = false
  }
}

async function save() {
  if (!canSave.value) return
  const version = generation
  saving.value = true
  try {
    await companionStore.bindModel(userStore.currentUser.id, companionStore.activeRoleId, {
      ...draft.value, modelName: draft.value.modelName.trim()
    })
    if (version !== generation) return
    ElMessage.success(t('common.savedSuccess'))
    emit('saved')
    close()
  } catch (err) {
    if (version === generation) ElMessage.error(err?.response?.data?.message || err?.message || t('common.operationFailed'))
  } finally {
    if (version === generation) saving.value = false
  }
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
  max-height: 268px;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.ms-card {
  flex-shrink: 0;
}

.ms-card-title {
  min-width: 0;
}

.ms-card-text .ms-card-name {
  display: block;
}

.ms-card-sub {
  display: block;
  margin-top: 2px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: #8e9192;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10.5px;
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
