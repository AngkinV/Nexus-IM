<template>
  <div class="group-list">
    <div v-if="filteredGroups.length === 0" class="empty-state">
      <div class="empty-mark">
        <span class="material-symbols-outlined">group</span>
      </div>
      <p class="empty-title">{{ $t('group.noGroups') }}</p>
      <p class="empty-text">{{ $t('group.noGroupsDesc') }}</p>
    </div>

    <template v-else>
      <div
        v-for="group in filteredGroups"
        :key="group.id"
        class="group-item"
        :class="{ active: selectedId != null ? selectedId === group.id : chatStore.activeChat?.id === group.id }"
        @click="selectGroup(group)"
      >
        <div class="avatar-frame">
          <img
            v-if="group.avatar"
            class="avatar-img"
            :src="group.avatar"
            alt=""
          />
          <div v-else class="avatar-fallback">{{ getGroupInitials(group.name) }}</div>
          <span class="member-badge">{{ group.memberCount || 0 }}</span>
        </div>

        <div class="group-content">
          <div class="group-header">
            <span class="group-name">{{ group.name }}</span>
            <span class="group-time">{{ formatTime(group.lastMessageTime) }}</span>
          </div>
          <div class="group-footer">
            <span class="group-message">{{ group.lastMessage || $t('group.noMessages') }}</span>
            <span v-if="group.unreadCount > 0" class="unread-badge">
              {{ group.unreadCount > 99 ? '99+' : group.unreadCount }}
            </span>
          </div>
          <div class="group-meta">
            <span class="group-chip">
              <span v-if="group.isPrivate" class="material-symbols-outlined">lock</span>
              {{ group.memberCount || 0 }} {{ $t('group.members') }}
            </span>
            <div class="group-flags">
              <span v-if="chatStore.isChatMuted?.(group.id)" class="material-symbols-outlined">notifications_off</span>
              <span v-if="chatStore.isChatPinned(group.id)" class="material-symbols-outlined pinned">push_pin</span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useSettingsStore } from '@/stores/settings'
import dayjs from 'dayjs'

const settings = useSettingsStore()

const props = defineProps({
  searchQuery: {
    type: String,
    default: ''
  },
  selectedId: {
    type: [Number, String],
    default: null
  }
})

const emit = defineEmits(['select'])

const chatStore = useChatStore()

const filteredGroups = computed(() => {
  const groups = chatStore.groupChats
  if (!props.searchQuery) {
    return groups
  }
  const query = props.searchQuery.toLowerCase()
  return groups.filter(group =>
    group.name.toLowerCase().includes(query)
  )
})

const selectGroup = (group) => {
  // The parent decides what a selection means (detail card on desktop,
  // straight into the conversation on phones).
  emit('select', group)
}

const getGroupInitials = (name) => {
  if (!name) return 'G'
  return name.split(' ').map(word => word[0]).join('').toUpperCase().slice(0, 2)
}

const formatTime = (time) => {
  if (!time) return ''
  const date = dayjs(time)
  const now = dayjs()

  if (date.isSame(now, 'day')) {
    return settings.format(time)
  } else if (date.isSame(now.subtract(1, 'day'), 'day')) {
    return date.format('MM-DD')
  } else if (date.isSame(now, 'week')) {
    return date.format('ddd')
  } else {
    return date.format('MMM D')
  }
}
</script>

<style scoped>
/* Prism c3 group rows — exported Tailwind classes resolved to CSS. */
.group-list {
  padding: 4px 0;
}

/* ── Empty state ────────────────────────────────────────── */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 64px 24px;
  text-align: center;
}

.empty-mark {
  width: 52px;
  height: 52px;
  border-radius: 12px;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #6f7274;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
}

.empty-mark .material-symbols-outlined {
  font-size: 24px;
}

.empty-title {
  font-size: 14px;
  font-weight: 600;
  color: #e4e1e6;
  margin: 0 0 6px;
}

.empty-text {
  color: #8e9192;
  font-size: 14px;
  line-height: 1.6;
  margin: 0;
  max-width: 200px;
}

/* ── Group rows ─────────────────────────────────────────── */
.group-item {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px;
  border-radius: 8px;
  border: 1px solid transparent;
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.group-item:hover {
  background: #1b1b1e;
}

/* Selected: bg-surface-container + border-outline-variant/40 */
.group-item.active {
  background: #1f1f22;
  border-color: rgba(68, 71, 72, 0.4);
}

.avatar-frame {
  position: relative;
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  padding: 2px;
  border-radius: 4px;
  background: #2a2a2d;
  border: 1px solid rgba(68, 71, 72, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
}

.avatar-img {
  width: 100%;
  height: 100%;
  border-radius: 4px;
  object-fit: cover;
  filter: grayscale(100%);
}

.avatar-fallback {
  width: 100%;
  height: 100%;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #2a2a2d;
  color: #e4e1e6;
  font-size: 13px;
  font-weight: 500;
}

.member-badge {
  position: absolute;
  right: -4px;
  bottom: -4px;
  padding: 0 4px;
  border-radius: 4px;
  background: #353438;
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #c4c7c8;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 9px;
  line-height: 1.25;
}

.group-content {
  flex: 1;
  min-width: 0;
  padding-right: 4px;
}

.group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
}

.group-name {
  font-size: 14px;
  line-height: 18px;
  letter-spacing: 0.01em;
  font-weight: 500;
  color: #e4e1e6;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.15s ease;
}

.group-item:hover .group-name {
  color: #ffffff;
}

.group-item.active .group-name {
  color: #ffffff;
  font-weight: 600;
}

.group-time {
  flex-shrink: 0;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 12px;
  line-height: 16px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: rgba(196, 199, 200, 0.7);
}

.group-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  margin-top: 2px;
}

.group-message {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  line-height: 24px;
  color: #c4c7c8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.unread-badge {
  flex-shrink: 0;
  height: 16px;
  min-width: 16px;
  padding: 0 4px;
  border-radius: 9999px;
  background: #ffffff;
  color: #2f3131;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.group-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
}

.group-chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 6px;
  border-radius: 4px;
  background: #2a2a2d;
  color: #c4c7c8;
  font-size: 10px;
  line-height: 16px;
  font-weight: 500;
  letter-spacing: 0.02em;
}

.group-chip .material-symbols-outlined {
  font-size: 11px;
}

.group-flags {
  display: flex;
  align-items: center;
  gap: 4px;
  color: rgba(196, 199, 200, 0.7);
}

.group-flags .material-symbols-outlined {
  font-size: 14px;
}

.group-flags .pinned {
  color: #ffffff;
}

/* ── Mobile ─────────────────────────────────────────────── */
@media (max-width: 768px) {
  .group-list {
    padding: 2px 0;
  }

  .empty-state {
    padding: 48px 20px;
  }

  .group-item {
    padding: 7px;
    margin: 0 6px;
  }

  .avatar-frame {
    width: 32px;
    height: 32px;
  }

  .group-time {
    font-size: 10px;
  }
}
</style>
