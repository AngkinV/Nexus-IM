<template>
  <!-- Prism c2: contact detail pane. Layout and classes follow the exported
       screen; every value shown comes from the existing profile / contacts
       endpoints — sections the backend has no data for are left out. -->
  <div class="flex-1 flex flex-col bg-surface overflow-hidden relative min-h-0">
    <!-- Breadcrumb bar -->
    <div class="h-12 shrink-0 px-space-lg flex items-center justify-between border-b border-outline-variant/20 bg-surface">
      <div class="flex items-center gap-space-sm font-label-md text-label-md text-on-surface-variant min-w-0">
        <span class="text-on-surface-variant/60 shrink-0">{{ $t('leftPanel.contacts') }}</span>
        <span class="material-symbols-outlined text-[14px] text-on-surface-variant/40">chevron_right</span>
        <span class="text-primary font-medium truncate">{{ displayName }}</span>
      </div>
      <div class="flex items-center gap-1 shrink-0">
        <button :class="barBtn" :title="$t('contact.viewProfile')" type="button" @click="openProfile">
          <span class="material-symbols-outlined text-[17px]">open_in_new</span>
        </button>
        <button :class="barBtn" :title="$t('contact.removeContact')" type="button" @click="$emit('remove', contact)">
          <span class="material-symbols-outlined text-[17px]">person_remove</span>
        </button>
        <button :class="barBtn" :title="$t('common.close')" type="button" @click="$emit('close')">
          <span class="material-symbols-outlined text-[17px]">close</span>
        </button>
      </div>
    </div>

    <div class="detail-scroll flex-1 overflow-y-auto p-space-lg space-y-space-md">
      <!-- Identity card -->
      <div class="border border-outline-variant/30 rounded-lg bg-surface-container-lowest p-space-lg">
        <div class="flex flex-col md:flex-row items-start md:items-center justify-between gap-space-lg">
          <div class="flex items-center gap-space-lg min-w-0">
            <div class="relative shrink-0">
              <div class="w-20 h-20 rounded-lg p-0.5 border border-outline-variant/40 bg-surface-container">
                <img
                  v-if="avatarUrl && !avatarFailed"
                  class="w-full h-full rounded object-cover grayscale contrast-125"
                  :src="avatarUrl"
                  alt=""
                  @error="avatarFailed = true"
                />
                <div v-else class="detail-initial w-full h-full rounded" :data-initial="initial"></div>
              </div>
              <div class="absolute -bottom-1 -right-1 px-1.5 py-0.5 rounded bg-surface border border-outline-variant/40 flex items-center gap-1">
                <span class="w-1.5 h-1.5 rounded-full" :class="isOnline ? 'bg-primary' : 'bg-outline'"></span>
                <span class="font-code text-[11px]" :class="isOnline ? 'text-primary' : 'text-on-surface-variant'">
                  {{ isOnline ? $t('chat.online') : $t('chat.offline') }}
                </span>
              </div>
            </div>

            <div class="space-y-1 min-w-0">
              <div class="flex items-center gap-space-sm min-w-0">
                <h1 class="m-0 font-headline-lg text-headline-lg text-primary tracking-tight font-semibold truncate">{{ displayName }}</h1>
                <span class="font-headline-md text-headline-md text-on-surface-variant font-normal truncate">@{{ contact.username }}</span>
              </div>
              <div class="flex flex-wrap items-center gap-x-space-md gap-y-1 text-on-surface-variant font-body-md text-body-md">
                <div v-if="profile?.email" class="flex items-center gap-1 text-on-surface min-w-0">
                  <span class="material-symbols-outlined text-[16px] text-primary">alternate_email</span>
                  <span class="truncate">{{ profile.email }}</span>
                </div>
                <span v-if="profile?.email" class="text-outline-variant/40">/</span>
                <div class="flex items-center gap-1 text-on-surface-variant">
                  <span class="material-symbols-outlined text-[16px]">schedule</span>
                  <span>{{ presenceText }}</span>
                </div>
              </div>
              <div class="flex items-center gap-space-sm pt-0.5">
                <span class="inline-flex items-center gap-1 font-code text-code text-on-surface-variant/70">
                  <span class="material-symbols-outlined text-[14px]">badge</span>ID: {{ contact.id }}
                </span>
                <template v-if="joinedText">
                  <span class="text-outline-variant/40">•</span>
                  <span class="inline-flex items-center gap-1 font-code text-code text-on-surface-variant/70">
                    <span class="material-symbols-outlined text-[14px]">event</span>{{ $t('profile.joined') }}: {{ joinedText }}
                  </span>
                </template>
              </div>
            </div>
          </div>

          <!-- Actions -->
          <div class="flex flex-wrap items-center gap-2 self-stretch md:self-auto shrink-0">
            <button
              class="flex items-center justify-center gap-1.5 px-4 py-2 rounded border-0 bg-white text-zinc-900 hover:bg-zinc-200 font-label-md text-label-md font-medium transition-all shadow-sm active:scale-[0.98] cursor-pointer"
              type="button"
              @click="$emit('message', contact)"
            >
              <span class="material-symbols-outlined text-[17px]">chat_bubble</span>
              <span>{{ $t('contact.message') }}</span>
            </button>
            <button :class="ghostBtn" type="button" @click="$emit('call', contact, 'audio')">
              <span class="material-symbols-outlined text-[17px] text-zinc-400">call</span>
              <span>{{ $t('chat.audioCall') }}</span>
            </button>
            <button :class="ghostBtn" type="button" @click="$emit('call', contact, 'video')">
              <span class="material-symbols-outlined text-[17px] text-zinc-400">videocam</span>
              <span>{{ $t('chat.videoCall') }}</span>
            </button>
            <a
              v-if="profile?.email"
              class="w-9 h-9 rounded border border-solid border-zinc-700 hover:border-zinc-500 bg-surface-container-lowest text-zinc-300 hover:text-white hover:bg-zinc-800 flex items-center justify-center transition-colors no-underline"
              :href="'mailto:' + profile.email"
              :title="$t('auth.email')"
            >
              <span class="material-symbols-outlined text-[17px]">mail</span>
            </a>
          </div>
        </div>

        <div
          v-if="profile?.bio"
          class="mt-space-md pt-3 border-t border-outline-variant/20 flex items-center gap-2 text-on-surface"
        >
          <span class="material-symbols-outlined text-[16px] text-primary shrink-0">psychology</span>
          <span class="font-body-md text-body-md min-w-0">
            {{ $t('profile.bio') }}: <strong class="text-primary font-medium">{{ profile.bio }}</strong>
          </span>
        </div>
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-12 gap-space-md">
        <!-- Contact info -->
        <div class="lg:col-span-7 flex flex-col gap-space-md min-w-0">
          <div :class="card">
            <div class="flex items-center justify-between pb-1 border-b border-outline-variant/20">
              <span class="font-headline-md text-headline-md text-primary font-medium">{{ $t('contact.detailInfo') }}</span>
              <span v-if="loading" class="font-code text-label-sm text-on-surface-variant tracking-wider">{{ $t('common.loading') }}</span>
            </div>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-space-sm pt-1">
              <div v-for="tile in infoTiles" :key="tile.label" :class="tileCls">
                <div class="w-7 h-7 rounded bg-surface-container-high text-primary flex items-center justify-center shrink-0">
                  <span class="material-symbols-outlined text-[16px]">{{ tile.icon }}</span>
                </div>
                <div class="flex-1 min-w-0">
                  <span class="block font-label-sm text-label-sm text-on-surface-variant/60">{{ tile.label }}</span>
                  <span class="font-code text-code text-primary truncate select-all block">{{ tile.value }}</span>
                </div>
              </div>
            </div>
          </div>

          <div v-if="socialEntries.length" :class="card">
            <div class="flex items-center justify-between pb-1 border-b border-outline-variant/20">
              <span class="font-headline-md text-headline-md text-primary font-medium">{{ $t('profile.socialLinks') }}</span>
              <span class="font-code text-label-sm text-on-surface-variant/60">{{ socialEntries.length }}</span>
            </div>
            <div class="space-y-1.5 pt-1">
              <a
                v-for="[platform, url] in socialEntries"
                :key="platform"
                :class="rowCls + ' no-underline'"
                :href="url"
                target="_blank"
                rel="noopener noreferrer"
              >
                <div :class="rowIcon">
                  <span class="material-symbols-outlined text-[17px]">link</span>
                </div>
                <div class="flex-1 min-w-0">
                  <span class="block font-label-md text-label-md text-primary truncate group-hover:underline">{{ platform }}</span>
                  <p class="m-0 font-code text-[11px] text-on-surface-variant/70 truncate">{{ url }}</p>
                </div>
              </a>
            </div>
          </div>
        </div>

        <!-- Mutual contacts -->
        <div class="lg:col-span-5 flex flex-col gap-space-md min-w-0">
          <div :class="card">
            <div class="flex items-center justify-between pb-1 border-b border-outline-variant/20">
              <span class="font-headline-md text-headline-md text-primary font-medium">{{ $t('profile.mutualContacts') }}</span>
              <span class="px-2 py-0.5 rounded bg-surface-container-high text-primary font-code text-label-sm border border-outline-variant/30">
                {{ mutual.length }}
              </span>
            </div>
            <div class="space-y-1.5 pt-1">
              <p v-if="!mutual.length" class="m-0 py-2 font-body-md text-body-md text-on-surface-variant/70">
                {{ $t('contact.noMutual') }}
              </p>
              <div v-for="m in mutual" :key="m.id" :class="rowCls" @click="$emit('select', m)">
                <div :class="rowIcon">
                  <span class="font-label-md text-label-md">{{ (m.nickname || m.username || '?').charAt(0) }}</span>
                </div>
                <div class="flex-1 min-w-0">
                  <span class="block font-label-md text-label-md text-primary truncate group-hover:underline">{{ m.nickname || m.username }}</span>
                  <p class="m-0 font-code text-[11px] text-on-surface-variant/70 truncate">@{{ m.username }}</p>
                </div>
              </div>
            </div>
            <div class="mt-space-sm pt-2 flex items-center justify-between text-on-surface-variant/70 font-code text-label-sm border-t border-outline-variant/20">
              <span class="flex items-center gap-1.5">
                <span class="material-symbols-outlined text-[13px] text-primary">lock</span>
                <span>{{ $t('chat.encryptReady') }}</span>
              </span>
              <span>#{{ contact.id }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import { userAPI, contactAPI, resolveFileUrl } from '@/services/api'
import { useUserStore } from '@/stores/user'

dayjs.extend(relativeTime)

const props = defineProps({
  contact: { type: Object, required: true }
})
defineEmits(['message', 'call', 'remove', 'close', 'select'])

const { t } = useI18n()
const router = useRouter()
const userStore = useUserStore()

// Shared class strings, lifted verbatim from the exported screen.
const barBtn = 'w-7 h-7 rounded border-0 bg-transparent hover:bg-surface-container text-on-surface-variant hover:text-primary flex items-center justify-center transition-colors cursor-pointer'
const ghostBtn = 'flex items-center justify-center gap-1.5 px-3.5 py-2 rounded border border-solid border-zinc-700 hover:border-zinc-500 bg-surface-container-lowest text-zinc-300 hover:text-white hover:bg-zinc-800 font-label-md text-label-md transition-all active:scale-[0.98] cursor-pointer'
const card = 'border border-outline-variant/30 rounded-lg bg-surface-container-lowest p-space-md space-y-space-sm'
const tileCls = 'p-3 rounded bg-surface-container border border-outline-variant/20 flex items-start gap-2.5'
const rowCls = 'p-2 rounded bg-surface-container hover:bg-surface-container-high border border-outline-variant/20 flex items-center gap-2.5 transition-colors cursor-pointer group'
const rowIcon = 'w-8 h-8 rounded bg-surface-container-highest border border-outline-variant/30 text-primary flex items-center justify-center shrink-0'

const profile = ref(null)
const mutual = ref([])
const loading = ref(false)
const avatarFailed = ref(false)

const displayName = computed(() => props.contact.nickname || props.contact.username || '')
const initial = computed(() => displayName.value.trim().charAt(0) || '?')
const avatarUrl = computed(() => {
  const raw = profile.value?.avatarUrl || props.contact.avatar || props.contact.avatarUrl
  return raw ? resolveFileUrl(raw) : ''
})
const isOnline = computed(() => !!props.contact.isOnline)
const lastSeen = computed(() => profile.value?.lastSeen || props.contact.lastSeen)

const presenceText = computed(() => {
  if (isOnline.value) return t('chat.activeNow')
  return lastSeen.value ? `${t('chat.lastSeen')} ${dayjs(lastSeen.value).fromNow()}` : t('chat.offline')
})
const joinedText = computed(() =>
  profile.value?.createdAt ? dayjs(profile.value.createdAt).format('YYYY-MM-DD') : ''
)

const infoTiles = computed(() => {
  const p = profile.value || {}
  return [
    { icon: 'person', label: t('auth.username'), value: '@' + props.contact.username },
    p.email && { icon: 'alternate_email', label: t('auth.email'), value: p.email },
    p.phone && { icon: 'call', label: t('auth.phone'), value: p.phone },
    lastSeen.value && !isOnline.value && {
      icon: 'history',
      label: t('profile.lastSeen'),
      value: dayjs(lastSeen.value).format('YYYY-MM-DD HH:mm')
    },
    p.contactCount != null && { icon: 'person_book', label: t('profile.contacts'), value: p.contactCount },
    p.groupCount != null && { icon: 'group', label: t('profile.groups'), value: p.groupCount }
  ].filter(Boolean)
})

const socialEntries = computed(() =>
  Object.entries(profile.value?.socialLinks || {}).filter(([, url]) => !!url)
)

const openProfile = () => router.push(`/user/${props.contact.id}`)

// Load the viewer-scoped profile + mutual contacts whenever the selection
// changes; a late response for a previous contact is dropped.
let requestId = 0
const load = async () => {
  const id = props.contact.id
  const viewerId = userStore.currentUser?.id
  const current = ++requestId
  profile.value = null
  mutual.value = []
  avatarFailed.value = false
  if (!id || !viewerId) return
  loading.value = true
  const [p, m] = await Promise.allSettled([
    userAPI.getUserProfileForViewer(id, viewerId),
    contactAPI.getMutualContacts(viewerId, id)
  ])
  if (current !== requestId) return
  profile.value = p.status === 'fulfilled' ? p.value.data : null
  mutual.value = m.status === 'fulfilled' ? m.value.data || [] : []
  loading.value = false
}
watch(() => props.contact.id, load, { immediate: true })
</script>

<style scoped>
.detail-scroll {
  scrollbar-width: none;
}

.detail-scroll::-webkit-scrollbar {
  display: none;
}

.detail-initial {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #2a2a2d;
  color: #ffffff;
  font-weight: 600;
}

.detail-initial[data-initial]::before {
  font-size: 28px;
}
</style>
