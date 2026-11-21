import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useSettingsStore } from '@/stores/settings'

/**
 * Tracks whether the app window is focused, and how long it has been unfocused.
 *
 * Used by the 3D renderers to stop drawing in the background. The rule is:
 * suspend only after the window has been unfocused for `delayMs`, so alt-tabbing
 * for a second does not tear down and rebuild the WebGL loop.
 *
 * Outside Electron this falls back to document visibility, which is what the
 * browser exposes.
 */
export function useWindowFocus({ delayMs = 30_000 } = {}) {
  const settings = useSettingsStore()
  const isFocused = ref(true)
  const isSuspended = ref(false)

  let timer = null
  let cleanup = null

  const clearTimer = () => {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  const setFocused = (focused) => {
    isFocused.value = focused
    clearTimer()

    if (focused) {
      // Coming back is immediate: the user is looking at it now.
      isSuspended.value = false
      return
    }

    if (!settings.suspendOnBlur) {
      isSuspended.value = false
      return
    }

    timer = setTimeout(() => {
      if (!isFocused.value && settings.suspendOnBlur) isSuspended.value = true
    }, delayMs)
  }

  const onVisibility = () => setFocused(document.visibilityState === 'visible')

  onMounted(() => {
    if (window.electronAPI?.onWindowFocus) {
      cleanup = window.electronAPI.onWindowFocus(setFocused)
    } else {
      document.addEventListener('visibilitychange', onVisibility)
      setFocused(document.visibilityState === 'visible')
    }
  })

  onBeforeUnmount(() => {
    clearTimer()
    if (cleanup) cleanup()
    else document.removeEventListener('visibilitychange', onVisibility)
  })

  return { isFocused, isSuspended }
}
