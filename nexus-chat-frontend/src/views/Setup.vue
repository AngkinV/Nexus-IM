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
          :disabled="!nickname"
          @click="completeSetup"
        >
          {{ $t('auth.startMessaging') }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const nickname = ref('')
const avatarUrl = ref(null)
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

const completeSetup = () => {
  if (!nickname.value) return

  // Save user info
  const user = {
    id: Date.now().toString(),
    name: nickname.value,
    avatar: avatarUrl.value,
    createdAt: new Date().toISOString()
  }
  
  localStorage.setItem('user', JSON.stringify(user))
  
  // If we came from login (which sets token), we can go to main
  // If no token, we might need to handle that, but assuming flow is Login -> Setup -> Main
  // Or just Setup -> Main if it's a local-first app as per some requirements
  
  // For now, ensure we have a token or mock one if this is a standalone setup
  if (!localStorage.getItem('token')) {
    localStorage.setItem('token', 'mock-token-' + Date.now())
  }

  router.push('/main')
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

.input-group {
  position: relative;
  margin-bottom: 40px;
  text-align: left;
}

input[type="text"] {
  width: 100%;
  padding: 12px 0;
  font-size: 18px;
  border: none;
  border-bottom: 1px solid rgba(68, 71, 72, 0.45);
  outline: none;
  background: transparent;
  transition: border-color 0.2s;
}

input[type="text"]:focus,
input[type="text"].has-value {
  border-color: #ffffff;
}

label {
  position: absolute;
  left: 0;
  top: 12px;
  color: #6f7274;
  font-size: 18px;
  pointer-events: none;
  transition: 0.2s ease all;
}

input[type="text"]:focus ~ label,
input[type="text"].has-value ~ label {
  top: -20px;
  font-size: 14px;
  color: #ffffff;
}

.submit-btn {
  width: 100%;
  padding: 16px;
  background-color: #ffffff;
  color: #2f3131;
  border: none;
  border-radius: 8px;
  font-size: 18px;
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
