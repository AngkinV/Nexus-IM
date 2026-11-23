<template>
  <!-- Prism pane: every companion conversation the user has, most recently
       spoken to first. Mirrors the AI session list so the two canvases read
       the same way. -->
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
        @click="openInvitePicker"
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

      <!-- Filters the rows already loaded: names and last lines. Nothing is
           requested, reordered or selected by typing here. -->
      <div class="relative flex items-center">
        <span
          class="material-symbols-outlined pointer-events-none absolute left-2.5 text-[16px] text-on-surface-variant"
          aria-hidden="true"
        >search</span>
        <input
          ref="searchInput"
          v-model="query"
          class="role-search h-9 w-full rounded-lg border border-solid border-outline-variant/40 bg-surface-container-low pl-8 pr-9 font-body-md text-[14px] text-primary placeholder:text-on-surface-variant/60"
          type="search"
          autocomplete="off"
          :placeholder="localT('search')"
          :aria-label="localT('search')"
        />
        <button
          v-if="query"
          class="role-search-clear absolute right-0 h-9 w-9 p-0 flex items-center justify-center rounded-lg text-on-surface-variant hover:text-primary transition-colors duration-150"
          type="button"
          :aria-label="localT('clearSearch')"
          :title="localT('clearSearch')"
          @click="clearSearch"
        >
          <span class="material-symbols-outlined text-[16px]" aria-hidden="true">close</span>
        </button>
      </div>
    </div>

    <div class="role-scroll flex-1 min-h-0 overflow-y-auto px-2 py-3 space-y-4">
      <!-- Empty groups take no space, whether empty to begin with or filtered. -->
      <div v-if="regularRows.length" class="space-y-1">
        <div class="px-3 py-1 flex items-center justify-between text-on-surface-variant/60 font-label-sm text-label-sm uppercase tracking-wider">
          <span>{{ $t('companion.seats.current') }}</span>
          <span class="font-code text-[10px]">{{ $t('companion.roleCount', { n: regularRows.length }) }}</span>
        </div>

        <div
          v-for="{ role, preview } in regularRows"
          :key="role.id"
          class="relative group px-3 py-2.5 rounded-lg transition-all flex items-start gap-3"
          :class="
            role.id === companionStore.activeRoleId
              ? 'bg-surface-container border border-outline-variant/40 text-primary'
              : 'hover:bg-surface-container-low text-on-surface-variant hover:text-primary'
          "
        >
          <div
            v-if="role.id === companionStore.activeRoleId"
            class="w-1 absolute left-0 top-2 bottom-2 bg-primary rounded-r"
          ></div>
          <button
            class="role-row-main flex-1 min-w-0 p-0 flex items-start gap-3 text-left"
            type="button"
            :aria-current="role.id === companionStore.activeRoleId ? 'true' : undefined"
            @click="selectRole(role)"
          >
            <span
              class="w-8 h-8 rounded-full shrink-0 overflow-hidden flex items-center justify-center border border-outline-variant/40"
              :class="role.id === companionStore.activeRoleId ? 'bg-surface-container-high text-primary' : 'bg-surface-container-low text-on-surface-variant'"
            >
              <img v-if="role.avatarUrl" class="w-full h-full object-cover grayscale" :src="role.avatarUrl" alt="" />
              <span v-else class="material-symbols-outlined text-[16px]" aria-hidden="true">person</span>
            </span>
            <span class="block flex-1 min-w-0">
              <span class="flex items-center justify-between gap-2">
                <span
                  class="block font-label-md text-[14px] leading-[18px] tracking-[0.01em] font-medium truncate"
                  :class="role.id === companionStore.activeRoleId ? 'text-primary font-medium' : 'group-hover:text-primary'"
                >{{ role.name || $t('companion.defaultName') }}</span>
                <span
                  v-if="role.lastMessageAt"
                  class="font-code text-[10px] text-on-surface-variant/60 shrink-0 tabular-nums"
                >{{ formatStamp(role.lastMessageAt) }}</span>
              </span>
              <!-- The last line actually said, the way a chat list row reads;
                   her greeting, labelled, before anything has been. The tooltip
                   is the original text, narration and line breaks included. -->
              <span
                class="block font-body-md text-[13px] leading-[17px] truncate mt-0.5"
                :class="role.id === companionStore.activeRoleId ? 'text-on-surface-variant' : 'text-on-surface-variant/70'"
                :title="preview.title"
              >{{ preview.text }}</span>
            </span>
            <span
              v-if="role.id === companionStore.activeRoleId"
              class="material-symbols-outlined text-[15px] text-primary shrink-0 mt-1"
              aria-hidden="true"
            >chat_bubble</span>
          </button>
          <ClearConversationButton class="role-row-action" :role="role" />
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
      <!-- Conversations exist, none match: say so, and offer the way back. -->
      <div v-else-if="searching && !regularRows.length && !guestRows.length" class="px-3 py-6 flex flex-col items-center gap-3 text-center">
        <p class="m-0 max-w-full break-words font-body-md text-[14px] text-on-surface-variant/60" role="status">
          {{ localT('noMatch', { q: query.trim() }) }}
        </p>
        <button
          class="role-search-reset py-1.5 px-3 rounded-lg bg-surface-container-low border border-outline-variant/40 text-primary font-label-md text-[13px] leading-[18px] hover:bg-surface-container hover:border-outline-variant transition-all duration-150 active:scale-[0.99]"
          type="button"
          @click="clearSearch"
        >{{ localT('clearSearch') }}</button>
      </div>

      <!-- Guests: faces met in one evening, not part of the household. -->
      <div v-if="guestRows.length" class="space-y-1">
        <div class="px-3 py-1 flex items-center justify-between text-on-surface-variant/60 font-label-sm text-label-sm uppercase tracking-wider">
          <span>{{ $t('companion.seats.past') }}</span>
          <span class="font-code text-[10px]">{{ $t('companion.seats.pastCount', { n: guestRows.length }) }}</span>
        </div>

        <div
          v-for="{ role, preview } in guestRows"
          :key="role.id"
          class="relative group px-3 py-2.5 rounded-lg transition-all flex items-start gap-3"
          :class="
            role.id === companionStore.activeRoleId
              ? 'bg-surface-container border border-outline-variant/40 text-primary'
              : 'hover:bg-surface-container-low text-on-surface-variant hover:text-primary'
          "
        >
          <div
            v-if="role.id === companionStore.activeRoleId"
            class="w-1 absolute left-0 top-2 bottom-2 bg-primary rounded-r"
          ></div>
          <button
            class="role-row-main flex-1 min-w-0 p-0 flex items-start gap-3 text-left"
            type="button"
            :aria-current="role.id === companionStore.activeRoleId ? 'true' : undefined"
            @click="selectRole(role)"
          >
            <span
              class="w-8 h-8 rounded-full shrink-0 overflow-hidden flex items-center justify-center border border-outline-variant/40"
              :class="role.id === companionStore.activeRoleId ? 'bg-surface-container-high text-primary' : 'bg-surface-container-low text-on-surface-variant'"
            >
              <img v-if="role.avatarUrl" class="w-full h-full object-cover grayscale" :src="role.avatarUrl" alt="" />
              <span v-else class="material-symbols-outlined text-[16px]" aria-hidden="true">person</span>
            </span>
            <span class="block flex-1 min-w-0">
              <span class="flex items-center justify-between gap-2">
                <span
                  class="block font-label-md text-[14px] leading-[18px] tracking-[0.01em] font-medium truncate"
                  :class="role.id === companionStore.activeRoleId ? 'text-primary font-medium' : 'group-hover:text-primary'"
                >{{ role.name || $t('companion.defaultName') }}</span>
                <span
                  v-if="role.lastMessageAt"
                  class="font-code text-[10px] text-on-surface-variant/60 shrink-0 tabular-nums"
                >{{ formatStamp(role.lastMessageAt) }}</span>
              </span>
              <span
                class="block font-body-md text-[13px] leading-[17px] truncate mt-0.5"
                :class="role.id === companionStore.activeRoleId ? 'text-on-surface-variant' : 'text-on-surface-variant/70'"
                :title="preview.title"
              >{{ preview.text }}</span>
            </span>
          </button>
          <button
            class="role-adopt-btn role-row-action shrink-0 mt-0.5"
            type="button"
            :title="$t('companion.seats.adopt')"
            :aria-label="$t('companion.seats.adopt')"
            @click.stop="adopt(role)"
          >
            <span class="material-symbols-outlined text-[15px]" aria-hidden="true">home</span>
          </button>
          <ClearConversationButton class="role-row-action" :role="role" />
        </div>
      </div>
    </div>

    <TavernBusy
      :visible="inviting"
      :title="$t('companion.seats.invitingTitle')"
      :hint="$t('companion.seats.invitingHint')"
    />

    <!-- Which face walks in. Default is "let the tavern choose" — the room
         fills without a decision, and the server prefers an unused model. -->
    <el-dialog
      v-model="pickerVisible"
      :title="localT('inviteTitle')"
      width="min(460px, calc(100vw - 32px))"
      append-to-body
      :close-on-click-modal="!inviting"
      :close-on-press-escape="!inviting"
      :show-close="!inviting"
    >
      <p class="mb-3 text-on-surface-variant text-[13px]">{{ localT('inviteHint') }}</p>

      <div class="flex flex-col gap-2 max-h-[50vh] overflow-y-auto" :aria-busy="pickerLoading">
        <div v-if="pickerLoading" class="px-3 py-6 flex items-center justify-center gap-2 text-on-surface-variant/70">
          <span class="role-list-spinner"></span>
          <span class="font-body-md text-[14px]">{{ localT('loadingModels') }}</span>
        </div>

        <template v-else>
          <!-- The default. Chosen by default so one click is enough. -->
          <button
            class="invite-model-row"
            :class="{ 'is-active': chosenModelUrl === null }"
            type="button"
            @click="chosenModelUrl = null"
          >
            <span class="material-symbols-outlined text-[18px]">auto_awesome</span>
            <span class="min-w-0 flex-1 text-left">
              <span class="block truncate">{{ localT('letTavernChoose') }}</span>
              <span class="block truncate text-[12px] text-on-surface-variant/70">{{ localT('letTavernChooseHint') }}</span>
            </span>
            <span v-if="chosenModelUrl === null" class="material-symbols-outlined text-[16px]">check</span>
          </button>

          <button
            v-for="model in inviteModels"
            :key="model.fileUrl"
            class="invite-model-row"
            :class="{ 'is-active': chosenModelUrl === model.fileUrl }"
            type="button"
            @click="chosenModelUrl = model.fileUrl"
          >
            <span class="material-symbols-outlined text-[18px]">view_in_ar</span>
            <span class="min-w-0 flex-1 text-left">
              <span class="block truncate">{{ model.fileName }}</span>
              <span class="block truncate text-[12px] text-on-surface-variant/70">{{ formatModelSize(model.size) }}</span>
            </span>
            <span v-if="chosenModelUrl === model.fileUrl" class="material-symbols-outlined text-[16px]">check</span>
          </button>

          <!-- Nothing uploaded yet: say so instead of showing an empty box. -->
          <p v-if="!inviteModels.length" class="text-on-surface-variant text-[13px] px-1 py-2">
            {{ localT('noModelsYet') }}
          </p>
        </template>
      </div>

      <template #footer>
        <el-button :disabled="inviting" @click="pickerVisible = false">{{ localT('cancel') }}</el-button>
        <el-button type="primary" :loading="inviting" :disabled="pickerLoading" @click="confirmInvite">
          {{ inviting ? localT('inviting') : localT('inviteAction') }}
        </el-button>
      </template>
    </el-dialog>

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
import dayjs from 'dayjs'
import { useCompanionStore } from '@/stores/companion'
import { useUserStore } from '@/stores/user'
import { useSettingsStore } from '@/stores/settings'
import { ElButton, ElDialog, ElMessage } from 'element-plus'
import { readSceneKey } from '@/components/companion/tavernScenes'
import { matchesQuery, previewOf } from '@/components/companion/roleText'
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
      noLineYet: '暂无消息',
      you: '你：',
      greeting: '开场白：',
      search: '搜索角色或消息',
      clearSearch: '清除搜索',
      noMatch: '没有匹配“{q}”的会话',
      inviteTitle: '邀请一个人入座',
      inviteHint: '选一个建模给她，或者让酒馆自己挑一个还没人用的。',
      letTavernChoose: '让酒馆挑选',
      letTavernChooseHint: '优先选没人在用的建模',
      loadingModels: '正在读取建模…',
      noModelsYet: '还没有上传过建模。可以到「设置 → 角色与模型」里导入，或直接邀请——她会先不显示形象。',
      inviting: '邀请中…',
      inviteAction: '邀请入座',
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
      noLineYet: 'No messages yet',
      you: 'You: ',
      greeting: 'Opening line: ',
      search: 'Search characters or messages',
      clearSearch: 'Clear search',
      noMatch: 'No conversations match “{q}”',
      inviteTitle: 'Invite someone to sit down',
      inviteHint: 'Give her a model, or let the tavern pick one nobody is wearing.',
      letTavernChoose: 'Let the tavern choose',
      letTavernChooseHint: 'Prefers a model nobody is wearing',
      loadingModels: 'Reading models…',
      noModelsYet: 'No models uploaded yet. Import one in Settings → Roles & Models, or invite anyway — she will appear without a body.',
      inviting: 'Inviting…',
      inviteAction: 'Invite',
      cancel: 'Cancel'
    }
  }
})
const companionStore = useCompanionStore()
const toggleList = inject('toggleList', () => {})
const userStore = useUserStore()
const settings = useSettingsStore()

/**
 * The row's timestamp, the same way the chat list reads one: time today, the
 * date before that. Follows the user's 24h/12h setting, never a fixed format.
 */
function formatStamp(value) {
  if (!value) return ''
  const d = dayjs(value)
  if (!d.isValid()) return ''
  const now = dayjs()
  if (d.isSame(now, 'day')) return settings.format(value, 'HH:mm')
  if (d.isSame(now, 'year')) return d.format('MM-DD')
  return d.format('YYYY-MM-DD')
}

/** A model's file size, so two same-named uploads can be told apart. */
function formatModelSize(bytes) {
  if (!bytes || bytes <= 0) return ''
  const mb = bytes / (1024 * 1024)
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
}

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

// ── Search ───────────────────────────────────────────────────────────────────
// Local and passive: it narrows the two groups above to the rows whose name or
// last line contains the query. The line is matched both as written (so words
// inside narration are found) and as the row shows it. Order, grouping and the
// selected character are untouched, and conversations the user removed stay out
// because the groups are built from conversationRoles only.
const query = ref('')
const searchInput = ref(null)
const searching = computed(() => !!query.value.trim())

function clearSearch() {
  query.value = ''
  // Back to the field, whichever control cleared it: the caret must not be
  // left on a button that is about to disappear.
  searchInput.value.focus()
}

// A row is the role plus the line under its name (see previewOf in roleText).
const toRows = (roles) => {
  const labels = { you: localT('you'), greeting: localT('greeting'), empty: localT('noLineYet') }
  return roles
    .map((role) => ({ role, preview: previewOf(role, labels) }))
    .filter((row) => matchesQuery(query.value, row.role.name || t('companion.defaultName'), row.preview.full, row.preview.line))
}
const regularRows = computed(() => toRows(regularRoles.value))
const guestRows = computed(() => toRows(guestRoles.value))

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

// ── Inviting: pick a face, or let the tavern pick ────────────────────────────
// The models come from the same catalogue the settings panel lists, so one
// uploaded there is immediately available here. "Let the tavern choose" is the
// default: a guest walking in should not require a decision, and the server
// prefers a model nobody else is wearing so the room does not fill with clones.
const inviteModels = ref([])
const pickerVisible = ref(false)
const pickerLoading = ref(false)
const chosenModelUrl = ref(null)

async function openInvitePicker() {
  const userId = userStore.currentUser?.id
  if (!userId || inviting.value) return
  pickerVisible.value = true
  chosenModelUrl.value = null
  // Always re-read. A model uploaded in Settings must show up here immediately;
  // caching the first list meant the picker kept offering a stale set until a
  // reload. The previous list stays on screen while the new one loads, so the
  // dialog does not flash empty.
  pickerLoading.value = !inviteModels.value.length
  try {
    inviteModels.value = await companionStore.fetchInviteModels(userId)
  } finally {
    pickerLoading.value = false
  }
}

function confirmInvite() {
  const modelUrl = chosenModelUrl.value
  pickerVisible.value = false
  invite(modelUrl)
}

// Fill the empty seat. Costs one model call when a model is configured; the
// server falls back to a neutral guest when none is.
const inviting = ref(false)
async function invite(modelUrl = null) {
  const userId = userStore.currentUser?.id
  if (!userId || inviting.value) return
  // Which characters existed before this attempt. A timeout does not mean
  // nothing happened, but it also does not mean the newest guest is ours —
  // without this, a failed invite jumped to whichever guest was already last.
  const knownIds = new Set(companionStore.roles.map((r) => r.id))
  inviting.value = true
  try {
    const role = await companionStore.inviteGuest(userId, readSceneKey(companionStore.activeRoleId), modelUrl)
    if (role) {
      await companionStore.fetchRoles(userId)
      await companionStore.setActiveRole(userId, role.id)
      return
    }
    // Re-read the list, then look only at characters that did not exist before.
    await companionStore.fetchRoles(userId)
    const created = companionStore.roles
      .filter((r) => !knownIds.has(r.id))
      .sort((a, b) => (b.id ?? 0) - (a.id ?? 0))[0]
    if (created) {
      await companionStore.setActiveRole(userId, created.id)
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

/* Search field. The base reset removes an input's border and outline, so both
   the edge and the focus state are restored here; the browser's own clear cross
   is hidden in favour of the button that also returns focus. */
.role-search {
  -webkit-appearance: none;
  appearance: none;
  transition: border-color 0.15s;
}

.role-search:focus-visible {
  border-color: rgba(255, 255, 255, 0.5);
}

.role-search::-webkit-search-cancel-button {
  display: none;
}

.role-search-clear:focus-visible,
.role-search-reset:focus-visible {
  outline: 2px solid rgba(255, 255, 255, 0.5);
  outline-offset: -2px;
}

/* The row's own button. Its hit area is stretched over the whole row, so a
   click on the padding still selects as it always did, while Adopt and Delete
   sit above it as siblings instead of buttons nested inside a button. The
   content is lifted over that area so the preview's tooltip still shows. */
.role-row-main::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 0;
  border-radius: 8px;
}

.role-row-main > * {
  position: relative;
  z-index: 1;
}

.role-row-main:focus-visible {
  outline: none;
}

.role-row-main:focus-visible::after {
  outline: 2px solid rgba(255, 255, 255, 0.5);
  outline-offset: -2px;
}

.role-row-action {
  position: relative;
  z-index: 1;
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

/* Invite picker rows: same quiet treatment as the seat rows above, so the
   dialog reads as part of the same list rather than a separate form. */
.invite-model-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 12px;
  border: 1px solid rgba(68, 71, 72, 0.4);
  border-radius: 8px;
  background: #1b1b1e;
  color: #e4e1e6;
  font-size: 14px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s, background-color 0.15s;
}

.invite-model-row:hover {
  border-color: rgba(255, 255, 255, 0.4);
  background: #232326;
}

.invite-model-row.is-active {
  border-color: rgba(255, 255, 255, 0.7);
  background: #2a2a2d;
  color: #ffffff;
}

.invite-model-row:focus-visible {
  outline: 2px solid rgba(255, 255, 255, 0.5);
  outline-offset: 2px;
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
