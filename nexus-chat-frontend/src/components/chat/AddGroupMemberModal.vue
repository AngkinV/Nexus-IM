<template>
  <el-dialog
    v-model="visible"
    :title="$t('group.inviteMembers')"
    width="380px"
    :close-on-click-modal="false"
    append-to-body
    class="add-member-dialog"
    @close="handleClose"
  >
    <div class="member-selector">
      <!-- Search -->
      <div class="search-box">
        <el-input
          v-model="searchQuery"
          :placeholder="$t('common.search')"
          prefix-icon="Search"
          clearable
        />
      </div>

      <!-- Available Contacts -->
      <div class="contacts-list" v-loading="loading">
        <template v-if="availableContacts.length > 0">
          <div
            v-for="contact in filteredContacts"
            :key="contact.userId"
            class="contact-item"
            :class="{ selected: selectedIds.has(contact.userId) }"
            @click="toggleSelect(contact.userId)"
          >
            <div class="contact-avatar">
              <el-avatar :size="40" :src="contact.avatarUrl || contact.avatar || defaultAvatar" />
              <span class="online-dot" :class="{ online: contact.isOnline }"></span>
            </div>
            <div class="contact-info">
              <span class="contact-name">{{ contact.nickname }}</span>
              <span class="contact-username">@{{ contact.username }}</span>
            </div>
            <div class="check-box">
              <span class="material-icons-round" v-if="selectedIds.has(contact.userId)">check_circle</span>
              <span class="material-icons-round" v-else>radio_button_unchecked</span>
            </div>
          </div>
        </template>
        <div v-else class="empty-state">
          <span class="material-icons-round">group_off</span>
          <p>{{ $t('group.noMoreContacts') }}</p>
        </div>
      </div>

      <!-- Selected Count -->
      <div class="selected-count" v-if="selectedIds.size > 0">
        {{ $t('group.selectedCount', { count: selectedIds.size }) }}
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="handleClose">{{ $t('common.cancel') }}</el-button>
        <el-button
          type="primary"
          :disabled="selectedIds.size === 0"
          :loading="submitting"
          @click="handleConfirm"
        >
          {{ $t('group.addMember') }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { contactAPI, groupAPI } from '@/services/api'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'

const { t } = useI18n()
const userStore = useUserStore()
const chatStore = useChatStore()

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  groupId: {
    type: [String, Number],
    required: true
  },
  existingMemberIds: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['update:modelValue', 'members-added'])

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'
const searchQuery = ref('')
const selectedIds = ref(new Set())
const contacts = ref([])
const loading = ref(false)
const submitting = ref(false)

// Filter out existing members (use userId for comparison)
const availableContacts = computed(() => {
  const existingSet = new Set(props.existingMemberIds)
  return contacts.value.filter(c => !existingSet.has(c.userId))
})

// Filter by search query
const filteredContacts = computed(() => {
  if (!searchQuery.value) return availableContacts.value
  const query = searchQuery.value.toLowerCase()
  return availableContacts.value.filter(c =>
    c.nickname?.toLowerCase().includes(query) ||
    c.username?.toLowerCase().includes(query)
  )
})

// Load contacts when dialog opens
watch(visible, async (newVal) => {
  if (newVal) {
    selectedIds.value = new Set()
    searchQuery.value = ''
    await loadContacts()
  }
})

const loadContacts = async () => {
  loading.value = true
  try {
    const userId = userStore.currentUser?.id
    if (!userId) return
    const response = await contactAPI.getContactsDetailed(userId)
    contacts.value = response.data || []
  } catch (error) {
    console.error('Failed to load contacts:', error)
  } finally {
    loading.value = false
  }
}

const toggleSelect = (userId) => {
  if (selectedIds.value.has(userId)) {
    selectedIds.value.delete(userId)
  } else {
    selectedIds.value.add(userId)
  }
  // Force reactivity
  selectedIds.value = new Set(selectedIds.value)
}

const handleConfirm = async () => {
  if (selectedIds.value.size === 0) return

  submitting.value = true
  try {
    const userId = userStore.currentUser?.id
    const memberIds = [...selectedIds.value]

    await groupAPI.addMembers(props.groupId, userId, memberIds)

    // Update local store (use userId to find contacts)
    const addedMembers = contacts.value.filter(c => selectedIds.value.has(c.userId))
    addedMembers.forEach(member => {
      chatStore.addGroupMember(props.groupId, {
        id: member.userId,
        nickname: member.nickname,
        avatarUrl: member.avatarUrl || member.avatar,
        isOnline: member.isOnline,
        role: 'member'
      })
    })

    ElMessage.success(t('group.addMembersSuccess'))
    emit('members-added', memberIds)
    handleClose()
  } catch (error) {
    console.error('Failed to add members:', error)
    const errorMessage = error.response?.data?.message || t('group.addMembersFailed')
    ElMessage.error(errorMessage)
  } finally {
    submitting.value = false
  }
}

const handleClose = () => {
  visible.value = false
  selectedIds.value = new Set()
  searchQuery.value = ''
}
</script>

<style scoped>
.member-selector {
  max-height: 400px;
  display: flex;
  flex-direction: column;
}

.search-box {
  margin-bottom: 16px;
}

.contacts-list {
  flex: 1;
  overflow-y: auto;
  min-height: 200px;
  max-height: 300px;
}

.contact-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.contact-item:hover {
  background: #f5f7fa;
}

.contact-item.selected {
  background: rgba(16, 185, 129, 0.1);
}

[data-theme="dark"] .contact-item:hover {
  background: #2a2d30;
}

[data-theme="dark"] .contact-item.selected {
  background: rgba(16, 185, 129, 0.15);
}

.contact-avatar {
  position: relative;
}

.online-dot {
  position: absolute;
  bottom: 2px;
  right: 2px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #9ca3af;
  border: 2px solid #ffffff;
}

.online-dot.online {
  background: #10b981;
}

[data-theme="dark"] .online-dot {
  border-color: #1e1e1e;
}

.contact-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.contact-name {
  font-size: 14px;
  font-weight: 500;
  color: #1e293b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

[data-theme="dark"] .contact-name {
  color: #e2e8f0;
}

.contact-username {
  font-size: 12px;
  color: #64748b;
}

.check-box .material-icons-round {
  font-size: 22px;
  color: #d1d5db;
  transition: color 0.2s ease;
}

.contact-item.selected .check-box .material-icons-round {
  color: #10B981;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  color: #9ca3af;
}

.empty-state .material-icons-round {
  font-size: 48px;
  margin-bottom: 12px;
  opacity: 0.5;
}

.empty-state p {
  margin: 0;
  font-size: 14px;
}

.selected-count {
  margin-top: 12px;
  padding: 8px 12px;
  background: rgba(16, 185, 129, 0.1);
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  color: #10B981;
  text-align: center;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* Scrollbar */
.contacts-list::-webkit-scrollbar {
  width: 4px;
}

.contacts-list::-webkit-scrollbar-track {
  background: transparent;
}

.contacts-list::-webkit-scrollbar-thumb {
  background: rgba(156, 163, 175, 0.3);
  border-radius: 20px;
}
</style>
