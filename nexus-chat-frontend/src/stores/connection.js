import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

// Tracks the live WebSocket connection state so the UI can reflect real
// connectivity (the user's own online dot, the reconnect banner) instead of
// hard-coding "online".
export const useConnectionStore = defineStore('connection', () => {
  // 'connected' | 'connecting' | 'disconnected'
  const status = ref('disconnected')
  // Becomes true after the first successful connect. Lets us tell an initial
  // connect (no banner) apart from a dropped connection (show banner).
  const hasEverConnected = ref(false)

  const isConnected = computed(() => status.value === 'connected')
  // Only surface the reconnect banner once we've been online and then lost it.
  const showReconnecting = computed(() => hasEverConnected.value && status.value !== 'connected')

  const markConnecting = () => { status.value = 'connecting' }
  const markConnected = () => {
    status.value = 'connected'
    hasEverConnected.value = true
  }
  const markDisconnected = () => {
    status.value = 'disconnected'
    hasEverConnected.value = false
  }

  return { status, isConnected, showReconnecting, markConnecting, markConnected, markDisconnected }
})
