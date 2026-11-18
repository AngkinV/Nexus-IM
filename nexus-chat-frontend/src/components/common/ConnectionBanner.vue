<template>
  <Transition name="conn-banner">
    <div
      v-if="connectionStore.showReconnecting"
      class="connection-banner"
      role="status"
      aria-live="polite"
    >
      <span class="conn-spinner" aria-hidden="true"></span>
      <span class="conn-text">{{ $t('common.reconnecting') }}</span>
    </div>
  </Transition>
</template>

<script setup>
import { useConnectionStore } from '@/stores/connection'

const connectionStore = useConnectionStore()
</script>

<style scoped>
.connection-banner {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 12000;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 7px 16px;
  background: #222225;
  color: #e4e1e6;
  font-size: 13px;
  font-weight: 600;
  border-bottom: 1px solid rgba(68, 71, 72, 0.4);
}

.conn-spinner {
  width: 13px;
  height: 13px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #e4e1e6;
  border-radius: 50%;
  animation: conn-spin 0.8s linear infinite;
  flex-shrink: 0;
}

@keyframes conn-spin {
  to { transform: rotate(360deg); }
}

.conn-banner-enter-active,
.conn-banner-leave-active {
  transition: transform 0.25s ease, opacity 0.25s ease;
}

.conn-banner-enter-from,
.conn-banner-leave-to {
  transform: translateY(-100%);
  opacity: 0;
}
</style>
