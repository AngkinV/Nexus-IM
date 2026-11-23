<template>
  <!--
    Model channel switcher for the session header.
    One row per saved chat channel, grouped by where it comes from (official vendor,
    aggregator, local server, custom endpoint), with a search box so the list stays
    usable with dozens of channels. A channel that cannot answer yet (no model chosen,
    no key) is shown with the reason instead of being hidden.
  -->
  <span class="model-switcher">
    <el-popover
      v-model:visible="open"
      trigger="click"
      placement="bottom-end"
      :width="340"
      :show-arrow="false"
      :offset="6"
      :disabled="disabled"
      :teleported="true"
      popper-class="mcs-popper"
      @show="onShow"
    >
      <template #reference>
        <button
          type="button" class="mcs-trigger" :class="{ 'is-open': open }" :disabled="disabled" :title="label"
          :aria-label="$t('agent.providers.switcher.title')"
        >
          <span class="material-symbols-outlined" aria-hidden="true">memory</span>
          <span class="mcs-trigger-text">{{ label }}</span>
          <span class="material-symbols-outlined" aria-hidden="true">arrow_drop_down</span>
        </button>
      </template>

      <div class="mcs" @keydown.down.prevent="move(1)" @keydown.up.prevent="move(-1)" @keydown.enter.prevent="pick(cursorId)" @keydown.esc.stop.prevent="open = false">
        <el-input
          ref="search" v-model="query" size="small" clearable
          :placeholder="$t('agent.providers.switcher.search')" :aria-label="$t('agent.providers.switcher.search')"
        >
          <template #prefix><span class="material-symbols-outlined mcs-search-icon" aria-hidden="true">search</span></template>
        </el-input>

        <el-scrollbar max-height="320px" class="mcs-scroll">
          <div role="listbox" :aria-label="$t('agent.providers.switcher.title')">
            <template v-for="group in groups" :key="group.key">
              <div class="mcs-group" aria-hidden="true">
                <span>{{ $t(`agent.providers.group.${group.key}`) }}</span><span>{{ group.items.length }}</span>
              </div>
              <button
                v-for="c in group.items" :key="c.id" type="button" class="mcs-row" role="option"
                :class="{ 'is-active': same(c.id, activeId), 'is-cursor': same(c.id, cursorId) }"
                :aria-selected="same(c.id, activeId)" :aria-disabled="!!c.issue" :disabled="!!c.issue"
                :data-channel="c.id" @click="pick(c.id)" @mousemove="cursorId = c.id"
              >
                <span class="mcs-mark material-symbols-outlined" aria-hidden="true">{{ same(c.id, activeId) ? 'check' : '' }}</span>
                <span class="mcs-main">
                  <span class="mcs-name">
                    <span class="mcs-name-text">{{ c.displayName || c.provider }}</span>
                    <span v-if="c.isDefault" class="mcs-tag">{{ $t('agent.providers.default') }}</span>
                  </span>
                  <span class="mcs-sub" :class="{ 'is-issue': c.issue }">
                    {{ c.issue ? $t(`agent.providers.switcher.issue.${c.issue}`) : c.defaultModel }}
                  </span>
                </span>
                <span class="mcs-vendor">{{ c.vendorLabel }}</span>
              </button>
            </template>
            <p v-if="!channels.length" class="mcs-empty">{{ $t('agent.providers.switcher.none') }}</p>
            <p v-else-if="!groups.length" class="mcs-empty">{{ $t('agent.providers.switcher.noMatch') }}</p>
          </div>
        </el-scrollbar>

        <div class="mcs-foot">
          <span class="mcs-count">{{ $t('agent.providers.switcher.count', { n: channels.length }) }}</span>
          <button type="button" class="mcs-manage" @click="manage">
            <span class="material-symbols-outlined" aria-hidden="true">tune</span>{{ $t('agent.providers.manage') }}
          </button>
        </div>
      </div>
    </el-popover>
  </span>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { groupChannels } from '@/stores/agentProviders'

const props = defineProps({
  channels: { type: Array, default: () => [] },
  activeId: { type: [Number, String], default: null },
  label: { type: String, default: '' },
  disabled: Boolean
})
const emit = defineEmits(['select', 'manage'])

const open = ref(false), query = ref(''), cursorId = ref(null), search = ref(null)
const same = (a, b) => a != null && b != null && String(a) === String(b)

const groups = computed(() => groupChannels(props.channels, query.value))
const usable = computed(() => groups.value.flatMap(g => g.items).filter(c => !c.issue))

function settleCursor() {
  const list = usable.value
  if (list.some(c => same(c.id, cursorId.value))) return
  cursorId.value = (list.find(c => same(c.id, props.activeId)) || list[0])?.id ?? null
}
watch(groups, settleCursor)

function onShow() {
  query.value = ''
  cursorId.value = null
  settleCursor()
  nextTick(() => search.value?.focus?.())
}

function move(step) {
  const list = usable.value
  if (!list.length) return
  const at = list.findIndex(c => same(c.id, cursorId.value))
  cursorId.value = list[(at + step + list.length) % list.length].id
  nextTick(() => globalThis.document?.querySelector(`.mcs-popper [data-channel="${cursorId.value}"]`)?.scrollIntoView?.({ block: 'nearest' }))
}

function pick(id) {
  const channel = usable.value.find(c => same(c.id, id))
  if (!channel) return
  open.value = false
  if (!same(channel.id, props.activeId)) emit('select', channel.id)
}

function manage() {
  open.value = false
  emit('manage')
}

watch(() => props.disabled, off => { if (off) open.value = false })
</script>

<style scoped>
.model-switcher { display: inline-flex; min-width: 0; }
.mcs-trigger {
  display: flex; align-items: center; gap: 6px; min-width: 0;
  font: 11px/1.4 'JetBrains Mono', ui-monospace, monospace; color: inherit;
  background: #232326; border: 1px solid #323236; border-radius: 5px; padding: 3px 6px; cursor: pointer;
}
.mcs-trigger:hover:not(:disabled), .mcs-trigger.is-open { border-color: #5a5a60; background: #2a2a2d; }
.mcs-trigger:focus-visible { outline: 1px solid #ffffff; outline-offset: 1px; }
.mcs-trigger:disabled { opacity: .5; cursor: not-allowed; }
.mcs-trigger .material-symbols-outlined { font-size: 16px; }
.mcs-trigger-text { max-width: 150px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
@media (max-width: 1120px) { .mcs-trigger-text { max-width: 105px; } }
@media (max-width: 850px) { .mcs-trigger-text { max-width: 80px; } }
@media (max-width: 650px) { .mcs-trigger-text { display: none; } }
@media (pointer: coarse) { .mcs-trigger { min-height: 44px; } }
</style>

<!-- The list is teleported to <body>, outside this component's scope. -->
<style>
.el-popper.mcs-popper {
  width: min(340px, calc(100vw - 16px)) !important;
  padding: 8px; border-radius: 8px; /* surface and border come from the Prism popper theme */
}
.mcs { display: grid; gap: 8px; color: #e4e4e7; font-family: 'Geist Sans', system-ui, sans-serif; }
.mcs-search-icon { font-size: 16px; color: #8e9192; }
.mcs-scroll { margin: 0 -4px; }
.mcs-group {
  display: flex; justify-content: space-between; padding: 6px 8px 4px;
  font: 10px/1.4 'JetBrains Mono', ui-monospace, monospace; letter-spacing: .06em; text-transform: uppercase; color: #8e9192;
}
.mcs-row {
  display: grid; grid-template-columns: 16px minmax(0, 1fr) auto; align-items: center; gap: 8px;
  width: 100%; padding: 7px 8px; border: 0; border-radius: 6px; background: transparent; color: inherit; text-align: left; cursor: pointer;
}
.mcs-row.is-cursor:not(:disabled) { background: #2a2a2d; }
.mcs-row.is-active { background: rgba(255, 255, 255, .08); }
.mcs-row:focus-visible { outline: 1px solid #ffffff; outline-offset: -1px; }
.mcs-row:disabled { cursor: not-allowed; opacity: .55; }
.mcs-mark { font-size: 15px; color: #ffffff; }
.mcs-main { display: grid; gap: 1px; min-width: 0; }
.mcs-name { display: flex; align-items: center; gap: 6px; min-width: 0; font-size: 13px; font-weight: 500; }
.mcs-name-text, .mcs-sub { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.mcs-tag { flex: none; padding: 0 5px; border: 1px solid #444748; border-radius: 4px; font-size: 10px; font-weight: 400; color: #c4c7c8; }
.mcs-sub { font: 11px/1.5 'JetBrains Mono', ui-monospace, monospace; color: #8e9192; }
.mcs-sub.is-issue { font-family: inherit; color: #c4c7c8; }
.mcs-vendor { max-width: 96px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: 11px; color: #8e9192; }
.mcs-empty { margin: 0; padding: 18px 8px; text-align: center; font-size: 12px; color: #8e9192; }
.mcs-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding-top: 8px; border-top: 1px solid #303034; }
.mcs-count { font: 11px/1.4 'JetBrains Mono', ui-monospace, monospace; color: #8e9192; }
.mcs-manage {
  display: inline-flex; align-items: center; gap: 4px; padding: 4px 8px; border: 1px solid #323236; border-radius: 5px;
  background: transparent; color: #e4e4e7; font-size: 12px; cursor: pointer;
}
.mcs-manage:hover { background: #2a2a2d; border-color: #5a5a60; }
.mcs-manage:focus-visible { outline: 1px solid #ffffff; outline-offset: 1px; }
.mcs-manage .material-symbols-outlined { font-size: 15px; }
@media (pointer: coarse) { .mcs-row { min-height: 44px; } .mcs-manage { min-height: 36px; } }
</style>
