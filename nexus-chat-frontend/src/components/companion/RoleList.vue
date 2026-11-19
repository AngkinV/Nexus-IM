<template>
  <!-- Prism pane: every companion role the user has, the active one pinned on top.
       Mirrors the AI session list so the two canvases read the same way. -->
  <div class="flex flex-col h-full min-h-0">
    <div class="p-space-md flex flex-col gap-space-sm border-b border-outline-variant/20 shrink-0">
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-2 min-w-0">
          <span class="material-symbols-outlined text-primary text-[18px]">nightlife</span>
          <span class="font-headline-md text-headline-md tracking-tight text-primary font-medium truncate">{{ $t('leftPanel.navCompanion') }}</span>
        </div>
        <div class="flex items-center gap-1 shrink-0">
          <span
            class="font-code text-[11px] px-1.5 py-0.5 rounded border border-outline-variant/30 bg-surface-container-low text-on-surface-variant"
          >{{ $t('agent.history.count', { n: companionStore.roles.length }) }}</span>
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
        class="w-full flex items-center justify-between py-2 px-3 rounded-lg bg-surface-container-low border border-outline-variant/40 text-primary hover:bg-surface-container hover:border-outline-variant transition-all duration-150 active:scale-[0.99]"
        type="button"
        :title="$t('settings.roleAndModel')"
        @click="$router.push('/settings')"
      >
        <div class="flex items-center gap-2">
          <span class="material-symbols-outlined text-[18px] text-primary">tune</span>
          <span class="font-label-md text-label-md font-medium">{{ $t('companion.roleManage') }}</span>
        </div>
        <kbd class="px-1.5 py-0.5 rounded bg-surface-container-high text-on-surface-variant font-code text-[11px]">⌘,</kbd>
      </button>
    </div>

    <div class="role-scroll flex-1 min-h-0 overflow-y-auto px-2 py-3 space-y-4">
      <div class="space-y-1">
        <div class="px-3 py-1 flex items-center justify-between text-on-surface-variant/60 font-label-sm text-label-sm uppercase tracking-wider">
          <span>{{ $t('companion.roleList') }}</span>
          <span class="font-code text-[10px]">{{ $t('companion.roleCount', { n: companionStore.roles.length }) }}</span>
        </div>

        <div
          v-for="role in orderedRoles"
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
              class="font-label-md text-label-md truncate"
              :class="role.id === companionStore.activeRoleId ? 'text-primary font-medium' : 'group-hover:text-primary'"
            >{{ role.name || $t('companion.defaultName') }}</div>
            <div
              class="font-body-md text-[12px] truncate mt-0.5"
              :class="role.id === companionStore.activeRoleId ? 'text-on-surface-variant' : 'text-on-surface-variant/70'"
            >{{ role.persona || role.description || $t('companion.defaultStatus') }}</div>
          </div>
          <span
            v-if="role.id === companionStore.activeRoleId"
            class="material-symbols-outlined text-[15px] text-primary shrink-0 mt-1"
          >chat_bubble</span>
        </div>
      </div>

      <div v-if="!companionStore.roles.length" class="px-3 py-6 text-center font-body-md text-[12px] text-on-surface-variant/60">
        {{ $t('companion.emptyRole') }}
      </div>
    </div>

    <div class="p-3 bg-surface-container-low/70 border-t border-outline-variant/20 flex items-center justify-between shrink-0">
      <div class="flex items-center gap-2.5 min-w-0">
        <div
          class="w-7 h-7 rounded bg-surface-container-high border border-outline-variant/30 flex items-center justify-center text-primary shrink-0"
        >
          <span class="material-symbols-outlined text-[16px]">person_heart</span>
        </div>
        <div class="min-w-0">
          <div class="font-label-md text-label-md text-primary truncate">
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
import { computed, inject, onMounted } from 'vue'
import { useCompanionStore } from '@/stores/companion'
import { useUserStore } from '@/stores/user'

const companionStore = useCompanionStore()
const toggleList = inject('toggleList', () => {})
const userStore = useUserStore()

// The active role leads the list so the conversation below always matches the
// top row.
const orderedRoles = computed(() => {
  const active = companionStore.roles.find((r) => r.id === companionStore.activeRoleId)
  return active
    ? [active, ...companionStore.roles.filter((r) => r.id !== active.id)]
    : companionStore.roles
})

onMounted(async () => {
  if (!userStore.currentUser?.id) return
  await companionStore.ensureInitialized(userStore.currentUser.id)
})

async function selectRole(role) {
  if (role.id === companionStore.activeRoleId) return
  await companionStore.setActiveRole(userStore.currentUser.id, role.id)
}
</script>

<style scoped>
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
