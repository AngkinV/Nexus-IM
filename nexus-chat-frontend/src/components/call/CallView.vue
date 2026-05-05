<template>
  <Teleport to="body">
    <Transition name="call-view">
      <div v-if="visible" class="call-view-overlay">
        <div class="call-view">
          <!-- Header -->
          <div class="call-header">
            <div class="call-info">
              <span class="call-duration">{{ callStore.formattedDuration }}</span>
              <span class="call-quality" :class="qualityClass">
                <el-icon><Connection /></el-icon>
              </span>
            </div>
            <div class="call-status-text" v-if="statusText">
              {{ statusText }}
            </div>
          </div>

          <!-- Video area -->
          <div class="video-container" :class="{ 'audio-only': !isVideoCall }">
            <!-- Remote video (full screen) -->
            <div class="remote-video-wrapper" v-if="isVideoCall">
              <video
                ref="remoteVideoRef"
                class="remote-video"
                autoplay
                playsinline
              ></video>
              <!-- Remote video off placeholder -->
              <div v-if="!hasRemoteVideo" class="video-off-placeholder">
                <img
                  v-if="remoteAvatar"
                  :src="remoteAvatar"
                  class="placeholder-avatar"
                  alt="Remote"
                />
                <div v-else class="placeholder-avatar-text">
                  {{ remoteInitial }}
                </div>
                <p class="video-off-text">{{ $t('call.cameraOff') }}</p>
              </div>
            </div>

            <!-- Audio only view -->
            <div v-else class="audio-only-view">
              <div class="audio-avatar-container">
                <img
                  v-if="remoteAvatar"
                  :src="remoteAvatar"
                  class="audio-avatar"
                  alt="Remote"
                />
                <div v-else class="audio-avatar-placeholder">
                  {{ remoteInitial }}
                </div>
                <!-- Audio wave animation -->
                <div class="audio-wave" v-if="callStore.callStatus === 'connected'">
                  <span></span>
                  <span></span>
                  <span></span>
                  <span></span>
                  <span></span>
                </div>
              </div>
              <h2 class="remote-name">{{ remoteName }}</h2>
            </div>

            <!-- Hidden audio element for remote audio (important for audio-only calls) -->
            <audio ref="remoteAudioRef" autoplay playsinline style="display: none;"></audio>

            <!-- Local video (PiP) -->
            <div
              v-if="isVideoCall"
              class="local-video-wrapper"
              :class="{ 'video-off': !callStore.isVideoEnabled }"
            >
              <video
                ref="localVideoRef"
                class="local-video"
                autoplay
                playsinline
                muted
              ></video>
              <div v-if="!callStore.isVideoEnabled" class="local-video-off">
                <el-icon :size="24"><VideoPause /></el-icon>
              </div>
            </div>
          </div>

          <!-- Controls -->
          <div class="call-controls">
            <div class="control-row">
              <!-- Mute button -->
              <button
                class="control-btn"
                :class="{ active: callStore.isMuted }"
                @click="callStore.toggleMute()"
              >
                <div class="btn-icon">
                  <el-icon :size="24">
                    <Mute v-if="callStore.isMuted" />
                    <Microphone v-else />
                  </el-icon>
                </div>
                <span class="btn-label">
                  {{ callStore.isMuted ? $t('call.unmute') : $t('call.mute') }}
                </span>
              </button>

              <!-- Video toggle (only for video calls) -->
              <button
                v-if="isVideoCall"
                class="control-btn"
                :class="{ active: !callStore.isVideoEnabled }"
                @click="callStore.toggleVideo()"
              >
                <div class="btn-icon">
                  <el-icon :size="24">
                    <VideoPause v-if="!callStore.isVideoEnabled" />
                    <VideoCamera v-else />
                  </el-icon>
                </div>
                <span class="btn-label">
                  {{ callStore.isVideoEnabled ? $t('call.hideVideo') : $t('call.showVideo') }}
                </span>
              </button>

              <!-- Switch camera (only for video calls) -->
              <button
                v-if="isVideoCall"
                class="control-btn"
                @click="callStore.switchCamera()"
              >
                <div class="btn-icon">
                  <el-icon :size="24"><Switch /></el-icon>
                </div>
                <span class="btn-label">{{ $t('call.switchCamera') }}</span>
              </button>

              <!-- Speaker toggle -->
              <button
                class="control-btn"
                :class="{ active: !callStore.isSpeakerOn }"
                @click="callStore.toggleSpeaker()"
              >
                <div class="btn-icon">
                  <el-icon :size="24">
                    <Mute v-if="!callStore.isSpeakerOn" />
                    <Bell v-else />
                  </el-icon>
                </div>
                <span class="btn-label">{{ $t('call.speaker') }}</span>
              </button>
            </div>

            <!-- End call button -->
            <button class="end-call-btn" @click="handleEndCall">
              <el-icon :size="28"><Phone /></el-icon>
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import {
  Phone,
  VideoCamera,
  VideoPause,
  Microphone,
  Mute,
  Bell,
  Switch,
  Connection
} from '@element-plus/icons-vue'
import { useCallStore, CallStatus } from '@/stores/call'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const callStore = useCallStore()

const localVideoRef = ref(null)
const remoteVideoRef = ref(null)
const remoteAudioRef = ref(null)

const visible = computed(() => {
  return callStore.callStatus === CallStatus.CONNECTING ||
    callStore.callStatus === CallStatus.CONNECTED
})

const isVideoCall = computed(() => {
  return callStore.currentCall?.callType === 'video'
})

const remoteName = computed(() => {
  return callStore.currentCall?.remoteUser?.name || 'Unknown'
})

const remoteAvatar = computed(() => {
  return callStore.currentCall?.remoteUser?.avatar || ''
})

const remoteInitial = computed(() => {
  const name = remoteName.value
  return name ? name.charAt(0).toUpperCase() : '?'
})

const hasRemoteVideo = computed(() => {
  const stream = callStore.remoteStream
  if (!stream) return false
  const videoTracks = stream.getVideoTracks()
  return videoTracks.length > 0 && videoTracks.some(t => t.enabled && t.readyState === 'live')
})

const statusText = computed(() => {
  if (callStore.callStatus === CallStatus.CONNECTING) {
    return t('call.connecting')
  }
  return ''
})

const qualityClass = computed(() => {
  // Placeholder for actual network quality detection
  return 'good'
})

function handleEndCall() {
  callStore.endCall()
}

// Helper function to set srcObject on video/audio elements
function setMediaElementSource(element, stream, elementName) {
  if (!element) {
    console.log(`${elementName} element is null`)
    return
  }

  console.log(`Setting ${elementName} srcObject:`, stream?.id)

  if (stream) {
    // Log tracks info
    const audioTracks = stream.getAudioTracks()
    const videoTracks = stream.getVideoTracks()
    console.log(`${elementName} stream has ${audioTracks.length} audio tracks, ${videoTracks.length} video tracks`)

    element.srcObject = stream

    // Try to play the element
    element.play().then(() => {
      console.log(`${elementName} playback started successfully`)
    }).catch(err => {
      console.warn(`${elementName} autoplay failed:`, err.message)
      // Try to play again after user interaction
    })
  } else {
    element.srcObject = null
  }
}

// Update video elements when streams change
watch(() => callStore.localStream, (stream) => {
  console.log('Local stream changed:', stream?.id)
  nextTick(() => {
    setMediaElementSource(localVideoRef.value, stream, 'localVideo')
  })
}, { immediate: true })

watch(() => callStore.remoteStream, (stream) => {
  console.log('Remote stream changed in CallView:', stream?.id)

  nextTick(() => {
    // Set remote video element
    setMediaElementSource(remoteVideoRef.value, stream, 'remoteVideo')

    // Also set audio element for audio-only calls or as backup
    if (remoteAudioRef.value && stream) {
      setMediaElementSource(remoteAudioRef.value, stream, 'remoteAudio')
    }
  })
}, { immediate: true })

// Also watch for visibility changes to re-attach streams
watch(visible, (isVisible) => {
  if (isVisible) {
    console.log('CallView became visible, re-attaching streams')
    nextTick(() => {
      if (callStore.localStream) {
        setMediaElementSource(localVideoRef.value, callStore.localStream, 'localVideo')
      }
      if (callStore.remoteStream) {
        setMediaElementSource(remoteVideoRef.value, callStore.remoteStream, 'remoteVideo')
        setMediaElementSource(remoteAudioRef.value, callStore.remoteStream, 'remoteAudio')
      }
    })
  }
}, { immediate: true })

// Prevent screen sleep during call (Electron)
onMounted(() => {
  console.log('CallView mounted')
  if (window.electronAPI) {
    window.electronAPI.preventDisplaySleep?.(true)
  }
})

onUnmounted(() => {
  console.log('CallView unmounted')
  if (window.electronAPI) {
    window.electronAPI.preventDisplaySleep?.(false)
  }
})
</script>

<style scoped>
.call-view-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: #000;
  z-index: 9998;
  display: flex;
  flex-direction: column;
}

.call-view {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
}

/* Header */
.call-header {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  padding: 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  z-index: 10;
  background: linear-gradient(to bottom, rgba(0, 0, 0, 0.6) 0%, transparent 100%);
}

.call-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.call-duration {
  font-size: 18px;
  font-weight: 500;
  color: #fff;
  font-variant-numeric: tabular-nums;
}

.call-quality {
  display: flex;
  align-items: center;
  color: #4caf50;
}

.call-quality.good {
  color: #4caf50;
}

.call-quality.medium {
  color: #ff9800;
}

.call-quality.poor {
  color: #f44336;
}

.call-status-text {
  margin-top: 4px;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.7);
}

/* Video container */
.video-container {
  flex: 1;
  position: relative;
  overflow: hidden;
}

.remote-video-wrapper {
  width: 100%;
  height: 100%;
  position: relative;
}

.remote-video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.video-off-placeholder {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
}

.placeholder-avatar {
  width: 120px;
  height: 120px;
  border-radius: 50%;
  object-fit: cover;
  border: 4px solid rgba(255, 255, 255, 0.2);
}

.placeholder-avatar-text {
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
}

.video-off-text {
  margin-top: 16px;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.5);
}

/* Local video PiP */
.local-video-wrapper {
  position: absolute;
  bottom: 140px;
  right: 20px;
  width: 120px;
  height: 160px;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.5);
  border: 2px solid rgba(255, 255, 255, 0.2);
}

.local-video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scaleX(-1);
}

.local-video-wrapper.video-off {
  background: #333;
}

.local-video-off {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.8);
  color: #fff;
}

/* Audio only view */
.audio-only-view {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
}

.audio-avatar-container {
  position: relative;
  margin-bottom: 20px;
}

.audio-avatar {
  width: 150px;
  height: 150px;
  border-radius: 50%;
  object-fit: cover;
  border: 4px solid rgba(255, 255, 255, 0.2);
}

.audio-avatar-placeholder {
  width: 150px;
  height: 150px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 60px;
  font-weight: 600;
  color: #fff;
}

.audio-wave {
  position: absolute;
  bottom: -30px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 20px;
}

.audio-wave span {
  width: 4px;
  background: #4caf50;
  border-radius: 2px;
  animation: wave 1s ease-in-out infinite;
}

.audio-wave span:nth-child(1) { animation-delay: 0s; height: 8px; }
.audio-wave span:nth-child(2) { animation-delay: 0.1s; height: 16px; }
.audio-wave span:nth-child(3) { animation-delay: 0.2s; height: 12px; }
.audio-wave span:nth-child(4) { animation-delay: 0.3s; height: 18px; }
.audio-wave span:nth-child(5) { animation-delay: 0.4s; height: 10px; }

@keyframes wave {
  0%, 100% {
    transform: scaleY(1);
  }
  50% {
    transform: scaleY(1.5);
  }
}

.remote-name {
  font-size: 24px;
  font-weight: 600;
  color: #fff;
  margin: 0;
}

/* Controls */
.call-controls {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 20px;
  padding-bottom: 40px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24px;
  background: linear-gradient(to top, rgba(0, 0, 0, 0.8) 0%, transparent 100%);
}

.control-row {
  display: flex;
  justify-content: center;
  gap: 24px;
}

.control-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  background: none;
  border: none;
  cursor: pointer;
  transition: transform 0.2s;
}

.control-btn:hover {
  transform: scale(1.1);
}

.control-btn .btn-icon {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.15);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  transition: background 0.2s;
}

.control-btn:hover .btn-icon {
  background: rgba(255, 255, 255, 0.25);
}

.control-btn.active .btn-icon {
  background: #fff;
  color: #333;
}

.control-btn .btn-label {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.8);
}

.end-call-btn {
  width: 70px;
  height: 70px;
  border-radius: 50%;
  background: linear-gradient(135deg, #ff416c 0%, #ff4b2b 100%);
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  cursor: pointer;
  box-shadow: 0 4px 20px rgba(255, 65, 108, 0.4);
  transition: transform 0.2s, box-shadow 0.2s;
  transform: rotate(135deg);
}

.end-call-btn:hover {
  transform: rotate(135deg) scale(1.1);
  box-shadow: 0 6px 30px rgba(255, 65, 108, 0.6);
}

/* Transitions */
.call-view-enter-active,
.call-view-leave-active {
  transition: all 0.3s ease;
}

.call-view-enter-from,
.call-view-leave-to {
  opacity: 0;
}
</style>
