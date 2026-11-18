<template>
  <!--
    Compact KB selector: shows the currently linked KB (or "none") and
    lets the user pick one from a dropdown. Lives in the AgentChatView
    header. The actual management UI is in KnowledgeBaseManager.vue,
    which this component opens via @manage event.
  -->
  <el-dropdown trigger="click" @command="onSelect">
    <button
      class="kb-switcher"
      :class="{ 'kb-switcher--active': !!store.activeKb }"
      :title="$t('agent.knowledge.headerLabel')"
    >
      <span class="material-symbols-outlined">menu_book</span>
      <span class="kb-text">{{ buttonLabel }}</span>
      <span class="material-symbols-outlined">expand_more</span>
    </button>

    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item :command="null">
          <div class="kb-row" :class="{ 'kb-row--current': !store.activeKbId }">
            <strong>{{ $t('agent.knowledge.selectorNone') }}</strong>
            <span class="kb-row-hint">{{ $t('agent.knowledge.selectorNoneHint') }}</span>
          </div>
        </el-dropdown-item>

        <el-dropdown-item
          v-for="kb in store.kbs"
          :key="kb.kbId"
          :command="kb.kbId"
        >
          <div class="kb-row" :class="{ 'kb-row--current': store.activeKbId === kb.kbId }">
            <strong>{{ kb.name }}</strong>
            <span class="kb-row-hint">{{ kb.documentCount }} · {{ kb.chunkCount }}</span>
            <span v-if="store.activeKbId === kb.kbId" class="kb-row-tag">
              {{ $t('agent.knowledge.selectorActive') }}
            </span>
          </div>
        </el-dropdown-item>

        <el-dropdown-item v-if="!store.kbs.length" disabled>
          {{ $t('agent.knowledge.empty') }}
        </el-dropdown-item>

        <el-dropdown-item divided command="__manage__">
          <span class="material-symbols-outlined kb-manage-icon">tune</span>
          <span>{{ $t('agent.knowledge.manage') }}</span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'

import { useKnowledgeStore } from '@/stores/knowledge'

const emit = defineEmits(['manage'])

const { t } = useI18n()
const store = useKnowledgeStore()

const buttonLabel = computed(() => {
  if (store.activeKb) return store.activeKb.name
  return t('agent.knowledge.selectorNone')
})

onMounted(async () => {
  // The selector is the only place the chat header surfaces the user's KBs;
  // the manager dialog refreshes on its own when opened.
  if (!store.kbs.length) {
    await store.fetchKbs()
  }
})

function onSelect(command) {
  if (command === '__manage__') {
    emit('manage')
    return
  }
  store.setActiveKb(command || null)
}
</script>

<style scoped>
.kb-switcher {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 4px;
  color: #c4c7c8;
  font-size: 0.75rem;
  line-height: 1rem;
  font-weight: 500;
  letter-spacing: 0.02em;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
  min-width: 0;
}

.kb-switcher:hover,
.kb-switcher--active {
  color: #ffffff;
}

.kb-switcher:hover {
  background: #1f1f22;
}

.kb-switcher .material-symbols-outlined {
  font-size: 14px;
}

.kb-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 160px;
}

.kb-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 200px;
}

.kb-row strong {
  flex: 1;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.kb-row-hint {
  font-size: 12px;
  color: #8e9192;
}

.kb-row--current strong {
  color: #ffffff;
}

.kb-row-tag {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  background: #2a2a2d;
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #ffffff;
}

.kb-manage-icon {
  font-size: 16px;
  margin-right: 6px;
}
</style>
