<template>
  <!-- Prism pane: every companion role the user has, the active one pinned on top.
       Mirrors the AI session list so the two canvases read the same way. -->
  <div class="flex flex-col h-full min-h-0">
    <div class="p-space-md flex flex-col gap-space-sm border-b border-outline-variant/20 shrink-0">
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-2 min-w-0">
          <span class="material-symbols-outlined text-primary text-[18px]">nightlife</span>
          <span class="font-headline-md text-[14px] leading-5 tracking-tight text-primary font-medium truncate">{{ $t('leftPanel.navCompanion') }}</span>
        </div>
        <div class="flex items-center gap-1 shrink-0">
          <span
            class="font-code text-[11px] px-1.5 py-0.5 rounded border border-outline-variant/30 bg-surface-container-low text-on-surface-variant"
          >{{ $t('agent.history.count', { n: companionStore.conversationRoles.length }) }}</span>
          <button
            class="blade-collapse-btn w-7 h-7 rounded bg-surface-container-low hover:bg-primary hover:text-on-primary text-on-surface-variant flex items-center justify-center transition-all duration-200 active:scale-90"
            :title="$t('leftPanel.collapseList')"
            type="button"
            @click="toggleList"
          >
            <span class="material-symbols-outlined text-[16px]">keyboard_double_arrow_left</span>
          </button>
        </div>
      </div>

      <button
        class="w-full flex items-center justify-between py-2 px-3 rounded-lg bg-surface-container-low border border-outline-variant/40 text-primary hover:bg-surface-container hover:border-outline-variant transition-all duration-150 active:scale-[0.99] disabled:opacity-50 disabled:cursor-not-allowed"
        type="button"
        :disabled="inviting"
        :title="$t('companion.seats.inviteHint')"
        @click="invite"
      >
        <div class="flex items-center gap-2">
          <span class="material-symbols-outlined text-[18px] text-primary">
            {{ inviting ? 'progress_activity' : 'add' }}
          </span>
          <span class="font-label-md text-[14px] leading-[18px] tracking-[0.01em] font-medium">
            {{ inviting ? $t('companion.seats.inviting') : $t('companion.seats.invite') }}
          </span>
        </div>
      </button>

      <button
        v-if="hiddenRoles.length"
        class="w-full flex items-center justify-between py-2 px-3 rounded-lg bg-surface-container-low border border-outline-variant/40 text-primary hover:bg-surface-container hover:border-outline-variant transition-all duration-150 active:scale-[0.99] disabled:opacity-50 disabled:cursor-not-allowed"
        type="button"
        :disabled="isRestoring || inviting || companionStore.isLoading || !userStore.currentUser?.id"
        :title="localT('existingConversation')"
        aria-haspopup="dialog"
        :aria-expanded="restoreDialogVisible"
        @click="restoreDialogVisible = true"
      >
        <span class="flex items-center gap-2">
          <span class="material-symbols-outlined text-[18px] text-primary" aria-hidden="true">chat_bubble</span>
          <span class="font-label-md text-[14px] leading-[18px] tracking-[0.01em] font-medium">{{ localT('existingConversation') }}</span>
        </span>
      </button>

      <button
        class="w-full flex items-center justify-between py-2 px-3 rounded-lg bg-surface-container-low border border-outline-variant/40 text-primary hover:bg-surface-container hover:border-outline-variant transition-all duration-150 active:scale-[0.99]"
        type="button"
        :title="$t('settings.roleAndModel')"
        @click="$router.push('/settings')"
      >
        <div class="flex items-center gap-2">
          <span class="material-symbols-outlined text-[18px] text-primary">tune</span>
          <span class="font-label-md text-[14px] leading-[18px] tracking-[0.01em] font-medium">{{ $t('companion.roleManage') }}</span>
        </div>
        <kbd class="px-1.5 py-0.5 rounded bg-surface-container-high text-on-surface-variant font-code text-[11px]">⌘,</kbd>
      </button>
    </div>

    <div class="role-scroll flex-1 min-h-0 overflow-y-auto px-2 py-3 space-y-4">
      <div class="space-y-1">
        <div class="px-3 py-1 flex items-center justify-between text-on-surface-variant/60 font-label-sm text-label-sm uppercase tracking-wider">
          <span>{{ $t('companion.seats.current') }}</span>
          <span class="font-code text-[10px]">{{ $t('companion.roleCount', { n: regularRoles.length }) }}</span>
        </div>

        <div
          v-for="role in regularRoles"
          :key="role.id"
          class="relative group cursor-pointer px-3 py-2.5 rounded-lg transition-all flex items-start gap-3"
          :class="
            role.id === companionStore.activeRoleId
              ? 'bg-surface-container border border-outline-variant/40 text-primary'
              : 'hover:bg-surface-container-low text-on-surface-variant hover:text-primary'
          "
          role="button"
          tabindex="0"
          @click="selectRole(role)"
          @keydown.enter="selectRole(role)"
        >
          <div
            v-if="role.id === companionStore.activeRoleId"
            class="w-1 absolute left-0 top-2 bottom-2 bg-primary rounded-r"
          ></div>
          <div
            class="w-8 h-8 rounded-full shrink-0 overflow-hidden flex items-center justify-center border border-outline-variant/40"
            :class="role.id === companionStore.activeRoleId ? 'bg-surface-container-high text-primary' : 'bg-surface-container-low text-on-surface-variant'"
          >
            <img v-if="role.avatarUrl" class="w-full h-full object-cover grayscale" :src="role.avatarUrl" alt="" />
            <span v-else class="material-symbols-outlined text-[16px]">person</span>
          </div>
          <div class="flex-1 min-w-0">
            <div
              class="font-label-md text-[14px] leading-[18px] tracking-[0.01em] font-medium truncate"
              :class="role.id === companionStore.activeRoleId ? 'text-primary font-medium' : 'group-hover:text-primary'"
            >{{ role.name || $t('companion.defaultName') }}</div>
            <div
              class="font-body-md text-[14px] truncate mt-0.5"
              :class="role.id === companionStore.activeRoleId ? 'text-on-surface-variant' : 'text-on-surface-variant/70'"
            >{{ role.presence || role.tone || $t('companion.defaultStatus') }}</div>
          </div>
          <span
            v-if="role.id === companionStore.activeRoleId"
            class="material-symbols-outlined text-[15px] text-primary shrink-0 mt-1"
          >chat_bubble</span>
          <ClearConversationButton :role="role" />
        </div>
      </div>

      <!-- Distinguish loading from an empty conversation list. -->
      <div v-if="companionStore.isLoading" class="px-3 py-6 flex items-center justify-center gap-2 text-on-surface-variant/70">
        <span class="role-list-spinner"></span>
        <span class="font-body-md text-[14px]">{{ $t('companion.loadingRoles') }}</span>
      </div>
      <div v-else-if="!companionStore.conversationRoles.length" class="px-3 py-6 text-center font-body-md text-[14px] text-on-surface-variant/60">
        {{ localT(hiddenRoles.length ? 'emptyWithHidden' : 'empty') }}
      </div>

      <!-- Guests: faces met in one evening, not part of the household. -->
      <div v-if="guestRoles.length" class="space-y-1">
        <div class="px-3 py-1 flex items-center justify-between text-on-surface-variant/60 font-label-sm text-label-sm uppercase tracking-wider">
          <span>{{ $t('companion.seats.past') }}</span>
          <span class="font-code text-[10px]">{{ $t('companion.seats.pastCount', { n: guestRoles.length }) }}</span>
        </div>

        <div
          v-for="role in guestRoles"
          :key="role.id"
          class="relative group cursor-pointer px-3 py-2.5 rounded-lg transition-all flex items-start gap-3"
          :class="
            role.id === companionStore.activeRoleId
              ? 'bg-surface-container border border-outline-variant/40 text-primary'
              : 'hover:bg-surface-container-low text-on-surface-variant hover:text-primary'
          "
          role="button"
          tabindex="0"
          @click="selectRole(role)"
          @keydown.enter="selectRole(role)"
        >
          <div
            v-if="role.id === companionStore.activeRoleId"
            class="w-1 absolute left-0 top-2 bottom-2 bg-primary rounded-r"
          ></div>
          <div
            class="w-8 h-8 rounded-full shrink-0 overflow-hidden flex items-center justify-center border border-outline-variant/40"
            :class="role.id === companionStore.activeRoleId ? 'bg-surface-container-high text-primary' : 'bg-surface-container-low text-on-surface-variant'"
          >
            <img v-if="role.avatarUrl" class="w-full h-full object-cover grayscale" :src="role.avatarUrl" alt="" />
            <span v-else class="material-symbols-outlined text-[16px]">person</span>
          </div>
          <div class="flex-1 min-w-0">
            <div
              class="font-label-md text-[14px] leading-[18px] tracking-[0.01em] font-medium truncate"
              :class="role.id === companionStore.activeRoleId ? 'text-primary font-medium' : 'group-hover:text-primary'"
            >{{ role.name || $t('companion.defaultName') }}</div>
            <div
              class="font-body-md text-[14px] truncate mt-0.5"
              :class="role.id === companionStore.activeRoleId ? 'text-on-surface-variant' : 'text-on-surface-variant/70'"
            >{{ role.presence || role.tone || $t('companion.defaultStatus') }}</div>
          </div>
          <button
            class="role-adopt-btn shrink-0 mt-0.5"
            type="button"
            :title="$t('companion.seats.adopt')"
            @click.stop="adopt(role)"
          >
            <span class="material-symbols-outlined text-[15px]">home</span>
          </button>
          <ClearConversationButton :role="role" />
        </div>
      </div>
    </div>

    <TavernBusy
      :visible="inviting"
      :title="$t('companion.seats.invitingTitle')"
      :hint="$t('companion.seats.invitingHint')"
    />

    <el-dialog
      v-model="restoreDialogVisible"
      :title="localT('existingConversation')"
      width="min(440px, calc(100vw - 32px))"
      append-to-body
      :close-on-click-modal="!isRestoring"
      :close-on-press-escape="!isRestoring"
      :show-close="!isRestoring"
    >
      <p class="mb-4 text-on-surface-variant">{{ localT('restoreHint') }}</p>
      <div class="flex flex-col gap-2 max-h-[50vh] overflow-y-auto" :aria-busy="isRestoring">
        <button
          v-for="role in hiddenRoles"
          :key="role.id"
          class="w-full flex items-center justify-between gap-3 py-2 px-3 rounded-lg bg-surface-container-low border border-outline-variant/40 text-primary hover:bg-surface-container hover:border-outline-variant transition-all duration-150 disabled:opacity-50 disabled:cursor-not-allowed"
          type="button"
          :disabled="isRestoring || !userStore.currentUser?.id || companionStore.isRoleBusy(role.id)"
          :aria-busy="restoringRoleId === role.id"
          @click="restoreRole(role)"
        >
          <span class="min-w-0 truncate">{{ role.name || $t('companion.defaultName') }}</span>
          <span class="flex items-center gap-2 shrink-0" aria-live="polite">
            <span v-if="restoringRoleId === role.id" class="role-list-spinner" aria-hidden="true"></span>
            <span>{{ localT(restoringRoleId === role.id ? 'restoring' : 'startConversation') }}</span>
          </span>
        </button>
        <p v-if="!hiddenRoles.length" class="text-on-surface-variant">{{ localT('noHiddenRoles') }}</p>
      </div>
      <template #footer>
        <el-button :disabled="isRestoring" @click="restoreDialogVisible = false">{{ localT('cancel') }}</el-button>
      </template>
    </el-dialog>

    <div v-if="companionStore.activeRole" class="p-3 bg-surface-container-low/70 border-t border-outline-variant/20 flex items-center justify-between shrink-0">
      <div class="flex items-center gap-2.5 min-w-0">
        <div
          class="w-7 h-7 rounded bg-surface-container-high border border-outline-variant/30 flex items-center justify-center text-primary shrink-0"
        >
          <span class="material-symbols-outlined text-[16px]">person_heart</span>
        </div>
        <div class="min-w-0">
          <div class="font-label-md text-[14px] leading-[18px] tracking-[0.01em] font-medium text-primary truncate">
            {{ companionStore.activeRole?.name || $t('companion.defaultName') }}
          </div>
          <div class="font-code text-[10px] text-on-surface-variant/70 truncate">
            {{ $t('companion.growthShort', { intimacy: companionStore.growth?.intimacy ?? 0, trust: companionStore.growth?.trust ?? 0 }) }}
          </div>
        </div>
      </div>
      <span class="w-1.5 h-1.5 rounded-full bg-primary inline-block shrink-0"></span>
    </div>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCompanionStore } from '@/stores/companion'
import { useUserStore } from '@/stores/user'
import { ElButton, ElDialog, ElMessage } from 'element-plus'
import { readSceneKey } from '@/components/companion/tavernScenes'
import TavernBusy from '@/components/companion/TavernBusy.vue'
import ClearConversationButton from '@/components/companion/ClearConversationButton.vue'

const { t } = useI18n({ useScope: 'global' })
const { t: localT } = useI18n({
  useScope: 'local',
  messages: {
    zh: {
      existingConversation: '与已有角色对话',
      empty: '暂无对话，可以邀请新角色开始聊天。',
      emptyWithHidden: '暂无对话。可以邀请新角色，或选择“与已有角色对话”重新开始。',
      restoreHint: '选择角色重新开启对话。角色、模型、成长数据和独立记忆仍会保留，旧消息不会恢复。',
      noHiddenRoles: '没有可重新开启对话的角色。',
      startConversation: '开始对话',
      restoring: '正在开启…',
      restoreFailed: '无法重新开启对话，请稍后重试。',
      cancel: '取消'
    },
    en: {
      existingConversation: 'Chat with an existing character',
      empty: 'No conversations yet. Invite a new character to start chatting.',
      emptyWithHidden: 'No conversations yet. Invite a new character or choose “Chat with an existing character” to start again.',
      restoreHint: 'Choose a character to start a new conversation. The character, model, growth data and independent memories are kept; old messages will not be restored.',
      noHiddenRoles: 'There are no characters with deleted conversations to reopen.',
      startConversation: 'Start conversation',
      restoring: 'Starting…',
      restoreFailed: 'Could not reopen the conversation. Please try again later.',
      cancel: 'Cancel'
    }
  }
})
const companionStore = useCompanionStore()
const toggleList = inject('toggleList', () => {})
const userStore = useUserStore()

// Like the chat list: order follows the last time you actually spoke to this
// character, NOT which one happens to be selected. Selecting a row is just
// looking at it — it must not drag that row to the top.
// Characters with no conversation yet keep their creation order at the bottom.
const byRecency = (a, b) => {
  const at = (r) => {
    const t = r.lastMessageAt
    if (!t) return 0
    const ms = new Date(t).getTime()
    return Number.isFinite(ms) ? ms : 0
  }
  const diff = at(b) - at(a)
  if (diff !== 0) return diff
  // Stable tie-break: never rely on array order from the server.
  return (a.id ?? 0) - (b.id ?? 0)
}

const regularRoles = computed(() =>
  companionStore.conversationRoles
    .filter((r) => (r.kind || 'regular') === 'regular')
    .slice()
    .sort(byRecency)
)

const guestRoles = computed(() =>
  companionStore.conversationRoles
    .filter((r) => r.kind === 'guest')
    .slice()
    .sort(byRecency)
)

const hiddenRoles = computed(() => companionStore.roles.filter((role) => role.conversationHidden))
const restoreDialogVisible = ref(false)
const restoringRoleId = ref(null)
const isRestoring = computed(() => restoringRoleId.value !== null)

async function restoreRole(role) {
  const userId = userStore.currentUser?.id
  if (!userId || isRestoring.value || !role.conversationHidden || companionStore.isRoleBusy(role.id)) return
  restoringRoleId.value = role.id
  try {
    // The store reopens and selects the conversation only after the POST succeeds.
    await companionStore.restoreConversation(userId, role.id)
    if (userStore.currentUser?.id === userId) restoreDialogVisible.value = false
  } catch {
    if (userStore.currentUser?.id === userId) ElMessage.error(localT('restoreFailed'))
  } finally {
    restoringRoleId.value = null
  }
}

onMounted(async () => {
  if (!userStore.currentUser?.id) return
  await companionStore.ensureInitialized(userStore.currentUser.id)
})

async function selectRole(role) {
  if (role.id === companionStore.activeRoleId) return
  await companionStore.setActiveRole(userStore.currentUser.id, role.id)
}

// Fill the empty seat. Costs one model call when a model is configured; the
// server falls back to a neutral guest when none is.
const inviting = ref(false)
async function invite() {
  const userId = userStore.currentUser?.id
  if (!userId || inviting.value) return
  inviting.value = true
  try {
    const role = await companionStore.inviteGuest(userId, readSceneKey(companionStore.activeRoleId))
    if (role) {
      await companionStore.fetchRoles(userId)
      await companionStore.setActiveRole(userId, role.id)
      return
    }
    // A timeout does not mean nothing happened — the server may have created the
    // guest already. Re-read the list before saying anything failed.
    await companionStore.fetchRoles(userId)
    const newest = companionStore.roles
      .filter((r) => r.kind === 'guest')
      .slice()
      .sort((a, b) => (b.id ?? 0) - (a.id ?? 0))[0]
    if (newest) {
      await companionStore.setActiveRole(userId, newest.id)
      ElMessage.success(t('companion.seats.invitedSlow'))
    } else {
      ElMessage.error(t('companion.seats.inviteFailed'))
    }
  } finally {
    inviting.value = false
  }
}

// Keep a guest: they move from 过往相逢 into the household.
async function adopt(role) {
  const userId = userStore.currentUser?.id
  if (!userId) return
  try {
    await companionStore.adoptRole(userId, role.id)
    await companionStore.fetchRoles(userId)
    ElMessage.success(t('companion.seats.adopted'))
  } catch {
    ElMessage.error(t('common.operationFailed'))
  }
}
</script>

<style scoped>
.role-list-spinner {
  width: 12px;
  height: 12px;
  border: 1.5px solid rgba(142, 145, 146, 0.35);
  border-top-color: #ffffff;
  border-radius: 9999px;
  animation: role-list-spin 0.8s linear infinite;
}

@keyframes role-list-spin {
  to { transform: rotate(360deg); }
}

/* Adopt button: quiet until hovered, so the guest row still reads as a name
   first and an action second. */
.role-adopt-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: 1px solid rgba(68, 71, 72, 0.35);
  border-radius: 4px;
  background: transparent;
  color: rgba(142, 145, 146, 0.7);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s, color 0.15s, border-color 0.15s;
}

.group:hover .role-adopt-btn,
.role-adopt-btn:focus-visible {
  opacity: 1;
}

.role-adopt-btn:hover {
  color: #ffffff;
  border-color: rgba(255, 255, 255, 0.5);
}

.role-scroll::-webkit-scrollbar {
  width: 6px;
}

.role-scroll::-webkit-scrollbar-track {
  background: transparent;
}

.role-scroll::-webkit-scrollbar-thumb {
  background: #353438;
  border-radius: 9999px;
}

.role-scroll::-webkit-scrollbar-thumb:hover {
  background: #8e9192;
}
</style>
