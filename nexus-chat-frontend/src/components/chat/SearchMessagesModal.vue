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
                <div class="avatar-frame"><img v-if="message.senderAvatar" class="avatar-img" :src="message.senderAvatar" alt="" /><div v-else class="avatar-fallback">{{ (message.senderName || "?").charAt(0) }}</div></div>
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
          <span class="material-symbols-outlined">search_off</span>
          <p>{{ $t('group.noMessagesFound') }}</p>
        </div>

        <!-- Initial State -->
        <div v-else-if="!searchQuery" class="initial-state">
          <span class="material-symbols-outlined">manage_search</span>
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
  margin-bottom: 12px;
}

.search-box :deep(.el-input__wrapper) {
  border-radius: 8px;
  background: #16161a;
  box-shadow: 0 0 0 1px rgba(68, 71, 72, 0.3) inset;
}

.search-box :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px rgba(142, 145, 146, 0.6) inset;
}

.results-container {
  flex: 1;
  min-height: 200px;
  max-height: 400px;
  overflow-y: auto;
}

.results-header {
  font-size: 11px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  color: #6f7274;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  margin-bottom: 10px;
  padding: 0 2px;
}

.results-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.message-item {
  display: flex;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.message-item:hover {
  background: #222225;
}

.message-avatar {
  flex-shrink: 0;
}

.avatar-frame {
  width: 28px;
  height: 28px;
  border-radius: 9999px;
  background: #2a2a2d;
  box-shadow: 0 0 0 1px rgba(68, 71, 72, 0.4);
}

.avatar-img {
  width: 100%;
  height: 100%;
  border-radius: 9999px;
  object-fit: cover;
  filter: grayscale(100%) contrast(1.1);
}

.avatar-fallback {
  width: 100%;
  height: 100%;
  border-radius: 9999px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #2a2a2d;
  color: #c4c7c8;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  text-transform: uppercase;
}

.message-content {
  flex: 1;
  min-width: 0;
}

.message-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 2px;
}

.sender-name {
  font-size: 12px;
  font-weight: 500;
  color: #e4e1e6;
}

.message-time {
  font-size: 10px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  color: #6f7274;
}

.message-text {
  font-size: 12px;
  color: #8e9192;
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.message-text :deep(mark) {
  background: rgba(255, 255, 255, 0.16);
  color: #ffffff;
  padding: 0 2px;
  border-radius: 2px;
}

.empty-state,
.initial-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 56px 20px;
  color: #8e9192;
}

.empty-state .material-symbols-outlined,
.initial-state .material-symbols-outlined {
  font-size: 32px;
  margin-bottom: 12px;
  color: #6f7274;
}

.empty-state p,
.initial-state p {
  margin: 0;
  font-size: 12px;
}

.results-container::-webkit-scrollbar {
  width: 4px;
}

.results-container::-webkit-scrollbar-track {
  background: transparent;
}

.results-container::-webkit-scrollbar-thumb {
  background: rgba(142, 145, 146, 0.25);
  border-radius: 20px;
}
</style>
