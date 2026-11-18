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
              <div class="avatar-frame"><img v-if="contact.avatarUrl || contact.avatar" class="avatar-img" :src="contact.avatarUrl || contact.avatar" alt="" /><div v-else class="avatar-fallback">{{ (contact.nickname || contact.username || "?").charAt(0) }}</div></div>
              <span class="online-dot" :class="{ online: contact.isOnline }"></span>
            </div>
            <div class="contact-info">
              <span class="contact-name">{{ contact.nickname }}</span>
              <span class="contact-username">@{{ contact.username }}</span>
            </div>
            <div class="check-box">
              <span class="material-symbols-outlined is-on" v-if="selectedIds.has(contact.userId)">check_circle</span>
              <span class="material-symbols-outlined" v-else>radio_button_unchecked</span>
            </div>
          </div>
        </template>
        <div v-else class="empty-state">
          <span class="material-symbols-outlined">group_off</span>
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

.contacts-list {
  flex: 1;
  overflow-y: auto;
  min-height: 200px;
  max-height: 300px;
}

.contact-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.contact-item:hover {
  background: #222225;
}

.contact-item.selected {
  background: #2a2a2d;
}

.contact-avatar {
  position: relative;
  flex-shrink: 0;
}

.avatar-frame {
  position: relative;
  width: 32px;
  height: 32px;
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
  font-size: 11px;
  text-transform: uppercase;
}

.online-dot {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #6f7274;
  border: 2px solid #131316;
}

.online-dot.online {
  background: #ffffff;
}

.contact-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.contact-name {
  font-size: 13px;
  font-weight: 500;
  color: #e4e1e6;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.contact-username {
  font-size: 11px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  color: #8e9192;
}

.check-box .material-symbols-outlined {
  font-size: 18px;
  color: #6f7274;
  transition: color 0.15s ease;
}

.check-box .material-symbols-outlined.is-on {
  color: #ffffff;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 36px 20px;
  color: #8e9192;
}

.empty-state .material-symbols-outlined {
  font-size: 32px;
  margin-bottom: 10px;
  color: #6f7274;
}

.empty-state p {
  margin: 0;
  font-size: 12px;
}

.selected-count {
  margin-top: 10px;
  padding: 7px 10px;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.3);
  border-radius: 6px;
  font-size: 11px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  color: #c4c7c8;
  text-align: center;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.contacts-list::-webkit-scrollbar {
  width: 4px;
}

.contacts-list::-webkit-scrollbar-track {
  background: transparent;
}

.contacts-list::-webkit-scrollbar-thumb {
  background: rgba(142, 145, 146, 0.25);
  border-radius: 20px;
}
</style>
