<template>
  <!-- Prism c3: group detail pane. Layout and classes follow the exported
       screen; members, roles and the pin / mute state come from the existing
       group endpoints and chat store. Sections with no backend data (shared
       files, activity chart, announcement history) are left out. -->
  <div class="flex-1 flex flex-col bg-surface-container-lowest overflow-hidden relative min-h-0">
    <!-- Header -->
    <div class="shrink-0 p-space-lg pb-space-md border-b border-outline-variant/20 bg-surface-container-low/40 flex flex-col gap-space-md">
      <div class="flex items-start justify-between flex-wrap gap-space-sm">
        <div class="flex items-center gap-space-md min-w-0">
          <div class="relative w-14 h-14 shrink-0 rounded bg-surface-container-high p-1 flex items-center justify-center border border-outline-variant/40">
            <IdentityAvatar :src="group.avatarUrl || group.avatar" :entity-id="group.id" :label="group.name" kind="group" class="w-full h-full rounded object-cover" />
            <span class="absolute -bottom-1 -right-1 px-1 rounded bg-surface-container-highest border border-outline-variant/30 text-on-surface-variant font-code text-[9px] leading-tight">
              {{ memberTotal }}
            </span>
          </div>
          <div class="min-w-0">
            <div class="flex items-center gap-space-sm flex-wrap">
              <h2 class="m-0 font-headline-lg text-headline-lg tracking-tight text-primary font-semibold truncate">{{ group.name }}</h2>
              <span class="px-2 py-0.5 rounded-full bg-surface-container-high text-on-surface font-label-sm text-label-sm border border-outline-variant/40">
                {{ group.isPrivate ? $t('group.privateGroup') : $t('group.publicGroup') }}
              </span>
              <span class="px-2 py-0.5 rounded-full bg-surface-container-high text-on-surface-variant font-code text-code">ID: {{ group.id }}</span>
            </div>
            <p class="m-0 font-body-md text-body-md text-on-surface-variant mt-0.5 flex items-center gap-space-sm flex-wrap">
              <template v-if="owner">
                <span>{{ $t('group.owner') }}: <strong class="text-primary font-medium">{{ owner.nickname || owner.username }}</strong></span>
                <span>•</span>
              </template>
              <template v-if="createdText">
                <span>{{ $t('group.createdOn', { date: createdText }) }}</span>
                <span>•</span>
              </template>
              <span class="text-primary font-medium">{{ $t('group.memberTotal', { n: memberTotal }) }}</span>
            </p>
          </div>
        </div>

        <div class="flex items-center gap-space-xs shrink-0">
          <button
            class="flex items-center gap-space-xs px-4 py-2 rounded border-0 bg-primary text-on-primary font-label-md text-label-md font-medium hover:bg-primary-container transition-all active:scale-95 shadow-sm cursor-pointer"
            type="button"
            @click="$emit('enter', group)"
          >
            <span class="material-symbols-outlined text-[18px]">chat</span>
            <span>{{ $t('group.enterChat') }}</span>
          </button>
          <button :class="iconBtn" :title="$t('common.close')" type="button" @click="$emit('close')">
            <span class="material-symbols-outlined text-[18px]">close</span>
          </button>
        </div>
      </div>

      <!-- Group description (the export's pinned-notice strip) -->
      <div
        v-if="description"
        class="rounded bg-surface-container-low border border-outline-variant/30 p-space-sm px-space-md flex items-center gap-space-sm min-w-0"
      >
        <div class="w-8 h-8 rounded bg-surface-container-high border border-outline-variant/40 flex items-center justify-center text-primary shrink-0">
          <span class="material-symbols-outlined text-[18px]">campaign</span>
        </div>
        <div class="min-w-0">
          <div class="font-label-md text-label-md text-primary font-medium">{{ $t('group.aboutGroup') }}</div>
          <p class="m-0 font-body-md text-body-md text-on-surface-variant truncate">{{ description }}</p>
        </div>
      </div>
    </div>

    <div class="detail-scroll flex-1 overflow-y-auto p-space-lg grid grid-cols-1 xl:grid-cols-12 gap-space-lg content-start">
      <!-- Members -->
      <div class="xl:col-span-7 flex flex-col gap-space-md min-w-0">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-space-xs">
            <span class="font-headline-md text-headline-md text-primary font-medium">{{ $t('group.memberList') }}</span>
            <span class="font-code text-label-sm px-2 py-0.5 rounded-full bg-surface-container-high text-on-surface-variant border border-outline-variant/30">
              {{ memberTotal }}
            </span>
          </div>
          <span v-if="loading" class="font-label-sm text-label-sm text-on-surface-variant">{{ $t('common.loading') }}</span>
          <span v-else-if="loadFailed" class="font-label-sm text-label-sm text-on-surface-variant">{{ $t('group.loadMembersFailed') }}</span>
        </div>

        <div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-space-xs">
          <div
            v-for="m in sortedMembers"
            :key="m.id"
            class="flex flex-col items-center text-center p-space-sm rounded bg-surface-container-low border border-outline-variant/20 hover:border-outline-variant/60 transition-colors relative cursor-pointer min-h-[96px] min-w-0"
            @click="openMember(m)"
          >
            <span
              v-if="m.role === 'owner' || m.role === 'admin'"
              class="absolute top-1.5 right-1.5 px-1.5 rounded bg-surface-container-highest border border-outline-variant/40 font-code text-[9px] font-medium z-10"
              :class="m.role === 'owner' ? 'text-primary' : 'text-on-surface-variant'"
            >{{ m.role === 'owner' ? $t('group.owner') : $t('group.admin') }}</span>
            <div class="relative w-10 h-10 shrink-0">
              <IdentityAvatar :src="m.avatarUrl || m.avatar" :entity-id="m.id" :label="m.nickname || m.username" kind="user" class="w-10 h-10 rounded-full object-cover" />
              <PresenceDot class="absolute bottom-0 right-0 w-2.5 h-2.5 ring-2 ring-surface-container-lowest" :value="m.isOnline" />
            </div>
            <span class="font-label-md text-label-md text-on-surface font-medium mt-1.5 truncate max-w-full">{{ m.nickname || m.username }}</span>
            <span class="font-label-sm text-[10px] text-on-surface-variant/70 truncate max-w-full">@{{ m.username }}</span>
          </div>
        </div>
      </div>

      <!-- Preferences -->
      <div class="xl:col-span-5 flex flex-col gap-space-md min-w-0">
        <div class="p-space-md rounded bg-surface-container-low border border-outline-variant/20 flex flex-col gap-space-sm">
          <span class="font-label-md text-label-md text-primary font-semibold">{{ $t('group.preferences') }}</span>

          <div v-for="pref in prefs" :key="pref.key" class="flex items-center justify-between py-1.5 gap-space-sm">
            <div class="flex items-center gap-space-sm min-w-0">
              <div
                class="w-8 h-8 shrink-0 rounded bg-surface-container-high flex items-center justify-center"
                :class="pref.on ? 'text-primary' : 'text-on-surface-variant'"
              >
                <span class="material-symbols-outlined text-[18px]">{{ pref.icon }}</span>
              </div>
              <div class="min-w-0">
                <div class="font-label-md text-label-md text-on-surface font-medium">{{ pref.title }}</div>
                <div class="font-label-sm text-[11px] text-on-surface-variant/70">{{ pref.desc }}</div>
              </div>
            </div>
            <button
              class="relative inline-flex h-6 w-11 shrink-0 items-center rounded-full border-0 p-0.5 transition-colors focus:outline-none cursor-pointer"
              :class="pref.on ? 'bg-primary' : 'bg-surface-container-highest'"
              type="button"
              role="switch"
              :aria-checked="pref.on"
              :aria-label="pref.title"
              @click="pref.toggle"
            >
              <span
                class="inline-block h-5 w-5 transform rounded-full shadow transition-transform"
                :class="pref.on ? 'translate-x-5 bg-surface' : 'translate-x-0 bg-on-surface-variant'"
              ></span>
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { membershipSignature, currentGroupRecord } from '@/components/identity/displayFacts.mjs'
import { useContactStore } from '@/stores/contact'
import { memberRows, requestGate } from '@/components/identity/presentation.mjs'
import { onBeforeUnmount } from 'vue'
import IdentityAvatar from '@/components/identity/IdentityAvatar.vue'
import PresenceDot from '@/components/identity/PresenceDot.vue'
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'
import { groupAPI } from '@/services/api'
import { useChatStore } from '@/stores/chat'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  group: { type: Object, required: true }
})
defineEmits(['enter', 'close'])

const { t } = useI18n()
const router = useRouter()
const chatStore = useChatStore()
const userStore = useUserStore()

const iconBtn = 'flex items-center justify-center w-8 h-8 rounded bg-surface-container hover:bg-surface-container-high text-on-surface-variant hover:text-primary transition-colors border border-solid border-outline-variant/30 cursor-pointer'

const contactStore = useContactStore()
const members = ref([])
const identityMembers = computed(() => memberRows(members.value, id => contactStore.getUserIdentity(id)))
const info = ref(null)
const loading = ref(false)
const loadFailed = ref(false)

const ROLE_ORDER = { owner: 0, admin: 1 }
const sortedMembers = computed(() =>
  [...identityMembers.value].sort(
    (a, b) => (ROLE_ORDER[a.role] ?? 2) - (ROLE_ORDER[b.role] ?? 2) || Number(!!b.isOnline) - Number(!!a.isOnline)
  )
)
const owner = computed(() => identityMembers.value.find((m) => m.role === 'owner'))
const memberTotal = computed(() => info.value?.memberCount ?? props.group.memberCount ?? members.value.length)
const description = computed(() => info.value?.description || props.group.description || '')
const createdText = computed(() => {
  const at = info.value?.createdAt || props.group.createdAt
  return at ? dayjs(at).format('YYYY.MM.DD') : ''
})

const prefs = computed(() => [
  {
    key: 'pin',
    icon: 'push_pin',
    title: t('group.stickyOnTop'),
    desc: t('group.stickyDesc'),
    on: chatStore.isChatPinned(props.group.id),
    toggle: () => chatStore.togglePinChat(props.group.id)
  },
  {
    key: 'mute',
    icon: 'notifications',
    title: t('group.muteNotifications'),
    desc: t('group.muteDesc'),
    on: chatStore.isChatMuted(props.group.id),
    toggle: () => chatStore.toggleMuteChat(props.group.id)
  }
])

const openMember = (m) => router.push(`/user/${m.id}`)

// Reload members + group info on selection change; drop stale responses.
const gate = requestGate()
onBeforeUnmount(() => gate.dispose())
const load = async () => {
  const id = props.group.id
  const current = gate.begin()
  const identityToken = contactStore.captureIdentityRevision()
  members.value = []
  loading.value = false
  info.value = null
  loadFailed.value = false
  if (!id || userStore.currentUser?.id == null) return
  loading.value = true
  const [m, g] = await Promise.allSettled([groupAPI.getGroupMembers(id), groupAPI.getGroupById(id)])
  if (!gate.current(current) || props.group.id !== id || !contactStore.isIdentityRevisionCurrent(identityToken)) return
  const rows = m.status === 'fulfilled' ? m.value.data || [] : []
  if (m.status === 'fulfilled' && !contactStore.ingestUserSnapshots(rows, identityToken)) return
  members.value = rows
  loadFailed.value = m.status !== 'fulfilled'
  info.value = g.status === 'fulfilled' ? g.value.data : null
  loading.value = false
}
const membershipFacts = computed(() => membershipSignature(currentGroupRecord(chatStore, props.group.id)))
watch(() => [userStore.currentUser?.id, props.group.id], load, { immediate: true, flush: 'sync' })
watch(() => JSON.stringify([userStore.currentUser?.id, props.group.id, membershipFacts.value]), (next, previous) => {
  const a = JSON.parse(next), b = JSON.parse(previous)
  if (a[0] === b[0] && a[1] === b[1]) load()
})
</script>

<style scoped>
.detail-scroll {
  scrollbar-width: none;
}

.detail-scroll::-webkit-scrollbar {
  display: none;
}
</style>
