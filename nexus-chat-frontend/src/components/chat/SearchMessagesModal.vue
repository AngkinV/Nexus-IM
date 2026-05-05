<template>
  <el-dialog
    v-model="visible"
    :title="$t('group.searchHistory')"
    width="420px"
    :close-on-click-modal="false"
    append-to-body
    class="search-messages-dialog"
    @close="handleClose"
  >
    <div class="search-container">
      <!-- Search Input -->
      <div class="search-box">
        <el-input
          v-model="searchQuery"
          :placeholder="$t('group.enterKeyword')"
          prefix-icon="Search"
          clearable
          @input="handleSearch"
        />
      </div>

      <!-- Search Results -->
      <div class="results-container" v-loading="loading">
        <template v-if="searchQuery && searchResults.length > 0">
          <div class="results-header">
            {{ searchResults.length }} {{ $t('group.searchMessages') }}
          </div>
          <div class="results-list">
            <div
              v-for="message in searchResults"
              :key="message.id"
              class="message-item"
              @click="handleMessageClick(message)"
            >
              <div class="message-avatar">
                <el-avatar :size="36" :src="message.senderAvatar || defaultAvatar" />
              </div>
              <div class="message-content">
                <div class="message-header">
                  <span class="sender-name">{{ message.senderName }}</span>
                  <span class="message-time">{{ formatTime(message.createdAt) }}</span>
                </div>
                <div class="message-text" v-html="highlightText(message.content)"></div>
              </div>
            </div>
          </div>
        </template>

        <!-- Empty State -->
        <div v-else-if="searchQuery && !loading" class="empty-state">
          <span class="material-icons-round">search_off</span>
          <p>{{ $t('group.noMessagesFound') }}</p>
        </div>

        <!-- Initial State -->
        <div v-else-if="!searchQuery" class="initial-state">
          <span class="material-icons-round">manage_search</span>
          <p>{{ $t('group.enterKeyword') }}</p>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMessageStore } from '@/stores/message'
import dayjs from 'dayjs'

const { t } = useI18n()
const messageStore = useMessageStore()

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  chatId: {
    type: [String, Number],
    required: true
  }
})

const emit = defineEmits(['update:modelValue', 'message-selected'])

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'
const searchQuery = ref('')
const searchResults = ref([])
const loading = ref(false)
let searchTimeout = null

// Reset when dialog opens
watch(visible, (newVal) => {
  if (newVal) {
    searchQuery.value = ''
    searchResults.value = []
  }
})

const handleSearch = () => {
  // Debounce search
  if (searchTimeout) clearTimeout(searchTimeout)

  if (!searchQuery.value.trim()) {
    searchResults.value = []
    return
  }

  loading.value = true
  searchTimeout = setTimeout(() => {
    performSearch()
  }, 300)
}

const performSearch = () => {
  const query = searchQuery.value.toLowerCase().trim()
  if (!query) {
    searchResults.value = []
    loading.value = false
    return
  }

  // Search in local messages
  const messages = messageStore.getMessages(props.chatId) || []
  searchResults.value = messages.filter(msg => {
    if (msg.type !== 'TEXT' && msg.type !== 'text') return false
    return msg.content?.toLowerCase().includes(query)
  }).slice(0, 50) // Limit results

  loading.value = false
}

const highlightText = (text) => {
  if (!searchQuery.value || !text) return text
  const query = searchQuery.value.trim()
  const regex = new RegExp(`(${query})`, 'gi')
  return text.replace(regex, '<mark>$1</mark>')
}

const formatTime = (time) => {
  if (!time) return ''
  const date = dayjs(time)
  const now = dayjs()

  if (date.isSame(now, 'day')) {
    return date.format('HH:mm')
  } else if (date.isSame(now, 'year')) {
    return date.format('MM-DD HH:mm')
  } else {
    return date.format('YYYY-MM-DD HH:mm')
  }
}

const handleMessageClick = (message) => {
  emit('message-selected', message)
  // Could scroll to message in chat view
  handleClose()
}

const handleClose = () => {
  visible.value = false
  searchQuery.value = ''
  searchResults.value = []
}
</script>

<style scoped>
.search-container {
  display: flex;
  flex-direction: column;
  max-height: 500px;
}

.search-box {
  margin-bottom: 16px;
}

.results-container {
  flex: 1;
  min-height: 200px;
  max-height: 400px;
  overflow-y: auto;
}

.results-header {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  margin-bottom: 12px;
  padding: 0 4px;
}

.results-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.message-item {
  display: flex;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.message-item:hover {
  background: #f5f7fa;
}

[data-theme="dark"] .message-item:hover {
  background: #2a2d30;
}

.message-avatar {
  flex-shrink: 0;
}

.message-content {
  flex: 1;
  min-width: 0;
}

.message-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.sender-name {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
}

[data-theme="dark"] .sender-name {
  color: #e2e8f0;
}

.message-time {
  font-size: 11px;
  color: #94a3b8;
}

.message-text {
  font-size: 13px;
  color: #64748b;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.message-text :deep(mark) {
  background: rgba(0, 191, 165, 0.3);
  color: inherit;
  padding: 0 2px;
  border-radius: 2px;
}

.empty-state,
.initial-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: #9ca3af;
}

.empty-state .material-icons-round,
.initial-state .material-icons-round {
  font-size: 56px;
  margin-bottom: 16px;
  opacity: 0.4;
}

.empty-state p,
.initial-state p {
  margin: 0;
  font-size: 14px;
}

/* Scrollbar */
.results-container::-webkit-scrollbar {
  width: 4px;
}

.results-container::-webkit-scrollbar-track {
  background: transparent;
}

.results-container::-webkit-scrollbar-thumb {
  background: rgba(156, 163, 175, 0.3);
  border-radius: 20px;
}
</style>
