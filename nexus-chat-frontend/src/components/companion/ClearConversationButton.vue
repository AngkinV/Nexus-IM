<template>
  <!-- Keep even disabled-button events out of the selectable role row. -->
  <span class="clear-conversation-control" @click.stop @keydown.stop @keyup.stop>
    <button
      class="clear-conversation-button"
      type="button"
      :disabled="disabled"
      :aria-busy="loading"
      :aria-label="label"
      :title="label"
      @click="confirmClearConversation"
    >
      <span
        class="material-symbols-outlined"
        :class="{ 'is-spinning': loading }"
        aria-hidden="true"
      >{{ loading ? 'progress_activity' : 'delete_sweep' }}</span>
    </button>
  </span>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCompanionStore } from '@/stores/companion'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  role: { type: Object, required: true }
})

const { t } = useI18n({
  useScope: 'local',
  messages: {
    zh: {
      defaultName: '陪伴者',
      action: '删除与「{name}」的对话',
      confirming: '等待确认删除与「{name}」的对话',
      clearing: '正在删除与「{name}」的对话…',
      confirmTitle: '删除与「{name}」的对话？',
      confirmMessage: '将从对话列表中移除「{name}」，并永久清空所有消息、全部夜晚记录及摘要，重置对话中的角色状态，此操作无法撤销。角色、模型、成长数据和独立记忆均会保留。可通过“与已有角色对话”重新开启对话，但旧消息不会恢复；如需删除独立记忆，请前往角色档案另行删除。',
      confirm: '永久删除对话',
      cancel: '取消',
      success: '已删除与「{name}」的对话。',
      busy: '「{name}」仍在回复、散场或删除对话，请稍后重试。',
      failed: '未能删除与「{name}」的对话，请重试。',
      unsupported: '当前后端不支持删除对话，请更新后端后重试。',
      notFound: '找不到「{name}」或无法删除该对话，请刷新后重试。',
      userChanged: '当前用户已切换，已取消删除。请在当前账号下重新操作。'
    },
    en: {
      defaultName: 'Companion',
      action: 'Delete conversation with “{name}”',
      confirming: 'Awaiting confirmation to delete the conversation with “{name}”',
      clearing: 'Deleting conversation with “{name}”…',
      confirmTitle: 'Delete conversation with “{name}”?',
      confirmMessage: 'This removes “{name}” from the conversation list, permanently deletes all messages, evening records and summaries, and resets their conversation state. This cannot be undone. The character, model, growth data and independent memories are kept. You can start again via “Chat with an existing character”, but old messages will not be restored. To delete independent memories, remove them separately in the role dossier.',
      confirm: 'Permanently delete conversation',
      cancel: 'Cancel',
      success: 'Conversation with “{name}” deleted.',
      busy: '“{name}” is still replying, closing an evening or deleting a conversation. Please try again later.',
      failed: 'Could not delete the conversation with “{name}”. Please try again.',
      unsupported: 'The current backend does not support deleting conversations. Update the backend and try again.',
      notFound: '“{name}” could not be found or the conversation could not be deleted. Refresh and try again.',
      userChanged: 'The current user has changed. Deletion was cancelled. Please try again with the current account.'
    }
  }
})

const companionStore = useCompanionStore()
const userStore = useUserStore()
const pending = ref(false)
const submitting = ref(false)
const loading = computed(() => submitting.value || companionStore.clearingRoleIds.includes(props.role.id))
const busy = computed(() => companionStore.isRoleBusy(props.role.id))
const disabled = computed(() => pending.value || loading.value || busy.value || !userStore.currentUser?.id || !props.role.id)
const label = computed(() => t(
  loading.value ? 'clearing' : busy.value ? 'busy' : pending.value ? 'confirming' : 'action',
  { name: props.role.name || t('defaultName') }
))

async function confirmClearConversation() {
  if (disabled.value) return

  // The dialog belongs to this user and role, never to the current selection.
  const userId = userStore.currentUser.id
  const roleId = props.role.id
  const name = props.role.name || t('defaultName')
  pending.value = true
  try {
    try {
      await ElMessageBox.confirm(t('confirmMessage', { name }), t('confirmTitle', { name }), {
        confirmButtonText: t('confirm'),
        cancelButtonText: t('cancel'),
        type: 'warning',
        closeOnClickModal: false,
        autofocus: false
      })
    } catch {
      return
    }

    if (userStore.currentUser?.id !== userId) {
      ElMessage.warning(t('userChanged'))
      return
    }
    // A reply or closing operation may have started while confirmation was open.
    if (companionStore.isRoleBusy(roleId)) {
      ElMessage.warning(t('busy', { name }))
      return
    }

    submitting.value = true
    await companionStore.clearConversation(userId, roleId)
    if (userStore.currentUser?.id === userId) ElMessage.success(t('success', { name }))
  } catch (error) {
    if (userStore.currentUser?.id !== userId) return
    const status = Number(error?.response?.status ?? error?.status)
    if (status === 409) {
      ElMessage.warning(t('busy', { name }))
    } else if (status === 405) {
      ElMessage.error(t('unsupported'))
    } else if (status === 404) {
      ElMessage.error(t('notFound', { name }))
    } else {
      ElMessage.error(t('failed', { name }))
    }
  } finally {
    submitting.value = false
    pending.value = false
  }
}
</script>

<style scoped>
.clear-conversation-control {
  display: inline-flex;
  flex-shrink: 0;
  align-self: center;
}

.clear-conversation-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: transparent;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  touch-action: manipulation;
  transition: color 0.15s, background-color 0.15s, border-color 0.15s;
}

.clear-conversation-button:not(:disabled):hover,
.clear-conversation-button:not(:disabled):active {
  color: var(--el-color-error);
  border-color: var(--el-color-error);
  background: var(--el-fill-color-light);
}

.clear-conversation-button:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

.clear-conversation-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.material-symbols-outlined {
  font-size: 18px;
  pointer-events: none;
}

.is-spinning {
  animation: clear-conversation-spin 0.8s linear infinite;
}

@keyframes clear-conversation-spin {
  to { transform: rotate(360deg); }
}

@media (pointer: coarse), (hover: none) {
  .clear-conversation-button {
    width: 44px;
    height: 44px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .clear-conversation-button { transition: none; }
  .is-spinning { animation: none; }
}
</style>
