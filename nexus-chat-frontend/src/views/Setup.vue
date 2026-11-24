<template>
  <div class="setup-container">
    <!-- Drag region for window dragging -->
    <div class="drag-region"></div>
    <div class="setup-card">
      <div class="logo-section">
        <div class="logo-circle">
          <img src="@/assets/images/nexus-emblem.png" alt="Nexus" class="logo-img" />
        </div>
        <h1>{{ $t('auth.setupTitle') }}</h1>
        <p class="subtitle">{{ $t('auth.setupSubtitle') }}</p>
      </div>

      <div class="form-section">
        <div class="avatar-upload" @click="triggerFileInput">
          <div class="avatar-preview" :style="avatarStyle">
            <span v-if="!avatarUrl" class="material-symbols-outlined camera-icon">photo_camera</span>
          </div>
          <input 
            type="file" 
            ref="fileInput" 
            accept="image/*" 
            class="hidden-input"
            @change="handleFileChange"
          >
          <span class="upload-hint">{{ $t('auth.setupAddPhoto') }}</span>
        </div>

        <div class="input-group">
          <input 
            type="text" 
            v-model="nickname" 
            :placeholder="$t('auth.setupNamePlaceholder')"
            @keyup.enter="completeSetup"
            :class="{ 'has-value': nickname.length > 0 }"
          >
          <label>{{ $t('auth.setupName') }}</label>
        </div>

        <button 
          class="submit-btn" 
          :disabled="!nickname.trim() || saving"
          :aria-busy="saving"
          @click="completeSetup"
        >
          {{ $t('auth.startMessaging') }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { userAPI } from '@/services/api'
import { mainLocation } from '@/router/navigation'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const { t } = useI18n()
const nickname = ref('')
const avatarUrl = ref(null)
const saving = ref(false)
let alive = true
onBeforeUnmount(() => { alive = false })
onMounted(() => {
  if (!userStore.currentUser) userStore.loadUserFromStorage()
  nickname.value = userStore.currentUser?.nickname || ''
  avatarUrl.value = userStore.currentUser?.avatar || null
})
const fileInput = ref(null)

const avatarStyle = computed(() => {
  if (avatarUrl.value) {
    return {
      backgroundImage: `url(${avatarUrl.value})`,
      backgroundSize: 'cover',
      backgroundPosition: 'center'
    }
  }
  return {}
})

const triggerFileInput = () => {
  fileInput.value.click()
}

const handleFileChange = (event) => {
  const file = event.target.files[0]
  if (file) {
    const reader = new FileReader()
    reader.onload = (e) => {
      avatarUrl.value = e.target.result
    }
    reader.readAsDataURL(file)
  }
}

const completeSetup = async () => {
  const name = nickname.value.trim(), id = userStore.currentUser?.id
  const token = localStorage.getItem('token'), avatar = avatarUrl.value
  if (saving.value || !name) return
  if (!id || !token || token.startsWith('mock-token-')) {
    await router.replace({ name: 'Login', query: { redirect: '/setup' } })
    return
  }
  const current = () => alive && route.name === 'Setup' && userStore.currentUser?.id === id && localStorage.getItem('token') === token
  saving.value = true
  try {
    if (avatar?.startsWith('data:image/')) {
      await userAPI.uploadAvatar(id, avatar)
      if (!current()) return
    }
    await userAPI.updateProfile(id, { nickname: name })
    if (!current()) return
    await userStore.loadUserProfile()
    if (current()) await router.replace(mainLocation('chats'))
  } catch {
    if (current()) ElMessage.error(t('common.failed'))
  } finally {
    if (current()) saving.value = false
  }
}
</script>

<style scoped>
.setup-container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  background-color: #131316;
  color: #e4e1e6;
  position: relative;
}

/* Drag region for window dragging */
.drag-region {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 32px;
  -webkit-app-region: drag;
  z-index: 1000;
}

.setup-card {
  width: 100%;
  max-width: 400px;
  padding: 40px;
  text-align: center;
}

.logo-circle {
  width: 100px;
  height: 100px;
  margin: 0 auto 24px;
}

.logo-img {
  width: 100%;
  height: 100%;
  display: block;
}

h1 {
  font-size: 32px;
  font-weight: 600;
  margin-bottom: 12px;
}

.subtitle {
  color: #8e9192;
  font-size: 16px;
  margin-bottom: 40px;
  line-height: 1.5;
}

.avatar-upload {
  margin-bottom: 40px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.avatar-preview {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  background-color: #222225;
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 32px;
  transition: opacity 0.2s;
}

.camera-icon {
  font-size: 30px;
  color: #8e9192;
  line-height: 1;
  transition: color 0.2s;
}

.avatar-upload:hover .camera-icon {
  color: #e4e1e6;
}

.avatar-upload:hover .avatar-preview {
  opacity: 0.8;
}

.upload-hint {
  color: #ffffff;
  font-size: 14px;
}

.hidden-input {
  display: none;
}

/* The label follows the input in the markup, so the column is reversed to
   show it above the field. */
.input-group {
  display: flex;
  flex-direction: column-reverse;
  gap: 8px;
  margin-bottom: 32px;
  text-align: left;
}

input[type="text"] {
  box-sizing: border-box;
  width: 100%;
  height: 48px;
  padding: 0 20px;
  border: none;
  border-radius: var(--field-radius);
  outline: none;
  background: var(--field-bg);
  box-shadow: 0 0 0 1px var(--field-ring) inset;
  color: var(--field-text);
  caret-color: #ffffff;
  font-size: 15px;
  transition: box-shadow var(--field-ease);
}

input[type="text"]::placeholder {
  color: var(--field-placeholder);
}

input[type="text"]:hover {
  box-shadow: 0 0 0 1px var(--field-ring-hover) inset;
}

input[type="text"]:focus {
  box-shadow: 0 0 0 1px var(--field-ring-focus) inset;
}

label {
  padding-left: 20px;
  color: #8e9192;
  font-size: 12.5px;
  transition: color var(--field-ease);
}

input[type="text"]:focus ~ label,
input[type="text"].has-value ~ label {
  color: #e4e1e6;
}

.submit-btn {
  width: 100%;
  padding: 16px;
  background-color: #ffffff;
  color: #2f3131;
  border: none;
  border-radius: 999px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.1s, opacity 0.2s;
}

.submit-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.submit-btn:not(:disabled):active {
  transform: scale(0.98);
}
</style>
