<template>
  <div class="group-list">
    <div v-if="filteredGroups.length === 0" class="empty-state">
      <div class="empty-icon-wrapper">
        <div class="empty-icon-glow"></div>
        <div class="empty-icon">
          <el-icon :size="48"><User /></el-icon>
        </div>
      </div>
      <h3 class="empty-title">{{ $t('group.noGroups') }}</h3>
      <p class="empty-text">Join a group or create a new one to start chatting with friends</p>
    </div>

    <template v-else>
      <div
        v-for="group in filteredGroups"
        :key="group.id"
        class="group-item"
        :class="{ active: chatStore.activeChat?.id === group.id }"
        @click="selectGroup(group)"
      >
        <div class="group-avatar">
          <el-avatar :size="50" :src="group.avatar || defaultGroupAvatar">
            <template v-if="!group.avatar">
              {{ getGroupInitials(group.name) }}
            </template>
          </el-avatar>
          <span v-if="group.isPrivate" class="private-badge">
            <el-icon :size="12"><Lock /></el-icon>
          </span>
        </div>

        <div class="group-content">
          <div class="group-header">
            <span class="group-name">{{ group.name }}</span>
            <span class="group-time">{{ formatTime(group.lastMessageTime) }}</span>
          </div>
          <div class="group-footer">
            <span class="group-message">{{ group.lastMessage || $t('group.noMessages') }}</span>
            <span v-if="group.unreadCount > 0" class="unread-badge">{{ group.unreadCount }}</span>
          </div>
          <div class="group-meta">
            <el-icon><User /></el-icon>
            <span>{{ group.memberCount || 0 }} {{ $t('group.members') }}</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { User, Lock } from '@element-plus/icons-vue'
import { useChatStore } from '@/stores/chat'
import dayjs from 'dayjs'

const props = defineProps({
  searchQuery: {
    type: String,
    default: ''
  }
})

const emit = defineEmits(['select'])

const chatStore = useChatStore()
const defaultGroupAvatar = ''

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
  chatStore.setActiveChat(group)
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
    return date.format('HH:mm')
  } else if (date.isSame(now.subtract(1, 'day'), 'day')) {
    return 'Yesterday'
  } else if (date.isSame(now, 'week')) {
    return date.format('ddd')
  } else {
    return date.format('MMM D')
  }
}
</script>

<style scoped>
.group-list {
  padding: 5px 0;
}

/* Empty State - Blue-Green Theme */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 400px;
  padding: 0 24px;
  text-align: center;
}

.empty-icon-wrapper {
  position: relative;
  margin-bottom: 24px;
}

.empty-icon-glow {
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, rgba(14, 165, 233, 0.2) 0%, rgba(16, 185, 129, 0.2) 100%);
  border-radius: 50%;
  filter: blur(24px);
  animation: pulse-slow 4s ease-in-out infinite;
}

@keyframes pulse-slow {
  0%, 100% {
    opacity: 0.4;
    transform: scale(1);
  }
  50% {
    opacity: 0.7;
    transform: scale(1.15);
  }
}

.empty-icon {
  width: 96px;
  height: 96px;
  border-radius: 50%;
  background: linear-gradient(135deg, rgba(14, 165, 233, 0.05) 0%, rgba(16, 185, 129, 0.05) 100%);
  display: flex;
  justify-content: center;
  align-items: center;
  position: relative;
  z-index: 10;
  transition: transform 0.3s ease;
}

.empty-icon:hover {
  transform: scale(1.05);
}

.empty-icon .el-icon {
  font-size: 48px;
  background: linear-gradient(135deg, #0ea5e9 0%, #10b981 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.empty-title {
  font-size: 16px;
  font-weight: 700;
  color: #1e293b;
  margin: 0 0 8px 0;
  letter-spacing: -0.01em;
}

.empty-text {
  color: #64748b;
  font-size: 14px;
  margin: 0 0 32px 0;
  max-width: 200px;
  line-height: 1.6;
}

/* Group Item */
.group-item {
  display: flex;
  gap: 12px;
  padding: 12px 16px;
  margin: 4px 8px;
  cursor: pointer;
  border-radius: 16px;
  transition: all 0.2s ease;
}

.group-item:hover {
  background: rgba(14, 165, 233, 0.06);
}

.group-item:active {
  transform: scale(0.99);
}

.group-item.active {
  background: linear-gradient(135deg, rgba(14, 165, 233, 0.12) 0%, rgba(16, 185, 129, 0.08) 100%);
}

.group-avatar {
  position: relative;
}

.group-avatar .el-avatar {
  background: linear-gradient(135deg, #0ea5e9 0%, #10b981 100%);
  color: white;
  font-weight: 600;
  font-size: 16px;
  box-shadow: 0 4px 12px -2px rgba(14, 165, 233, 0.3);
}

.private-badge {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: linear-gradient(135deg, #0ea5e9 0%, #10b981 100%);
  color: white;
  display: flex;
  justify-content: center;
  align-items: center;
  border: 2px solid white;
  box-shadow: 0 2px 6px rgba(14, 165, 233, 0.3);
}

.group-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.group-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.group-name {
  font-weight: 600;
  font-size: 15px;
  color: #1e293b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.2s ease;
}

.group-item:hover .group-name {
  color: #0ea5e9;
}

.group-time {
  font-size: 12px;
  color: #94a3b8;
  flex-shrink: 0;
  font-weight: 500;
}

.group-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.group-message {
  font-size: 13px;
  color: #64748b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}

.unread-badge {
  background: linear-gradient(135deg, #0ea5e9 0%, #10b981 100%);
  color: white;
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 10px;
  min-width: 20px;
  text-align: center;
  box-shadow: 0 2px 8px rgba(14, 165, 233, 0.3);
}

.group-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: #94a3b8;
}

.group-meta .el-icon {
  font-size: 14px;
  color: #0ea5e9;
}

/* Dark Mode - Blue-Green Theme */
[data-theme="dark"] .empty-state {
  background: transparent;
}

[data-theme="dark"] .empty-icon {
  background: linear-gradient(135deg, rgba(14, 165, 233, 0.08) 0%, rgba(16, 185, 129, 0.08) 100%);
}

[data-theme="dark"] .empty-icon-glow {
  background: linear-gradient(135deg, rgba(14, 165, 233, 0.15) 0%, rgba(16, 185, 129, 0.15) 100%);
}

[data-theme="dark"] .empty-title {
  color: #e2e8f0;
}

[data-theme="dark"] .empty-text {
  color: #94a3b8;
}

[data-theme="dark"] .group-item:hover {
  background: rgba(14, 165, 233, 0.1);
}

[data-theme="dark"] .group-item.active {
  background: linear-gradient(135deg, rgba(14, 165, 233, 0.15) 0%, rgba(16, 185, 129, 0.1) 100%);
}

[data-theme="dark"] .group-name {
  color: #e2e8f0;
}

[data-theme="dark"] .group-item:hover .group-name {
  color: #38bdf8;
}

[data-theme="dark"] .group-message {
  color: #94a3b8;
}

[data-theme="dark"] .private-badge {
  border-color: #1e293b;
}

/* Mobile Responsive Styles */
@media (max-width: 768px) {
  .group-list {
    padding: 4px 0;
  }

  .empty-state {
    height: 350px;
    padding: 0 20px;
  }

  .empty-icon {
    width: 80px;
    height: 80px;
  }

  .empty-icon .el-icon {
    font-size: 40px;
  }

  .empty-title {
    font-size: 15px;
  }

  .empty-text {
    font-size: 13px;
    max-width: 180px;
  }

  .group-item {
    gap: 10px;
    padding: 10px 12px;
    margin: 3px 6px;
    border-radius: 14px;
  }

  .group-avatar .el-avatar {
    width: 46px !important;
    height: 46px !important;
    font-size: 14px !important;
  }

  .group-name {
    font-size: 14px;
  }

  .group-time {
    font-size: 11px;
  }

  .group-message {
    font-size: 12px;
  }

  .unread-badge {
    font-size: 10px;
    padding: 2px 6px;
  }

  .group-meta {
    font-size: 11px;
  }

  .group-meta .el-icon {
    font-size: 12px;
  }
}
</style>
