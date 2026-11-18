<template>
  <Teleport to="body">
    <Transition name="end-modal">
      <div v-if="visible" class="call-end-overlay">
        <div class="call-end-modal">
          <!-- Icon -->
          <div class="end-icon" :class="endReasonClass">
            <el-icon :size="48">
              <Phone v-if="endReason === 'completed'" />
              <Close v-else-if="endReason === 'rejected' || endReason === 'cancelled'" />
              <Clock v-else-if="endReason === 'timeout' || endReason === 'missed'" />
              <Warning v-else-if="endReason === 'busy'" />
              <CircleClose v-else />
            </el-icon>
          </div>

          <!-- Message -->
          <h3 class="end-title">{{ endTitle }}</h3>
          <p class="end-message">{{ endMessage }}</p>

          <!-- Duration (if completed) -->
          <p v-if="endReason === 'completed' && duration" class="call-duration">
            {{ $t('call.duration') }}: {{ duration }}
          </p>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { computed } from 'vue'
import { Phone, Close, Clock, Warning, CircleClose } from '@element-plus/icons-vue'
import { useCallStore, CallStatus, CallEndReason } from '@/stores/call'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const callStore = useCallStore()

const visible = computed(() => {
  return callStore.callStatus === CallStatus.ENDED
})

const endReason = computed(() => {
  return callStore.callEndReason
})

const endReasonClass = computed(() => {
  const reason = endReason.value
  if (reason === CallEndReason.COMPLETED) return 'success'
  if (reason === CallEndReason.REJECTED || reason === CallEndReason.CANCELLED) return 'warning'
  if (reason === CallEndReason.BUSY) return 'busy'
  return 'error'
})

const endTitle = computed(() => {
  const reason = endReason.value
  switch (reason) {
    case CallEndReason.COMPLETED:
      return t('call.callEnded')
    case CallEndReason.REJECTED:
      return t('call.callRejected')
    case CallEndReason.CANCELLED:
      return t('call.callCancelled')
    case CallEndReason.BUSY:
      return t('call.userBusy')
    case CallEndReason.TIMEOUT:
      return t('call.noAnswer')
    case CallEndReason.MISSED:
      return t('call.missedCall')
    case CallEndReason.FAILED:
    default:
      return t('call.callFailed')
  }
})

const endMessage = computed(() => {
  const reason = endReason.value
  const remoteName = callStore.currentCall?.remoteUser?.name || ''

  switch (reason) {
    case CallEndReason.COMPLETED:
      return ''
    case CallEndReason.REJECTED:
      return t('call.rejectedMessage', { name: remoteName })
    case CallEndReason.CANCELLED:
      return t('call.cancelledMessage')
    case CallEndReason.BUSY:
      return t('call.busyMessage', { name: remoteName })
    case CallEndReason.TIMEOUT:
      return t('call.timeoutMessage', { name: remoteName })
    case CallEndReason.MISSED:
      return t('call.missedMessage', { name: remoteName })
    case CallEndReason.FAILED:
    default:
      return t('call.failedMessage')
  }
})

const duration = computed(() => {
  return callStore.formattedDuration
})
</script>

<style scoped>
.call-end-overlay {
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

.call-end-modal {
  text-align: center;
  padding: 40px;
}

.end-icon {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 24px;
  color: #fff;
}

.end-icon.success {
  background: #ffffff;
  color: #2f3131;
  transform: rotate(135deg);
}

.end-icon.warning {
  background: #c4c7c8;
  color: #131316;
}

.end-icon.busy {
  background: #8e9192;
  color: #131316;
}

.end-icon.error {
  background: #b3261e;
}

.end-title {
  font-size: 24px;
  font-weight: 600;
  color: #fff;
  margin: 0 0 8px 0;
}

.end-message {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.6);
  margin: 0;
}

.call-duration {
  margin-top: 16px;
  font-size: 16px;
  color: rgba(255, 255, 255, 0.8);
}

/* Transition */
.end-modal-enter-active,
.end-modal-leave-active {
  transition: all 0.3s ease;
}

.end-modal-enter-from,
.end-modal-leave-to {
  opacity: 0;
}

.end-modal-enter-from .call-end-modal,
.end-modal-leave-to .call-end-modal {
  transform: scale(0.9);
}
</style>
