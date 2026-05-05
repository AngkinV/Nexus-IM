<template>
  <Teleport to="body">
    <Transition name="call-modal">
      <div v-if="visible" class="incoming-call-overlay">
        <div class="incoming-call-modal">
          <!-- Background blur effect -->
          <div class="call-background"></div>

          <!-- Content -->
          <div class="call-content">
            <!-- Avatar -->
            <div class="caller-avatar-container">
              <div class="avatar-ring"></div>
              <img
                v-if="callerAvatar"
                :src="callerAvatar"
                class="caller-avatar"
                alt="Caller"
              />
              <div v-else class="caller-avatar-placeholder">
                {{ callerInitial }}
              </div>
            </div>

            <!-- Caller info -->
            <div class="caller-info">
              <h2 class="caller-name">{{ callerName }}</h2>
              <p class="call-type">
                {{ callType === 'video' ? $t('call.incomingVideoCall') : $t('call.incomingAudioCall') }}
              </p>
            </div>

            <!-- Action buttons -->
            <div class="call-actions">
              <button class="action-btn reject-btn" @click="handleReject">
                <div class="btn-icon">
                  <el-icon :size="28"><Close /></el-icon>
                </div>
                <span class="btn-label">{{ $t('call.reject') }}</span>
              </button>

              <button class="action-btn accept-btn" @click="handleAccept">
                <div class="btn-icon">
                  <el-icon :size="28">
                    <VideoCamera v-if="callType === 'video'" />
                    <Phone v-else />
                  </el-icon>
                </div>
                <span class="btn-label">{{ $t('call.accept') }}</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { computed } from 'vue'
import { Phone, VideoCamera, Close } from '@element-plus/icons-vue'
import { useCallStore } from '@/stores/call'

const callStore = useCallStore()

const visible = computed(() => callStore.hasIncomingCall)

const callerName = computed(() => {
  return callStore.currentCall?.remoteUser?.name || 'Unknown'
})

const callerAvatar = computed(() => {
  return callStore.currentCall?.remoteUser?.avatar || ''
})

const callerInitial = computed(() => {
  const name = callerName.value
  return name ? name.charAt(0).toUpperCase() : '?'
})

const callType = computed(() => {
  return callStore.currentCall?.callType || 'audio'
})

function handleAccept() {
  callStore.acceptCall()
}

function handleReject() {
  callStore.rejectCall()
}
</script>

<style scoped>
.incoming-call-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.8);
  backdrop-filter: blur(10px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}

.incoming-call-modal {
  position: relative;
  width: 100%;
  max-width: 400px;
  padding: 40px;
  text-align: center;
}

.call-background {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 300px;
  height: 300px;
  background: radial-gradient(circle, rgba(76, 175, 80, 0.2) 0%, transparent 70%);
  border-radius: 50%;
  animation: pulse-bg 2s ease-in-out infinite;
}

@keyframes pulse-bg {
  0%, 100% {
    transform: translate(-50%, -50%) scale(1);
    opacity: 0.5;
  }
  50% {
    transform: translate(-50%, -50%) scale(1.2);
    opacity: 0.3;
  }
}

.call-content {
  position: relative;
  z-index: 1;
}

.caller-avatar-container {
  position: relative;
  display: inline-block;
  margin-bottom: 24px;
}

.avatar-ring {
  position: absolute;
  top: -10px;
  left: -10px;
  right: -10px;
  bottom: -10px;
  border: 3px solid rgba(76, 175, 80, 0.5);
  border-radius: 50%;
  animation: ring-pulse 1.5s ease-in-out infinite;
}

@keyframes ring-pulse {
  0%, 100% {
    transform: scale(1);
    opacity: 1;
  }
  50% {
    transform: scale(1.1);
    opacity: 0.5;
  }
}

.caller-avatar {
  width: 120px;
  height: 120px;
  border-radius: 50%;
  object-fit: cover;
  border: 4px solid #fff;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
}

.caller-avatar-placeholder {
  width: 120px;
  height: 120px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 48px;
  font-weight: 600;
  color: #fff;
  border: 4px solid #fff;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
}

.caller-info {
  margin-bottom: 48px;
}

.caller-name {
  font-size: 28px;
  font-weight: 600;
  color: #fff;
  margin: 0 0 8px 0;
}

.call-type {
  font-size: 16px;
  color: rgba(255, 255, 255, 0.7);
  margin: 0;
  animation: fade-pulse 2s ease-in-out infinite;
}

@keyframes fade-pulse {
  0%, 100% {
    opacity: 0.7;
  }
  50% {
    opacity: 1;
  }
}

.call-actions {
  display: flex;
  justify-content: center;
  gap: 60px;
}

.action-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  background: none;
  border: none;
  cursor: pointer;
  transition: transform 0.2s;
}

.action-btn:hover {
  transform: scale(1.1);
}

.action-btn:active {
  transform: scale(0.95);
}

.btn-icon {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  transition: box-shadow 0.2s;
}

.reject-btn .btn-icon {
  background: linear-gradient(135deg, #ff416c 0%, #ff4b2b 100%);
  box-shadow: 0 4px 20px rgba(255, 65, 108, 0.4);
}

.reject-btn:hover .btn-icon {
  box-shadow: 0 6px 30px rgba(255, 65, 108, 0.6);
}

.accept-btn .btn-icon {
  background: linear-gradient(135deg, #4caf50 0%, #45a049 100%);
  box-shadow: 0 4px 20px rgba(76, 175, 80, 0.4);
  animation: accept-pulse 1.5s ease-in-out infinite;
}

@keyframes accept-pulse {
  0%, 100% {
    box-shadow: 0 4px 20px rgba(76, 175, 80, 0.4);
  }
  50% {
    box-shadow: 0 6px 30px rgba(76, 175, 80, 0.6);
  }
}

.accept-btn:hover .btn-icon {
  box-shadow: 0 6px 30px rgba(76, 175, 80, 0.6);
}

.btn-label {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.8);
  font-weight: 500;
}

/* Transition animations */
.call-modal-enter-active,
.call-modal-leave-active {
  transition: all 0.3s ease;
}

.call-modal-enter-from,
.call-modal-leave-to {
  opacity: 0;
}

.call-modal-enter-from .incoming-call-modal,
.call-modal-leave-to .incoming-call-modal {
  transform: scale(0.9);
}
</style>
