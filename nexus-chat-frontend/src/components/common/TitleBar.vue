<template>
  <div class="title-bar" :class="{ 'is-mac': isMac }">
    <!-- Drag region - allows window dragging -->
    <div class="drag-region">
      <div class="app-title">
        <span class="app-icon">💬</span>
        <span class="app-name">Nexus Chat</span>
      </div>
    </div>
    
    <!-- Window controls -->
    <div class="window-controls" v-if="!isMac">
      <button class="control-btn minimize" @click="minimize" title="最小化">
        <svg width="12" height="12" viewBox="0 0 12 12">
          <rect width="10" height="1" x="1" y="6" fill="currentColor"/>
        </svg>
      </button>
      <button class="control-btn maximize" @click="maximize" title="最大化">
        <svg width="12" height="12" viewBox="0 0 12 12">
          <rect width="9" height="9" x="1.5" y="1.5" fill="none" stroke="currentColor" stroke-width="1"/>
        </svg>
      </button>
      <button class="control-btn close" @click="close" title="关闭">
        <svg width="12" height="12" viewBox="0 0 12 12">
          <path d="M1 1 L11 11 M1 11 L11 1" stroke="currentColor" stroke-width="1.5"/>
        </svg>
      </button>
    </div>
    
    <!-- Mac traffic light spacer -->
    <div class="mac-spacer" v-if="isMac"></div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'

const isMac = ref(false)

onMounted(() => {
  // Check if running in Electron and get platform
  if (window.electronAPI) {
    isMac.value = window.electronAPI.platform === 'darwin'
  }
})

const minimize = () => {
  if (window.electronAPI) {
    window.electronAPI.minimizeWindow()
  }
}

const maximize = () => {
  if (window.electronAPI) {
    window.electronAPI.maximizeWindow()
  }
}

const close = () => {
  if (window.electronAPI) {
    window.electronAPI.closeWindow()
  }
}
</script>

<style scoped>
.title-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 32px;
  background: linear-gradient(135deg, #469dd7 0%, #2969d0 100%);
  color: #ffffff;
  user-select: none;
  -webkit-app-region: drag;
  position: relative;
  z-index: 9999;
  flex-shrink: 0;
}

.title-bar.is-mac {
  padding-left: 78px; /* Space for macOS traffic lights */
}

.drag-region {
  flex: 1;
  display: flex;
  align-items: center;
  height: 100%;
  padding-left: 12px;
}

.app-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.3px;
}

.app-icon {
  font-size: 14px;
}

.app-name {
  opacity: 0.95;
}

.mac-spacer {
  width: 8px;
}

.window-controls {
  display: flex;
  align-items: center;
  height: 100%;
  -webkit-app-region: no-drag;
}

.control-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 100%;
  border: none;
  background: transparent;
  color: rgba(255, 255, 255, 0.9);
  cursor: pointer;
  transition: background-color 0.15s ease;
  -webkit-app-region: no-drag;
}

.control-btn:hover {
  background: rgba(255, 255, 255, 0.1);
}

.control-btn.close:hover {
  background: #e81123;
  color: #ffffff;
}

.control-btn:active {
  background: rgba(255, 255, 255, 0.2);
}

.control-btn.close:active {
  background: #bf0f1d;
}
</style>
