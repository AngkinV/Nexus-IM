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
              <el-avatar :size="50" :src="user.avatar || defaultAvatar" />
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
              <el-button v-else-if="isContactAdded(user.id)" type="success" size="small" disabled>
                <el-icon><Check /></el-icon>
                {{ $t('contact.added') }}
              </el-button>
              <el-button v-else type="warning" size="small" disabled>
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
            <el-avatar :size="40" :src="user.avatar || defaultAvatar" />
            <span class="recommended-name">{{ user.nickname }}</span>
            <el-icon class="add-icon"><Plus /></el-icon>
          </div>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
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
const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'

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
    recommendedUsers.value = (response.data || []).filter(
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
    searchResults.value = (response.data || []).filter(
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
  gap: 10px;
}

.search-input {
  flex: 1;
}

.search-input :deep(.el-input__wrapper) {
  border-radius: 24px;
  padding-left: 15px;
}

.search-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 15px;
  background: #f0f7ff;
  border-radius: 10px;
  color: #3390ec;
  font-size: 13px;
}

.hint-icon {
  font-size: 16px;
}

.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 40px;
  color: #666;
}

.loading-icon {
  font-size: 32px;
  color: #3390ec;
}

.search-results {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.results-title {
  font-size: 13px;
  color: #999;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.user-result-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 15px;
  background: #f8f9fa;
  border-radius: 12px;
  transition: all 0.2s;
}

.user-result-card:hover {
  background: #f0f2f5;
  transform: translateY(-1px);
}

.user-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-details {
  display: flex;
  flex-direction: column;
}

.user-nickname {
  font-weight: 600;
  font-size: 15px;
  color: #1c1c1e;
}

.user-username {
  font-size: 13px;
  color: #3390ec;
}

.user-email {
  font-size: 12px;
  color: #999;
}

.no-results {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 40px 20px;
  gap: 10px;
}

.no-results-illustration {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: #f5f5f5;
  display: flex;
  justify-content: center;
  align-items: center;
}

.sad-icon {
  font-size: 40px;
  color: #ccc;
}

.no-results-text {
  font-size: 16px;
  font-weight: 500;
  color: #333;
}

.no-results-hint {
  font-size: 13px;
  color: #999;
}

.section-divider {
  display: flex;
  align-items: center;
  gap: 15px;
  color: #999;
  font-size: 12px;
}

.section-divider::before,
.section-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: #e5e5e5;
}

.quick-add-section {
  display: flex;
  flex-direction: column;
  gap: 15px;
}

.direct-id-input :deep(.el-input-group__append) {
  background: #3390ec;
  border-color: #3390ec;
}

.direct-id-input :deep(.el-input-group__append .el-button) {
  color: white;
}

.recommended-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 500;
  color: #333;
}

.section-title .el-icon {
  color: #ffc107;
}

.recommended-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 5px 0;
}

.recommended-user {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 12px 15px;
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;
  min-width: 80px;
  flex: 1;
  max-width: calc(33.33% - 7px);
  position: relative;
}

.recommended-user:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.recommended-user:hover .add-icon {
  opacity: 1;
}

.recommended-name {
  font-size: 12px;
  color: #333;
  text-align: center;
  max-width: 80px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.add-icon {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #3390ec;
  color: white;
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 14px;
  opacity: 0;
  transition: opacity 0.2s;
}

/* Mobile Responsive Styles */
@media (max-width: 768px) {
  .add-contact-content {
    gap: 12px;
  }

  .search-section {
    flex-direction: column;
    gap: 8px;
  }

  .search-input :deep(.el-input__wrapper) {
    padding-left: 12px;
  }

  .search-input :deep(.el-input__inner) {
    font-size: 16px;
  }

  .search-hint {
    padding: 10px 12px;
    font-size: 12px;
  }

  .user-result-card {
    padding: 10px 12px;
  }

  .user-left {
    gap: 10px;
  }

  .user-nickname {
    font-size: 14px;
  }

  .user-username {
    font-size: 12px;
  }

  .user-email {
    font-size: 11px;
  }

  .no-results {
    padding: 30px 16px;
  }

  .no-results-illustration {
    width: 60px;
    height: 60px;
  }

  .sad-icon {
    font-size: 32px;
  }

  .no-results-text {
    font-size: 14px;
  }

  .no-results-hint {
    font-size: 12px;
  }

  .recommended-user {
    padding: 10px 12px;
    max-width: calc(50% - 5px);
  }

  .recommended-name {
    font-size: 11px;
    max-width: 70px;
  }

  .section-title {
    font-size: 13px;
  }
}
</style>
