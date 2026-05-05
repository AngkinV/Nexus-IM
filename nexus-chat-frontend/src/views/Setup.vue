<template>
  <div class="setup-container">
    <!-- Drag region for window dragging -->
    <div class="drag-region"></div>
    <div class="setup-card">
      <div class="logo-section">
        <div class="logo-circle">
          <svg viewBox="0 0 24 24" class="logo-icon">
            <path fill="currentColor" d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/>
          </svg>
        </div>
        <h1>Your Profile</h1>
        <p class="subtitle">Enter your name and add a profile picture.</p>
      </div>

      <div class="form-section">
        <div class="avatar-upload" @click="triggerFileInput">
          <div class="avatar-preview" :style="avatarStyle">
            <span v-if="!avatarUrl" class="camera-icon">📷</span>
          </div>
          <input 
            type="file" 
            ref="fileInput" 
            accept="image/*" 
            class="hidden-input"
            @change="handleFileChange"
          >
          <span class="upload-hint">Add Profile Photo</span>
        </div>

        <div class="input-group">
          <input 
            type="text" 
            v-model="nickname" 
            placeholder="Your Name (required)"
            @keyup.enter="completeSetup"
            :class="{ 'has-value': nickname.length > 0 }"
          >
          <label>Name</label>
        </div>

        <button 
          class="submit-btn" 
          :disabled="!nickname"
          @click="completeSetup"
        >
          Start Messaging
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
  background-color: var(--bg-color, #ffffff);
  color: var(--text-color, #000000);
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
  color: var(--primary-color, #3390ec);
}

h1 {
  font-size: 32px;
  font-weight: 600;
  margin-bottom: 12px;
}

.subtitle {
  color: #707579;
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
  background-color: #f1f1f1;
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 32px;
  transition: opacity 0.2s;
}

.avatar-upload:hover .avatar-preview {
  opacity: 0.8;
}

.upload-hint {
  color: var(--primary-color, #3390ec);
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
  border-bottom: 2px solid #e0e0e0;
  outline: none;
  background: transparent;
  transition: border-color 0.2s;
}

input[type="text"]:focus,
input[type="text"].has-value {
  border-color: var(--primary-color, #3390ec);
}

label {
  position: absolute;
  left: 0;
  top: 12px;
  color: #707579;
  font-size: 18px;
  pointer-events: none;
  transition: 0.2s ease all;
}

input[type="text"]:focus ~ label,
input[type="text"].has-value ~ label {
  top: -20px;
  font-size: 14px;
  color: var(--primary-color, #3390ec);
}

.submit-btn {
  width: 100%;
  padding: 16px;
  background-color: var(--primary-color, #3390ec);
  color: white;
  border: none;
  border-radius: 12px;
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
