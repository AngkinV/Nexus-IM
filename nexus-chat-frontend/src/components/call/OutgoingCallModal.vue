<template>
  <Teleport to="body">
    <Transition name="call-modal">
      <div v-if="visible" class="outgoing-call-overlay">
        <div class="outgoing-call-modal">
          <!-- Background animation -->
          <div class="call-background">
            <div class="ripple ripple-1"></div>
            <div class="ripple ripple-2"></div>
            <div class="ripple ripple-3"></div>
          </div>

          <!-- Content -->
          <div class="call-content">
            <!-- Avatar -->
            <div class="callee-avatar-container">
              <img
                v-if="calleeAvatar"
                :src="calleeAvatar"
                class="callee-avatar"
                alt="Callee"
              />
              <div v-else class="callee-avatar-placeholder">
                {{ calleeInitial }}
              </div>
            </div>

            <!-- Callee info -->
            <div class="callee-info">
              <h2 class="callee-name">{{ calleeName }}</h2>
              <p class="call-status">{{ statusText }}</p>
            </div>

            <!-- Cancel button -->
            <div class="call-actions">
              <button class="action-btn cancel-btn" @click="handleCancel">
                <div class="btn-icon">
                  <el-icon :size="28"><Close /></el-icon>
                </div>
                <span class="btn-label">{{ $t('call.cancel') }}</span>
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
import { Close } from '@element-plus/icons-vue'
import { useCallStore, CallStatus } from '@/stores/call'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const callStore = useCallStore()

const visible = computed(() => callStore.isOutgoingCall)

const calleeName = computed(() => {
  return callStore.currentCall?.remoteUser?.name || 'Unknown'
})

const calleeAvatar = computed(() => {
  return callStore.currentCall?.remoteUser?.avatar || ''
})

const calleeInitial = computed(() => {
  const name = calleeName.value
  return name ? name.charAt(0).toUpperCase() : '?'
})

const statusText = computed(() => {
  const status = callStore.callStatus
  const callType = callStore.currentCall?.callType || 'audio'

  if (status === CallStatus.CALLING) {
    return t('call.calling')
  } else if (status === CallStatus.CONNECTING) {
    return t('call.connecting')
  }
  return callType === 'video' ? t('call.videoCall') : t('call.audioCall')
})

function handleCancel() {
  callStore.cancelCall()
}
</script>

<style scoped>
.outgoing-call-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.85);
  backdrop-filter: blur(10px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}

.outgoing-call-modal {
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
}

.ripple {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  border-radius: 50%;
  border: 2px solid rgba(76, 175, 80, 0.3);
  animation: ripple-out 2s ease-out infinite;
}

.ripple-1 {
  width: 150px;
  height: 150px;
  animation-delay: 0s;
}

.ripple-2 {
  width: 200px;
  height: 200px;
  animation-delay: 0.5s;
}

.ripple-3 {
  width: 250px;
  height: 250px;
  animation-delay: 1s;
}

@keyframes ripple-out {
  0% {
    transform: translate(-50%, -50%) scale(0.8);
    opacity: 1;
  }
  100% {
    transform: translate(-50%, -50%) scale(1.5);
    opacity: 0;
  }
}

.call-content {
  position: relative;
  z-index: 1;
}

.callee-avatar-container {
  position: relative;
  display: inline-block;
  margin-bottom: 24px;
}

.callee-avatar {
  width: 120px;
  height: 120px;
  border-radius: 50%;
  object-fit: cover;
  border: 4px solid #fff;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
}

.callee-avatar-placeholder {
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

.callee-info {
  margin-bottom: 60px;
}

.callee-name {
  font-size: 28px;
  font-weight: 600;
  color: #fff;
  margin: 0 0 8px 0;
}

.call-status {
  font-size: 16px;
  color: rgba(255, 255, 255, 0.7);
  margin: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.call-status::after {
  content: '';
  display: inline-block;
  width: 8px;
  height: 8px;
  background: #4caf50;
  border-radius: 50%;
  animation: status-blink 1s ease-in-out infinite;
}

@keyframes status-blink {
  0%, 100% {
    opacity: 1;
  }
  50% {
    opacity: 0.3;
  }
}

.call-actions {
  display: flex;
  justify-content: center;
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

.cancel-btn .btn-icon {
  background: linear-gradient(135deg, #ff416c 0%, #ff4b2b 100%);
  box-shadow: 0 4px 20px rgba(255, 65, 108, 0.4);
}

.cancel-btn:hover .btn-icon {
  box-shadow: 0 6px 30px rgba(255, 65, 108, 0.6);
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

.call-modal-enter-from .outgoing-call-modal,
.call-modal-leave-to .outgoing-call-modal {
  transform: scale(0.9);
}
</style>
