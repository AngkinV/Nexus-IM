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
      <span class="material-icons-round">menu_book</span>
      <span class="kb-text">{{ buttonLabel }}</span>
      <el-icon><ArrowDown /></el-icon>
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
            <el-tag v-if="store.activeKbId === kb.kbId" type="success" size="small" effect="light">
              {{ $t('agent.knowledge.selectorActive') }}
            </el-tag>
          </div>
        </el-dropdown-item>

        <el-dropdown-item v-if="!store.kbs.length" disabled>
          {{ $t('agent.knowledge.empty') }}
        </el-dropdown-item>

        <el-dropdown-item divided command="__manage__">
          <el-icon><Setting /></el-icon>
          <span>{{ $t('agent.knowledge.manage') }}</span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowDown, Setting } from '@element-plus/icons-vue'

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
  gap: 6px;
  padding: 6px 10px;
  height: 34px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
  max-width: 200px;
}

.kb-switcher:hover {
  background: var(--el-fill-color);
  border-color: var(--el-border-color);
}

.kb-switcher--active {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
}

.kb-switcher .material-icons-round {
  font-size: 18px;
}

.kb-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 120px;
}

.kb-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 200px;
}

.kb-row strong {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.kb-row-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.kb-row--current strong {
  color: var(--el-color-primary);
}
</style>
