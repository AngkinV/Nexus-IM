<template>
  <el-dropdown trigger="click" @command="onCommand" placement="bottom-start" max-height="420px">
    <button class="session-trigger" :title="$t('agent.history.title')">
      <span class="material-icons-round">history</span>
      <span class="session-title">{{ activeTitle }}</span>
      <el-icon><ArrowDown /></el-icon>
    </button>

    <template #dropdown>
      <el-dropdown-menu class="session-menu">
        <el-dropdown-item command="__new__" class="new-row">
          <el-icon><Plus /></el-icon>
          <span>{{ $t('agent.history.newConversation') }}</span>
        </el-dropdown-item>

        <el-dropdown-item v-if="!agentStore.sessions.length" disabled>
          {{ $t('agent.history.empty') }}
        </el-dropdown-item>

        <el-dropdown-item
          v-for="s in agentStore.sessions"
          :key="s.sessionId"
          :command="s.sessionId"
          :class="{ 'is-active': s.sessionId === agentStore.sessionId }"
        >
          <div class="row">
            <div class="row-main">
              <div class="row-title">{{ s.title || $t('agent.history.untitled') }}</div>
              <div class="row-time">{{ formatTime(s.updatedAt) }}</div>
            </div>
            <div class="row-actions" @click.stop>
              <el-tooltip :content="$t('agent.history.rename')" placement="top">
                <button class="icon-btn" @click.stop="onRename(s)">
                  <el-icon :size="14"><Edit /></el-icon>
                </button>
              </el-tooltip>
              <el-tooltip :content="$t('common.delete')" placement="top">
                <button class="icon-btn danger" @click.stop="onDelete(s)">
                  <el-icon :size="14"><Delete /></el-icon>
                </button>
              </el-tooltip>
            </div>
          </div>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Plus, Edit, Delete } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { useAgentStore } from '@/stores/agent'

const { t } = useI18n()
const agentStore = useAgentStore()

const activeTitle = computed(() => {
  return agentStore.activeSession?.title
      || (agentStore.sessionId ? t('agent.history.untitled') : t('agent.history.newConversation'))
})

onMounted(() => {
  agentStore.loadSessions()
})

function formatTime(t) {
  if (!t) return ''
  const d = dayjs(t)
  if (!d.isValid()) return ''
  const today = dayjs().startOf('day')
  if (d.isAfter(today)) return d.format('HH:mm')
  if (d.isAfter(today.subtract(6, 'day'))) return d.format('ddd HH:mm')
  return d.format('YYYY-MM-DD')
}

async function onCommand(command) {
  if (command === '__new__') {
    await agentStore.newSession()
    ElMessage.success(t('agent.history.created'))
    return
  }
  if (typeof command === 'string') {
    await agentStore.switchSession(command)
  }
}

async function onRename(s) {
  let next
  try {
    const result = await ElMessageBox.prompt(
      t('agent.history.renamePrompt'),
      t('agent.history.rename'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        inputValue: s.title || '',
        inputValidator: (v) => !!v && v.trim().length > 0,
        inputErrorMessage: t('agent.history.renameRequired')
      }
    )
    next = result.value
  } catch {
    return
  }
  try {
    await agentStore.renameSession(s.sessionId, next.trim())
    ElMessage.success(t('agent.history.renamed'))
  } catch (err) {
    ElMessage.error(err.message || t('common.failed'))
  }
}

async function onDelete(s) {
  try {
    await ElMessageBox.confirm(
      t('agent.history.deleteConfirm', { name: s.title || t('agent.history.untitled') }),
      t('common.delete'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )
  } catch { return }
  try {
    await agentStore.deleteSession(s.sessionId)
    ElMessage.success(t('agent.history.deleted'))
  } catch (err) {
    ElMessage.error(err.message || t('common.failed'))
  }
}
</script>

<style scoped>
.session-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid rgba(20, 184, 166, 0.25);
  background: rgba(20, 184, 166, 0.08);
  color: var(--tg-text-primary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  max-width: 240px;
  transition: var(--tg-transition);
}
.session-trigger:hover {
  background: rgba(20, 184, 166, 0.16);
}
.session-trigger .material-icons-round {
  font-size: 16px;
  color: var(--tg-primary);
}
.session-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 160px;
}

.session-menu {
  min-width: 320px;
  max-width: 380px;
}
.session-menu :deep(.el-dropdown-menu__item) {
  padding: 8px 12px;
}
.session-menu :deep(.el-dropdown-menu__item.is-active) {
  background: rgba(6, 182, 212, 0.08);
  color: var(--tg-primary);
}
.new-row {
  font-weight: 600;
  color: var(--tg-primary);
}
.row {
  display: flex;
  align-items: center;
  width: 100%;
  gap: 8px;
}
.row-main {
  flex: 1;
  min-width: 0;
}
.row-title {
  font-size: 13.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-time {
  font-size: 11px;
  color: var(--tg-text-secondary);
  margin-top: 2px;
}
.row-actions {
  display: none;
  gap: 2px;
  flex-shrink: 0;
}
.session-menu :deep(.el-dropdown-menu__item):hover .row-actions {
  display: inline-flex;
}
.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  background: transparent;
  border-radius: 6px;
  color: var(--tg-text-secondary);
  cursor: pointer;
}
.icon-btn:hover {
  background: rgba(0, 0, 0, 0.05);
  color: var(--tg-text-primary);
}
.icon-btn.danger:hover {
  color: #ef4444;
  background: rgba(239, 68, 68, 0.1);
}
</style>
