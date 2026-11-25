<template>
  <!-- One capsule in the composer toolbar; everything about the reading scope lives
       in the panel it opens. Closing the panel never confirms, saves or clears. -->
  <div ref="anchor" class="scope-anchor" @keydown="onKey">
    <button ref="trigger" type="button" class="scope-trigger" :class="state" aria-haspopup="dialog" :aria-expanded="open" aria-controls="agent-scope-panel" :title="`${t('agentScope.title')} · ${stateText}`" @click="open ? close() : show()">
      <span class="material-symbols-outlined" aria-hidden="true">mark_chat_read</span>
      <span class="scope-name wide">{{ t('agentScope.title') }}</span>
      <span class="scope-name narrow">{{ t('agentScope.shortTitle') }}</span>
      <span class="scope-state" aria-hidden="true">{{ summaryText }}</span>
      <span class="sr-only">{{ stateText }}</span>
      <span class="material-symbols-outlined scope-chevron" aria-hidden="true">expand_less</span>
    </button>
    <Teleport to="body" :disabled="!narrow">
    <div v-if="open" class="scope-backdrop" @click="close()"></div>
    <section v-if="open" id="agent-scope-panel" ref="panel" class="scope-panel" role="dialog" :aria-modal="narrow ? 'true' : undefined" aria-labelledby="agent-scope-title" aria-describedby="agent-scope-about" :style="place" @keydown="onKey">
      <header class="scope-head">
        <h3 id="agent-scope-title" ref="heading" tabindex="-1">{{ t('agentScope.title') }}</h3>
        <button type="button" class="scope-icon" :aria-label="t('common.close')" :title="t('common.close')" @click="close(true)"><span class="material-symbols-outlined" aria-hidden="true">close</span></button>
        <p id="agent-scope-about">{{ t('agentScope.about') }}</p>
      </header>

      <div class="scope-body">
        <section class="scope-part">
          <div class="scope-line">
            <button type="button" class="scope-btn" :disabled="frozen" @click="load"><span class="material-symbols-outlined" aria-hidden="true">refresh</span>{{ t(loaded ? 'agentScope.reload' : 'agentScope.load') }}</button>
            <p class="scope-note">{{ t('agentScope.reloadNote') }}</p>
          </div>
          <p v-if="loaded && workspace.bindingVersion == null && !reading" class="scope-note" role="status">{{ t('agentScope.bindingStale') }}</p>
          <p v-if="bindingError" class="scope-error" role="alert">{{ bindingError }}</p>
        </section>

        <section class="scope-part" aria-labelledby="agent-scope-targets">
          <div class="scope-line">
            <h4 id="agent-scope-targets">{{ t('agentScope.targets') }}</h4>
            <span class="scope-note">{{ t('agentScope.selectedCount', { n: count }) }}</span>
            <button type="button" class="scope-link" :disabled="frozen || !count" @click="choose([])">{{ t('agentScope.clear') }}</button>
          </div>
          <label class="nx-field-shell" :class="{ 'nx-field-lg': roomy }">
            <span class="material-symbols-outlined" aria-hidden="true">search</span>
            <input type="search" :value="query" :placeholder="t('agentScope.search')" :aria-label="t('agentScope.search')" @input="query = $event.target.value" />
          </label>
          <div class="scope-list">
            <template v-for="group in groups" :key="group.type">
              <h5>{{ t(`agentScope.${group.type}`) }}</h5>
              <ul>
                <li v-for="item in group.rows" :key="keyOf(item)" class="scope-item">
                  <label>
                    <input type="checkbox" :checked="chosen.has(keyOf(item))" :disabled="frozen" @change="toggle(item)" />
                    <span class="scope-item-name">{{ item.label || t(`agentScope.${item.type}`) }}</span>
                    <span class="scope-item-id">#{{ item.id }}</span>
                    <span v-if="item.missing" class="scope-item-id">{{ t('agentScope.missing') }}</span>
                  </label>
                  <button v-if="item.type === 'group' && !item.missing" type="button" class="scope-link" :title="`${t('agentScope.openGroup')} · ${item.label}`" :aria-label="`${t('agentScope.openGroup')} · ${item.label}`" @click="enterGroup(item)"><span class="material-symbols-outlined" aria-hidden="true">forum</span>{{ t('agentScope.openGroup') }}</button>
                </li>
              </ul>
            </template>
            <p v-if="!groups.length" class="scope-note" role="status">{{ t(!loaded ? 'agentScope.notLoaded' : query.trim() ? 'agentScope.noMatch' : 'agentScope.empty') }}</p>
          </div>
          <p v-if="groupError" class="scope-error" role="alert">{{ displayError(groupError) }}</p>
          <p class="scope-note">{{ t('agentScope.candidate') }}</p>
        </section>

        <section class="scope-part" aria-labelledby="agent-scope-time">
          <h4 id="agent-scope-time">{{ t('agentScope.time') }}</h4>
          <div class="scope-presets" role="group" aria-labelledby="agent-scope-time">
            <button v-for="kind in ['day', 'week', 'all', 'custom']" :key="kind" type="button" class="scope-chip" :class="{ on: mode === kind }" :aria-pressed="mode === kind" :disabled="frozen" @click="pick(kind)">{{ t(`agentScope.preset.${kind}`) }}</button>
          </div>
          <template v-if="mode === 'custom'">
            <label class="scope-field">
              <span>{{ t('agentScope.start') }}</span>
              <input class="nx-field" :class="{ 'nx-field-lg': roomy }" type="datetime-local" step="1" :value="walls.from.text" :disabled="frozen" :aria-invalid="!!errors.from" @input="changeWall('from', $event)" @blur="leaveWall('from', $event)" />
            </label>
            <p v-if="errors.from" class="scope-error" role="alert">{{ t(`agentScope.error.${errors.from}`) }}</p>
            <label class="scope-field">
              <span>{{ t('agentScope.end') }}</span>
              <input class="nx-field" :class="{ 'nx-field-lg': roomy }" type="datetime-local" step="1" :value="walls.to.text" :disabled="frozen" :aria-invalid="!!errors.to" @input="changeWall('to', $event)" @blur="leaveWall('to', $event)" />
            </label>
            <p v-if="errors.to" class="scope-error" role="alert">{{ t(`agentScope.error.${errors.to}`) }}</p>
          </template>
          <!-- A preset can be refused as well, so this is not part of the custom editors. -->
          <p v-if="errors.range" class="scope-error" role="alert">{{ t(`agentScope.error.${errors.range}`) }}</p>
          <label class="scope-field">
            <span>{{ t('agentScope.zone') }}</span>
            <input class="nx-field" :class="{ 'nx-field-lg': roomy }" type="text" list="agent-scope-zones" autocomplete="off" spellcheck="false" :value="zoneText" :disabled="frozen" :aria-invalid="!!errors.zone" @input="changeZone($event, false)" @change="changeZone($event, true)" />
          </label>
          <datalist id="agent-scope-zones"><option v-for="zone in zones" :key="zone" :value="zone"></option></datalist>
          <p v-if="errors.zone" class="scope-error" role="alert">{{ t(`agentScope.error.${errors.zone}`) }}</p>
          <!-- What is stored and will be sent, not what is half-typed above. -->
          <dl class="scope-facts">
            <div><dt>{{ t('agentScope.start') }}</dt><dd>{{ range.fromInclusive ? clock(range.fromInclusive, range.timeZone) : t('agentScope.anyStart') }}</dd></div>
            <div><dt>{{ t('agentScope.end') }}</dt><dd>{{ range.toExclusive ? clock(range.toExclusive, range.timeZone) : t('agentScope.anyEnd') }}</dd></div>
            <div><dt>{{ t('agentScope.zone') }}</dt><dd>{{ range.timeZone }}</dd></div>
          </dl>
        </section>

        <details class="scope-part">
          <summary>{{ t('agentScope.receipt') }}<span class="material-symbols-outlined" aria-hidden="true">expand_more</span></summary>
          <p class="scope-note" role="status">{{ t(receiptActive ? 'agentScope.currentReceipt' : 'agentScope.recentReceipt') }}</p>
          <p v-if="receiptMessage?.scopeState === 'unavailable'" class="scope-error" role="status">{{ t('agentScope.resultUnavailable') }}</p>
          <dl v-else-if="receipt" class="scope-facts">
            <div><dt>{{ t('agentScope.receiptChats') }}</dt><dd>{{ receipt.chatIds.join(', ') || '—' }}</dd></div>
            <div><dt>{{ t('agentScope.receiptContacts') }}</dt><dd>{{ receipt.contactUserIds.join(', ') || '—' }}</dd></div>
            <div><dt>{{ t('agentScope.start') }}</dt><dd>{{ receipt.timeRange.fromInclusive ? clock(receipt.timeRange.fromInclusive, receipt.timeRange.timeZone) : t('agentScope.anyStart') }}</dd></div>
            <div><dt>{{ t('agentScope.end') }}</dt><dd>{{ receipt.timeRange.toExclusive ? clock(receipt.timeRange.toExclusive, receipt.timeRange.timeZone) : t('agentScope.anyEnd') }}</dd></div>
            <div><dt>{{ t('agentScope.zone') }}</dt><dd>{{ receipt.timeRange.timeZone }}</dd></div>
          </dl>
          <p v-else class="scope-note">{{ t('agentScope.receiptUnknown') }}</p>
          <p class="scope-note">{{ t('agentScope.receiptNote') }}</p>
        </details>

        <details class="scope-part">
          <summary>{{ t('agentScope.advanced') }}<span class="material-symbols-outlined" aria-hidden="true">expand_more</span></summary>
          <p class="scope-note">{{ t('agentScope.override') }}</p>
          <dl class="scope-facts">
            <div><dt>{{ t('agentScope.version') }}</dt><dd>{{ workspace.bindingVersion ?? '—' }} <small>{{ t('agentScope.fromBinding') }}</small></dd></div>
            <div><dt>{{ t('agentScope.generation') }}</dt><dd>{{ workspace.sessionGeneration ?? '—' }} <small>{{ t('agentScope.fromBinding') }}</small></dd></div>
            <div><dt>{{ t('agentScope.authorizationVersion') }}</dt><dd>{{ receipt?.authorizationVersion ?? '—' }} <small>{{ t('agentScope.fromReceipt') }}</small></dd></div>
          </dl>
        </details>
      </div>

      <footer class="scope-foot">
        <p class="scope-status" role="status">{{ t(`agentScope.status.${state}`) }}</p>
        <p v-if="saved" class="scope-status" role="status">{{ t('agentScope.saved') }}</p>
        <p v-if="saveError" class="scope-error" role="alert">{{ t('agentScope.saveFailed') }} {{ displayError(saveError) }}</p>
        <button type="button" class="scope-primary" :disabled="frozen || invalid || workspace.scopeConfirmed" @click="workspace.confirmScope()">{{ t('agentScope.confirmNext') }}</button>
        <button type="button" class="scope-btn" :disabled="frozen || invalid || !workspace.scopeConfirmed || workspace.bindingVersion == null || !agent.sessionId" @click="save">{{ t(saving ? 'agentScope.saving' : 'agentScope.save') }}</button>
        <p class="scope-note">{{ t('agentScope.saveNote') }}</p>
      </footer>
    </section>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, inject, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAgentStore } from '@/stores/agent'
import { useAgentWorkspaceStore } from '@/stores/agentWorkspace'
import { useChatStore } from '@/stores/chat'
import { useUserStore } from '@/stores/user'
import { canonicalZone, lastDay, utcToWall, wallRange, weekToDate } from './scopeTime.mjs'

const props = defineProps({ submitting: { type: Boolean, default: false } })
const { t } = useI18n({ useScope: 'global' })
const agent = useAgentStore(), workspace = useAgentWorkspaceStore(), chatStore = useChatStore(), user = useUserStore()
const setActiveView = inject('setActiveView', () => {})
const messageOf = error => error.response?.data?.message || error.message || t('common.failed')
const displayError = text => /^(?:Network\s*Error|Failed to fetch)$/i.test(text || '') ? t('agentScope.serviceUnavailable') : text
// The device zone leads the suggestions; the scope itself keeps the zone it was given.
const zones = [...new Set([Intl.DateTimeFormat().resolvedOptions().timeZone, 'UTC', ...Intl.supportedValuesOf('timeZone')])]

// A load starts at the click. The store only reports the part after the session
// exists, and creating that session is a request of its own.
const saving = ref(false), loading = ref(false)
const running = computed(() => props.submitting || agent.streaming)
const reading = computed(() => loading.value || workspace.scopeLoading)
// A scope in flight cannot change under the request that carries it.
const frozen = computed(() => running.value || agent.switching || reading.value || saving.value)
const count = computed(() => workspace.scopeSelection.targets.length)
// The count is what is selected here. Nothing on this button says "authorized".
const state = computed(() => running.value ? 'running' : reading.value ? 'loading' : !count.value ? 'none' : workspace.scopeConfirmed ? 'confirmed' : 'pending')
const stateText = computed(() => t(`agentScope.state.${state.value}`, { n: count.value }))
const summaryText = computed(() => t(`agentScope.compact.${state.value}`, { n: count.value }))

// ── Panel: above the button on a desktop, a bottom sheet on a narrow screen ──
const open = ref(false), anchor = ref(null), trigger = ref(null), panel = ref(null), heading = ref(null)
const place = ref({}), narrow = ref(false), roomy = ref(false)
// The nearest box that clips the panel (the workbench column).
function clipBox() {
  let clip = anchor.value.parentElement
  while (clip.parentElement && getComputedStyle(clip).overflowY === 'visible') clip = clip.parentElement
  return clip
}
function measure() {
  narrow.value = window.innerWidth <= 480
  roomy.value = narrow.value || window.matchMedia('(pointer: coarse)').matches
  // The panel cannot leave the box that clips it: it is left-aligned with the button,
  // pulled back inside that box, and never taller than the room above the button.
  const bounds = clipBox().getBoundingClientRect(), box = anchor.value.getBoundingClientRect(), width = Math.min(420, bounds.width - 16)
  place.value = { '--scope-width': `${width}px`, '--scope-shift': `${Math.max(bounds.left + 8, Math.min(box.left, bounds.right - 8 - width)) - box.left}px`, '--scope-max': `${box.top - bounds.top - 16}px` }
}
// Seen before anything under the press can swallow it.
const capture = { capture: true }
const outside = event => { if (!anchor.value.contains(event.target) && !panel.value?.contains(event.target)) close() }
function show() { measure(); open.value = true }
function close(refocus = false) { open.value = false; if (refocus) trigger.value.focus() }
// The window is not the only thing that moves the button. The input growing, a hint
// appearing under the toolbar and the column changing width all do, without any
// window resize, so every box from the button up to the one that clips the panel is
// watched while the panel is open.
const sizes = new ResizeObserver(measure)
function release() { document.removeEventListener('pointerdown', outside, capture); window.removeEventListener('resize', measure); sizes.disconnect() }
watch(open, async shown => {
  if (!shown) { release(); return }
  document.addEventListener('pointerdown', outside, capture); window.addEventListener('resize', measure)
  const clip = clipBox()
  for (let box = anchor.value; box !== clip; box = box.parentElement) sizes.observe(box)
  sizes.observe(clip)
  await nextTick(); heading.value?.focus()
})
onBeforeUnmount(release)
function onKey(event) {
  if (!open.value) return
  if (event.key === 'Escape') { event.stopPropagation(); close(true); return }
  // The bottom sheet covers the page, so Tab stays inside it.
  if (event.key !== 'Tab' || !narrow.value) return
  const stops = [...panel.value.querySelectorAll('button:not(:disabled), input:not(:disabled), summary')]
  const edge = event.shiftKey ? stops[0] : stops.at(-1)
  if (document.activeElement !== edge && document.activeElement !== heading.value) return
  event.preventDefault(); (event.shiftKey ? stops.at(-1) : stops[0]).focus()
}
defineExpose({ open: show })

// ── Binding and resources ──
const loadError = ref(''), saveError = ref(''), groupError = ref(''), savedKey = ref('')
const resources = computed(() => [...workspace.scopeResources.contacts, ...workspace.scopeResources.groups])
const loaded = computed(() => workspace.bindingVersion != null || resources.value.length > 0)
// A refused turn reports through the store; a failed save is already shown beside its button.
const bindingError = computed(() => displayError(loadError.value || (workspace.scopeError !== saveError.value ? workspace.scopeError : '')))
// What a request reports belongs to the account and session it was made for. Each
// change of either starts a new epoch; an answer from an earlier one is dropped.
let epoch = 0
async function load() {
  const mine = epoch
  loading.value = true; loadError.value = ''; saveError.value = ''
  try {
    const sid = await agent.ensureSession()
    // A session lookup can settle after the user has already switched. Do not even
    // ask the workspace to select or load that stale session in the new context.
    if (mine !== epoch || sid !== agent.sessionId) return
    await workspace.loadScope(sid)
  }
  catch (error) { if (mine === epoch) loadError.value = messageOf(error) }
  finally { if (mine === epoch) loading.value = false }
}
// Sending reloads a missing binding by itself; any load that starts supersedes the last one's failure.
watch(() => workspace.scopeLoading, started => { if (started) loadError.value = '' }, { flush: 'sync' })
const selectionKey = computed(() => JSON.stringify(workspace.scopeSelection))
// "Saved" describes the selection that was saved, not whatever is edited afterwards.
const saved = computed(() => savedKey.value === selectionKey.value && !workspace.scopeConfirmed)
async function save() {
  const mine = epoch
  saving.value = true; saveError.value = ''; savedKey.value = ''
  // saveScope spends the confirmation whether or not the server accepts the binding.
  try { await workspace.saveScope(agent.sessionId); if (mine === epoch) savedKey.value = selectionKey.value }
  catch (error) { if (mine === epoch) saveError.value = messageOf(error) }
  finally { if (mine === epoch) saving.value = false }
}

// ── Targets: only what the server listed, plus what is selected but no longer listed ──
const query = ref('')
const keyOf = item => `${item.type}:${item.id}`
const chosen = computed(() => new Set(workspace.scopeSelection.targets.map(keyOf)))
const groups = computed(() => {
  const needle = query.value.trim().toLowerCase()
  const listed = new Set(resources.value.map(keyOf))
  const missing = workspace.scopeSelection.targets.filter(target => !listed.has(keyOf(target))).map(target => ({ ...target, label: '', missing: true }))
  return ['contact', 'group'].map(type => ({ type, rows: [...resources.value, ...missing].filter(item => item.type === type && (!needle || item.label.toLowerCase().includes(needle) || String(item.id).includes(needle))) })).filter(group => group.rows.length)
})
function choose(targets) { workspace.setScope({ ...workspace.scopeSelection, targets }) }
function toggle(item) {
  const key = keyOf(item)
  choose(chosen.value.has(key) ? workspace.scopeSelection.targets.filter(target => keyOf(target) !== key) : [...workspace.scopeSelection.targets, { type: item.type, id: item.id }])
}
// A scope row is an authorization candidate, not a conversation, so the entry
// resolves the real chat from the chat store and only then opens it. It never
// selects or confirms anything.
const groupChatOf = chatId => chatStore.groupChats.find(chat => String(chat.id) === String(chatId))
async function enterGroup(item) {
  const mine = epoch
  groupError.value = ''
  const chatId = item.chatId ?? item.id
  let group = groupChatOf(chatId)
  if (!group) {
    // The chat snapshot may not have arrived yet; ask once instead of hiding the entry.
    const userId = user.currentUser?.id
    try { if (userId) await chatStore.fetchChats(userId) } catch (error) { if (mine === epoch) groupError.value = messageOf(error) }
    if (mine !== epoch) return
    group = groupChatOf(chatId)
  }
  if (!group) { groupError.value = t('agentScope.groupUnavailable'); return }
  close()
  chatStore.setActiveChat(group)
  setActiveView('chats')
}

// ── Receipt: what Java reported for a run. It never decides the local state above. ──
const receiptMessage = computed(() => agent.messages.findLast(message => message.role === 'assistant' && message.runId))
const receipt = computed(() => receiptMessage.value?.scopeState === 'observed' ? receiptMessage.value.actualScope : null)
const receiptActive = computed(() => agent.streaming && receiptMessage.value?.status === 'streaming')

// ── Time: typed as a wall clock in the scope's zone, stored as UTC ──
const range = computed(() => workspace.scopeSelection.timeRange)
const rangeKey = computed(() => `${range.value.fromInclusive} ${range.value.toExclusive} ${range.value.timeZone}`)
// A boundary is shown to the second; one stored with a fraction keeps it, so nothing reads as rounder than it is.
const clock = (iso, zone) => { const ms = new Date(iso).getUTCMilliseconds(); return utcToWall(iso, zone).replace('T', ' ') + (ms ? `.${String(ms).padStart(3, '0')}` : '') }
// Each side of the custom range as its field holds it: the field's own text, and
// whether that text is an unfinished entry. The browser reports an unfinished entry
// as an empty value, which must not read as "no limit".
const zoneText = ref(''), walls = ref({ from: { text: '', bad: false }, to: { text: '', bad: false } }), errors = ref({})
// A zone that is still being typed is not the stored one, so it cannot be confirmed either.
const invalid = computed(() => Object.keys(errors.value).length > 0 || zoneText.value !== range.value.timeZone)
const stored = side => { const iso = range.value[side === 'from' ? 'fromInclusive' : 'toExclusive']; return iso ? utcToWall(iso, range.value.timeZone) : '' }
// The fields follow whatever is stored (a load, a save, another session); an
// entry that was refused stays on screen until the stored range itself changes.
// Text that already reads as the stored boundary is left as typed: writing an
// equivalent string back into a date-time field restarts the entry under the user.
function follow() {
  zoneText.value = range.value.timeZone
  for (const side of ['from', 'to']) {
    const held = walls.value[side]
    if (held.bad || (held.text.length === 16 ? `${held.text}:00` : held.text) !== stored(side)) walls.value[side] = { text: stored(side), bad: false }
  }
  errors.value = {}
}
watch(rangeKey, follow, { immediate: true })
// A half-typed date-time lives only in its field, and closing the panel removed that
// field: what was never stored is dropped instead of coming back as an empty box with an error.
watch(open, shown => { if (shown && (walls.value.from.bad || walls.value.to.bad)) follow() })
// A preset is remembered only for the exact range it produced.
const picked = ref({ kind: 'all', at: 0, key: '' })
const mode = computed(() => picked.value.key === rangeKey.value ? picked.value.kind : range.value.fromInclusive || range.value.toExclusive ? 'custom' : 'all')
function commit(kind, at) {
  // Any edit, even one that is refused, ends the previous confirmation.
  workspace.scopeConfirmed = false
  const unfinished = kind === 'custom' && ['from', 'to'].find(side => walls.value[side].bad)
  if (unfinished) { errors.value = { [unfinished]: 'invalid' }; return }
  let timeRange
  try {
    timeRange = kind === 'custom' ? wallRange(walls.value.from.text, walls.value.to.text, zoneText.value)
      : { ...(kind === 'day' ? lastDay(at) : kind === 'week' ? weekToDate(at, canonicalZone(zoneText.value)) : { fromInclusive: null, toExclusive: null }), timeZone: canonicalZone(zoneText.value) }
  } catch (error) { errors.value = { [error.field]: error.code }; return }
  workspace.setScope({ ...workspace.scopeSelection, timeRange })
  follow()
  picked.value = { kind, at, key: rangeKey.value }
}
function pick(kind) {
  // "Custom" opens the editors on the range as it stands; it changes nothing by itself.
  if (kind === 'custom') picked.value = { kind, at: 0, key: rangeKey.value }
  else commit(kind, Date.now())
}
function changeWall(side, event) {
  walls.value[side] = { text: event.target.value, bad: event.target.validity.badInput }
  commit('custom', 0)
}
// Filling an empty field half way, or emptying a half-filled one, leaves its value
// empty and so raises no input event. Leaving the field is when that is noticed.
function leaveWall(side, event) { if (event.target.validity.badInput !== walls.value[side].bad) changeWall(side, event) }
function changeZone(event, settled) {
  zoneText.value = event.target.value
  // Applied once it is a listed name (a suggestion was picked or typed in full) or the
  // field is left; text still being typed only ends the previous confirmation.
  if (!settled && !zones.includes(zoneText.value)) { workspace.scopeConfirmed = false; return }
  // The zone is how the range is read: a preset is taken again in the new zone at
  // its original click, custom entries keep their digits.
  commit(mode.value, picked.value.at)
}
// Synchronous, so the epoch has moved before any late answer can run. A session that
// comes into being under a load or a send is the same conversation, not a new one.
watch(() => [user.currentUser?.id, user.token, agent.sessionId], (now, was) => {
  if (now[0] !== was[0] || now[1] !== was[1] || (was[2] && was[2] !== now[2])) { epoch++; loading.value = false; saving.value = false }
  query.value = ''; loadError.value = ''; saveError.value = ''; groupError.value = ''; savedKey.value = ''
}, { flush: 'sync' })
watch(() => agent.sessionId, follow)
</script>

<style scoped>
.scope-anchor{position:relative;display:inline-flex;min-width:0}
/* The name stays whole and the summary is what gives way: capped at 220px, and cut
   further only when the toolbar itself is narrower than the button. */
.scope-trigger{display:inline-flex;align-items:center;gap:5px;min-width:0;height:26px;padding:0 9px;border-radius:13px;background:var(--field-bg);box-shadow:0 0 0 1px var(--field-ring) inset;color:var(--field-icon);font-size:.75rem;line-height:1rem;font-weight:500;letter-spacing:.02em;white-space:nowrap;transition:box-shadow var(--field-ease),color var(--field-ease)}
.scope-trigger:hover,.scope-trigger[aria-expanded="true"]{color:#fff;box-shadow:0 0 0 1px var(--field-ring-hover) inset}
.scope-trigger.pending{color:#e4e4e7;box-shadow:0 0 0 1px var(--field-ring-focus) inset}
.scope-trigger.confirmed{color:#fff}
.scope-trigger .material-symbols-outlined{flex:none;font-size:14px}
.scope-name{flex:none}
.scope-name.narrow{display:none}
.scope-state{min-width:0;max-width:220px;overflow:hidden;text-overflow:ellipsis;color:#e4e4e7;font-family:var(--wb-mono,monospace);font-size:11px;font-weight:400}
.scope-state::before{content:'· ';color:var(--field-icon)}
.scope-trigger.none .scope-state,.scope-trigger.loading .scope-state{color:var(--field-icon)}
.scope-chevron{transition:transform var(--field-ease)}
.scope-trigger[aria-expanded="true"] .scope-chevron{transform:rotate(180deg)}
.scope-backdrop{display:none}
.scope-panel{position:absolute;z-index:50;left:var(--scope-shift);bottom:calc(100% + 8px);display:flex;flex-direction:column;width:var(--scope-width);max-height:min(720px,var(--scope-max));border:1px solid var(--wb-line,#303034);border-radius:16px;background:var(--wb-panel,#1b1b1e);box-shadow:0 14px 36px rgba(0,0,0,.5);color:#e4e4e7;font-size:13px;line-height:1.5;text-align:start;letter-spacing:0;cursor:default}
/* Preflight is off in this app, so the panel resets the few elements it uses. */
.scope-panel h3,.scope-panel h4,.scope-panel h5,.scope-panel p,.scope-panel ul,.scope-panel dl,.scope-panel dd{margin:0}
.scope-panel ul{padding:0;list-style:none}
.scope-head{display:grid;grid-template-columns:1fr auto;align-items:center;gap:2px 8px;flex:none;padding:16px 16px 12px;border-bottom:1px solid var(--wb-line,#303034)}
.scope-head h3{font-size:14px;font-weight:600;outline:0}
.scope-head p{grid-column:1/-1;color:#9e9ea6;font-size:12px}
.scope-body{display:grid;gap:12px;flex:1;min-height:0;padding:12px 16px;overflow-y:auto;overscroll-behavior:contain;scrollbar-width:thin;scrollbar-color:#404044 transparent}
.scope-part{display:grid;gap:8px;min-width:0}
.scope-part+.scope-part{padding-top:12px;border-top:1px solid var(--wb-line,#303034)}
.scope-part h4,.scope-part summary{font-size:12px;font-weight:600;color:#e4e4e7}
.scope-part summary{min-height:28px;display:flex;align-items:center;justify-content:space-between;cursor:pointer;list-style:none}
.scope-part summary::-webkit-details-marker{display:none}
.scope-part summary .material-symbols-outlined{color:#9e9ea6;transition:transform var(--field-ease)}
.scope-part[open]>summary .material-symbols-outlined{transform:rotate(180deg)}
.scope-line{display:flex;align-items:center;gap:8px;min-width:0}
.scope-line h4{margin-right:auto}
.scope-note{min-width:0;color:#9e9ea6;font-size:12px}
.scope-error{color:#fff;font-size:12px;padding:6px 10px;border:1px solid #6f7274;border-radius:8px;background:#232326;overflow-wrap:anywhere}
.scope-btn,.scope-primary,.scope-chip{display:inline-flex;align-items:center;justify-content:center;gap:5px;min-height:32px;padding:5px 12px;border-radius:999px;font-size:12px;font-weight:500;transition:background-color var(--field-ease),color var(--field-ease)}
.scope-btn{flex:none;background:var(--field-btn-bg);color:var(--field-btn-ink)}
.scope-btn:hover:not(:disabled){background:var(--field-btn-bg-hover);color:#fff}
.scope-primary{background:#fff;color:#2f3131;min-height:36px;font-size:13px}
.scope-primary:hover:not(:disabled){background:#e2e2e2}
.scope-primary:disabled{background:var(--field-btn-bg);color:#6f7274}
.scope-btn:disabled,.scope-chip:disabled,.scope-link:disabled{opacity:.45;cursor:not-allowed}
.scope-link{display:inline-flex;align-items:center;gap:3px;flex:none;min-height:28px;padding:2px 8px;border-radius:999px;color:#c4c7c8;font-size:12px}
.scope-link:hover:not(:disabled){background:var(--field-btn-bg);color:#fff}
.scope-icon{display:grid;place-items:center;width:28px;height:28px;padding:0;border-radius:50%;color:#9e9ea6}
.scope-icon:hover{background:var(--field-btn-bg);color:#fff}
.scope-panel .material-symbols-outlined{font-size:16px}
.scope-list{max-height:208px;overflow-y:auto;scrollbar-width:thin;scrollbar-color:#404044 transparent}
.scope-list h5{padding:6px 2px 2px;color:#9e9ea6;font:11px/1.5 var(--wb-mono,monospace)}
.scope-item{display:flex;align-items:center;gap:6px;min-width:0;border-radius:8px}
.scope-item:hover{background:#222225}
.scope-item label{display:flex;align-items:center;gap:8px;flex:1;min-width:0;min-height:32px;padding:2px 6px;cursor:pointer}
.scope-item input{flex:none;width:15px;height:15px;accent-color:#888}
.scope-item-name{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.scope-item-id{flex:none;color:#9e9ea6;font:11px/1.5 var(--wb-mono,monospace)}
.scope-presets{display:flex;flex-wrap:wrap;gap:6px}
.scope-chip{flex:1 1 auto;white-space:nowrap;padding-inline:6px;background:#222225;color:#9e9ea6}
.scope-chip:hover:not(:disabled){background:var(--field-btn-bg-hover);color:#fff}
.scope-chip.on{background:#34343a;color:#fff;box-shadow:0 0 0 1px #55555c inset}
.scope-field{display:grid;grid-template-columns:92px minmax(0,1fr);align-items:center;gap:8px;color:#9e9ea6;font-size:12px}
.scope-facts{display:grid;gap:2px;font-size:12px}
.scope-facts div{display:grid;grid-template-columns:92px minmax(0,1fr);gap:8px}
.scope-facts dt{color:#9e9ea6}
.scope-facts dd{font-family:var(--wb-mono,monospace);overflow-wrap:anywhere}
.scope-facts small{color:#9e9ea6;font-family:'Geist Sans',system-ui,sans-serif}
.scope-foot{display:grid;grid-template-columns:1fr auto;gap:8px;flex:none;padding:12px 16px 16px;border-top:1px solid var(--wb-line,#303034)}
.scope-foot p,.scope-foot .scope-primary{grid-column:1/-1}
.scope-foot .scope-btn{grid-column:1/-1;justify-self:start}
.scope-status{font-size:12px;color:#e4e4e7}
.scope-anchor button:focus-visible,.scope-part summary:focus-visible,.scope-item input:focus-visible{outline:2px solid #eee;outline-offset:2px}
@media(max-width:480px){
  .scope-name.wide{display:none}.scope-name.narrow{display:inline}
  .scope-chevron{display:none}
  .scope-trigger{padding-inline:8px;gap:4px}
  .scope-backdrop{display:block;position:fixed;inset:0;z-index:49;background:rgba(0,0,0,.55)}
  .scope-panel{position:fixed;left:8px;right:8px;bottom:max(8px,env(safe-area-inset-bottom));width:auto;max-height:85dvh}
  .scope-presets{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))}
  .scope-field{grid-template-columns:minmax(0,1fr);gap:4px}
}
@media(max-width:480px),(pointer:coarse){
  .scope-trigger,.scope-btn,.scope-primary,.scope-chip,.scope-link,.scope-item label,.scope-part summary{min-height:44px}
  .scope-trigger{height:44px;border-radius:22px}
  .scope-icon{width:44px;height:44px}
  .scope-item input{width:18px;height:18px}
}
@media(max-height:600px){
  .scope-panel{overflow-y:auto;overscroll-behavior:contain}
  .scope-body{flex:none;overflow-y:visible}
}
@media(prefers-reduced-motion:reduce){.scope-trigger,.scope-chevron,.scope-btn,.scope-primary,.scope-chip,.scope-part summary .material-symbols-outlined{transition:none}}
</style>
