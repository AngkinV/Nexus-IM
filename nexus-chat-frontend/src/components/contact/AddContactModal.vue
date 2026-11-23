<template>
  <el-dialog
    v-model="dialogVisible"
    :title="$t('contact.addContact')"
    width="360px"
    class="add-contact-dialog"
    append-to-body
    align-center
    :before-close="handleClose"
    :close-on-click-modal="false"
  >
    <div class="add-contact-content">
      <!-- Search Section -->
      <div class="search-section">
        <el-input
          v-model="searchQuery"
          :placeholder="$t('contact.searchPlaceholder')"
          class="search-input"
          @keyup.enter="handleSearch"
          clearable
          size="large"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button 
          type="primary" 
          @click="handleSearch" 
          :loading="isSearching"
          :disabled="!searchQuery.trim()"
          size="large"
        >
          {{ $t('common.search') }}
        </el-button>
      </div>

      <div class="search-hint" v-if="!hasSearched">
        <el-icon class="hint-icon"><InfoFilled /></el-icon>
        <span>{{ $t('contact.searchHint') }}</span>
      </div>

      <!-- Search Results -->
      <div v-if="isSearching" class="loading-state">
        <el-icon class="loading-icon is-loading"><Loading /></el-icon>
        <span>{{ $t('contact.searching') }}</span>
      </div>

      <div v-else-if="searchResults.length > 0" class="search-results">
        <div class="results-title">{{ $t('contact.searchResults') }}</div>
        <el-scrollbar max-height="300px">
          <div
            v-for="user in searchResults"
            :key="user.id"
            class="user-result-card"
          >
            <div class="user-left">
              <div class="avatar-frame avatar-frame--lg"><IdentityAvatar :src="user.avatarUrl || user.avatar" :entity-id="user.userId ?? user.id" :label="user.nickname || user.username" /></div>
              <div class="user-details">
                <span class="user-nickname">{{ user.nickname }}</span>
                <span class="user-username">@{{ user.username }}</span>
                <span v-if="user.email" class="user-email">{{ user.email }}</span>
              </div>
            </div>
            <div class="user-right">
              <el-button
                v-if="!isContactAdded(user.id) && !isRequestSent(user.id)"
                type="primary"
                size="small"
                @click="addContact(user)"
                :loading="addingUserId === user.id"
              >
                <el-icon><Plus /></el-icon>
                {{ $t('contact.add') }}
              </el-button>
              <el-button v-else-if="isContactAdded(user.id)" size="small" disabled class="state-btn">
                <el-icon><Check /></el-icon>
                {{ $t('contact.added') }}
              </el-button>
              <el-button v-else size="small" disabled class="state-btn">
                <el-icon><Clock /></el-icon>
                {{ $t('contact.requestSentShort') }}
              </el-button>
            </div>
          </div>
        </el-scrollbar>
      </div>

      <div v-else-if="hasSearched && searchResults.length === 0" class="no-results">
        <div class="no-results-illustration">
          <el-icon class="sad-icon"><Warning /></el-icon>
        </div>
        <span class="no-results-text">{{ $t('contact.notFound') }}</span>
        <span class="no-results-hint">{{ $t('contact.tryAgain') }}</span>
      </div>

      <!-- Quick Add Section -->
      <div class="quick-add-section" v-if="!hasSearched">
        <div class="section-divider">
          <span>{{ $t('contact.orAddById') }}</span>
        </div>
        
        <el-input
          v-model="directUserId"
          :placeholder="$t('contact.enterUserId')"
          class="direct-id-input"
          clearable
        >
          <template #append>
            <el-button 
              type="primary" 
              @click="addContactById"
              :disabled="!directUserId.trim()"
            >
              {{ $t('contact.add') }}
            </el-button>
          </template>
        </el-input>
      </div>

      <!-- Recommended Contacts -->
      <div v-if="!hasSearched && recommendedUsers.length > 0" class="recommended-section">
        <div class="section-title">
          <el-icon><Star /></el-icon>
          {{ $t('contact.recommended') }}
        </div>
        <div class="recommended-list">
          <div
            v-for="user in recommendedUsers"
            :key="user.id"
            class="recommended-user"
            @click="quickAddContact(user)"
          >
            <div class="avatar-frame"><IdentityAvatar :src="user.avatarUrl || user.avatar" :entity-id="user.userId ?? user.id" :label="user.nickname || user.username" /></div>
            <span class="recommended-name">{{ user.nickname }}</span>
            <el-icon class="add-icon"><Plus /></el-icon>
          </div>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import IdentityAvatar from '@/components/identity/IdentityAvatar.vue'
import { ref, computed, watch, onMounted } from 'vue'
import { Search, Plus, Check, InfoFilled, Loading, Warning, Star, Clock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useContactStore } from '@/stores/contact'
import { useUserStore } from '@/stores/user'
import { useI18n } from 'vue-i18n'
import { userAPI } from '@/services/api'

const { t } = useI18n()

const props = defineProps({
  visible: Boolean
})

const emit = defineEmits(['update:visible', 'added'])

const contactStore = useContactStore()
const userStore = useUserStore()

// Ensure user is loaded from storage
if (!userStore.currentUser) {
  userStore.loadUserFromStorage()
}

const dialogVisible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
})

const searchQuery = ref('')
const directUserId = ref('')
const searchResults = ref([])
const hasSearched = ref(false)
const isSearching = ref(false)
const addingUserId = ref(null)
const addedUserIds = ref([])
const requestSentUserIds = ref([])  // 已发送申请的用户ID

// Recommended users from API
const recommendedUsers = ref([])

// Get current user ID
const currentUserId = computed(() => userStore.currentUser?.id)

// Load recommended users on mount
onMounted(async () => {
  await loadRecommendedUsers()
})

// Reset on dialog open
watch(() => props.visible, async (val) => {
  if (val) {
    resetForm()
    await loadRecommendedUsers()
  }
})

const loadRecommendedUsers = async () => {
  if (!currentUserId.value) return

  try {
    const response = await userAPI.getRecommendedUsers(currentUserId.value, 5)
    recommendedUsers.value = (response.data || []).map(user => ({ ...user, id: user.userId ?? user.id })).filter(
      user => user.id !== currentUserId.value && !isContactAdded(user.id)
    )
  } catch (error) {
    console.error('Failed to load recommended users:', error)
    recommendedUsers.value = []
  }
}

const handleClose = () => {
  dialogVisible.value = false
  resetForm()
}

const resetForm = () => {
  searchQuery.value = ''
  directUserId.value = ''
  searchResults.value = []
  hasSearched.value = false
  isSearching.value = false
  addingUserId.value = null
  requestSentUserIds.value = []
}

const isContactAdded = (userId) => {
  return addedUserIds.value.includes(userId) ||
         contactStore.contacts.some(c => c.id === userId || c.userId === userId)
}

const isRequestSent = (userId) => {
  return requestSentUserIds.value.includes(userId) ||
         contactStore.sentRequests.some(r => r.toUserId === userId)
}

const handleSearch = async () => {
  if (!searchQuery.value.trim()) return

  isSearching.value = true
  hasSearched.value = true

  try {
    const response = await userAPI.searchUsers(searchQuery.value.trim())
    // Filter out current user from results
    searchResults.value = (response.data || []).map(user => ({ ...user, id: user.userId ?? user.id })).filter(
      user => user.id !== currentUserId.value
    )
  } catch (error) {
    console.error('Search failed:', error)
    ElMessage.error(t('contact.searchFailed'))
    searchResults.value = []
  } finally {
    isSearching.value = false
  }
}

const addContact = async (user) => {
  // Ensure user is loaded
  if (!userStore.currentUser) {
    userStore.loadUserFromStorage()
  }

  const userId = userStore.currentUser?.id
  if (!userId) {
    ElMessage.error(t('common.notLoggedIn'))
    return
  }

  addingUserId.value = user.id

  try {
    const result = await contactStore.addContactRequest(userId, user.id)

    if (result.type === 'direct') {
      // 直接添加成功
      addedUserIds.value.push(user.id)
      emit('added', user)
      ElMessage.success(t('contact.addSuccess', { name: user.nickname || user.username }))
    } else if (result.type === 'request') {
      // 发送了好友申请
      requestSentUserIds.value.push(user.id)
      ElMessage.success(t('contact.requestSent', { name: user.nickname || user.username }))
    }
  } catch (error) {
    console.error('Failed to add contact:', error)
    const errorKey = error.response?.data?.error
    // Map backend error keys to frontend translations
    let errorMsg = t('contact.addFailed')
    if (errorKey === 'error.friend.request.already.sent') {
      errorMsg = t('contact.alreadySent')
    } else if (errorKey === 'error.contact.exists' || errorKey === 'error.friend.request.already.contact') {
      errorMsg = t('contact.alreadyContact')
    } else if (errorKey === 'error.contact.self.add') {
      errorMsg = t('contact.cannotAddSelf')
    } else if (errorKey) {
      errorMsg = errorKey
    }
    ElMessage.error(errorMsg)
  } finally {
    addingUserId.value = null
  }
}

const quickAddContact = async (user) => {
  await addContact(user)
}

const addContactById = async () => {
  if (!directUserId.value.trim()) return

  const visitorId = parseInt(directUserId.value.trim())
  if (isNaN(visitorId)) {
    ElMessage.error(t('contact.invalidUserId'))
    return
  }

  // Ensure user is loaded
  if (!userStore.currentUser) {
    userStore.loadUserFromStorage()
  }

  const userId = userStore.currentUser?.id
  if (!userId) {
    ElMessage.error(t('common.notLoggedIn'))
    return
  }

  if (visitorId === userId) {
    ElMessage.error(t('contact.cannotAddSelf'))
    return
  }

  try {
    // First get user info
    const response = await userAPI.getUserById(visitorId)
    if (response.data) {
      await addContact(response.data)
      directUserId.value = ''
    }
  } catch (error) {
    console.error('Failed to find user:', error)
    ElMessage.error(t('contact.userNotFound'))
  }
}
</script>

<style scoped>
.add-contact-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow-x: hidden;
}

.search-section {
  display: flex;
  gap: 8px;
}

.search-input {
  flex: 1;
}

.search-input :deep(.el-input__wrapper) {
  border-radius: 8px;
  padding-left: 12px;
  background: #16161a;
  box-shadow: 0 0 0 1px rgba(68, 71, 72, 0.3) inset;
}

.search-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px rgba(142, 145, 146, 0.6) inset;
}

.search-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.3);
  border-radius: 8px;
  color: #8e9192;
  font-size: 12px;
}

.hint-icon {
  font-size: 15px;
  color: #8e9192;
}

.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 40px;
  color: #8e9192;
  font-size: 12px;
}

.loading-icon {
  font-size: 26px;
  color: #c4c7c8;
}

.search-results {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.results-title {
  font-size: 11px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  color: #6f7274;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.user-result-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.25);
  border-radius: 8px;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.user-result-card:hover {
  background: #222225;
  border-color: rgba(142, 145, 146, 0.4);
}

.user-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.avatar-frame {
  position: relative;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: 9999px;
  background: #2a2a2d;
  box-shadow: 0 0 0 1px rgba(68, 71, 72, 0.4);
}

.avatar-frame--lg {
  width: 40px;
  height: 40px;
}

.avatar-img {
  width: 100%;
  height: 100%;
  border-radius: 9999px;
  object-fit: cover;
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
  font-size: 12px;
  text-transform: uppercase;
}

.user-details {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.user-nickname {
  font-weight: 500;
  font-size: 13px;
  color: #e4e1e6;
}

.user-username {
  font-size: 11px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  color: #8e9192;
}

.user-email {
  font-size: 11px;
  color: #6f7274;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.state-btn {
  pointer-events: none;
}

.no-results {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 36px 20px;
  gap: 8px;
}

.no-results-illustration {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.3);
  display: flex;
  justify-content: center;
  align-items: center;
  margin-bottom: 4px;
}

.sad-icon {
  font-size: 24px;
  color: #6f7274;
}

.no-results-text {
  font-size: 13px;
  font-weight: 500;
  color: #e4e1e6;
}

.no-results-hint {
  font-size: 12px;
  color: #8e9192;
}

.section-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #6f7274;
  font-size: 11px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
}

.section-divider::before,
.section-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: rgba(68, 71, 72, 0.4);
}

.quick-add-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.direct-id-input :deep(.el-input-group__append) {
  background: #ffffff;
  border-color: #ffffff;
  box-shadow: none;
}

.direct-id-input :deep(.el-input-group__append .el-button) {
  color: #2f3131;
  background: transparent;
  border: none;
}

.recommended-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 500;
  color: #c4c7c8;
}

.section-title .el-icon {
  color: #8e9192;
  font-size: 14px;
}

.recommended-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 2px 0;
}

.recommended-user {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 12px 10px;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.25);
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
  min-width: 76px;
  flex: 1;
  max-width: calc(33.33% - 6px);
  position: relative;
}

.recommended-user:hover {
  background: #222225;
  border-color: rgba(142, 145, 146, 0.4);
}

.recommended-user:hover .add-icon {
  opacity: 1;
}

.recommended-name {
  font-size: 11px;
  color: #c4c7c8;
  text-align: center;
  max-width: 80px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.add-icon {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 18px;
  height: 18px;
  border-radius: 4px;
  background: #ffffff;
  color: #2f3131;
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 12px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

/* Mobile */
@media (max-width: 768px) {
  .add-contact-content {
    gap: 12px;
  }

  .search-section {
    flex-direction: column;
    gap: 8px;
  }

  .search-input :deep(.el-input__inner) {
    font-size: 16px;
  }

  .search-hint {
    padding: 9px 10px;
    font-size: 11px;
  }

  .user-result-card {
    padding: 9px 10px;
  }

  .no-results {
    padding: 28px 16px;
  }

  .no-results-illustration {
    width: 48px;
    height: 48px;
  }

  .sad-icon {
    font-size: 20px;
  }

  .recommended-user {
    padding: 10px;
    max-width: calc(50% - 4px);
  }
}
</style>
