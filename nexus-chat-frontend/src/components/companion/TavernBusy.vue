<template>
  <!-- A blocking, explained wait.
       Inviting a guest and closing an evening both end in a model call, which
       can take many seconds. A spinner on the button alone reads as "nothing
       happened" — this says what is being waited for, and keeps the user from
       clicking again into a duplicate request. -->
  <Transition name="busy-fade">
    <div v-if="visible" class="busy-scrim" role="status" aria-live="polite">
      <div class="busy-card">
        <span class="busy-spinner"></span>
        <div class="busy-text">
          <div class="busy-title">{{ title }}</div>
          <div v-if="hint" class="busy-hint">{{ hint }}</div>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup>
defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '' },
  hint: { type: String, default: '' }
})
</script>

<style scoped>
.busy-scrim {
  position: fixed;
  inset: 0;
  z-index: 55;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(6, 6, 8, 0.55);
  backdrop-filter: blur(2px);
}

.busy-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 22px;
  border-radius: 12px;
  background: rgba(19, 19, 22, 0.97);
  border: 1px solid rgba(68, 71, 72, 0.5);
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.6);
  max-width: 320px;
}

.busy-spinner {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  border: 2px solid rgba(142, 145, 146, 0.28);
  border-top-color: #ffffff;
  border-radius: 9999px;
  animation: busy-spin 0.8s linear infinite;
}

@keyframes busy-spin {
  to { transform: rotate(360deg); }
}

.busy-text {
  min-width: 0;
}

.busy-title {
  color: #ffffff;
  font-size: 13px;
  font-weight: 500;
}

.busy-hint {
  margin-top: 3px;
  color: rgba(142, 145, 146, 0.85);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10.5px;
  line-height: 1.5;
}

.busy-fade-enter-active,
.busy-fade-leave-active {
  transition: opacity 0.18s ease;
}

.busy-fade-enter-from,
.busy-fade-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .busy-spinner { animation-duration: 2s; }
}
</style>
