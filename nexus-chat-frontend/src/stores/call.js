import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import websocket from '@/services/websocket'
import webrtc from '@/services/webrtc'
import { useUserStore } from '@/stores/user'

/**
 * Call status enum
 */
export const CallStatus = {
    IDLE: 'idle',
    RINGING: 'ringing',         // Incoming call ringing
    CALLING: 'calling',         // Outgoing call waiting for answer
    CONNECTING: 'connecting',   // Setting up WebRTC connection
    CONNECTED: 'connected',     // Call in progress
    ENDED: 'ended'
}

/**
 * Call end reason
 */
export const CallEndReason = {
    COMPLETED: 'completed',     // Normal hang up
    REJECTED: 'rejected',       // Callee rejected
    CANCELLED: 'cancelled',     // Caller cancelled
    BUSY: 'busy',               // Callee is busy
    TIMEOUT: 'timeout',         // No answer timeout
    FAILED: 'failed',           // Connection failed
    MISSED: 'missed'            // Missed call
}

/**
 * Generate UUID v4
 */
function generateCallId() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = Math.random() * 16 | 0
        return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16)
    })
}

export const useCallStore = defineStore('call', () => {
    // Current call state
    const currentCall = ref(null)
    const callStatus = ref(CallStatus.IDLE)
    const callEndReason = ref(null)

    // Media state
    const isMuted = ref(false)
    const isSpeakerOn = ref(true)
    const isVideoEnabled = ref(true)
    const isFrontCamera = ref(true)

    // Media streams
    const localStream = ref(null)
    const remoteStream = ref(null)

    // Call timing
    const callStartTime = ref(null)
    const callDuration = ref(0)
    let durationTimer = null

    // Incoming call queue
    const incomingCalls = ref([])

    // Ring timeout (60 seconds)
    const RING_TIMEOUT = 60000
    let ringTimeoutId = null

    // Ringtone audio
    let ringtone = null
    let ringbackTone = null

    // Computed
    const isInCall = computed(() => {
        return callStatus.value === CallStatus.CONNECTED ||
            callStatus.value === CallStatus.CONNECTING
    })

    const hasIncomingCall = computed(() => {
        return callStatus.value === CallStatus.RINGING
    })

    const isOutgoingCall = computed(() => {
        return callStatus.value === CallStatus.CALLING
    })

    const formattedDuration = computed(() => {
        const seconds = callDuration.value
        const h = Math.floor(seconds / 3600)
        const m = Math.floor((seconds % 3600) / 60)
        const s = seconds % 60
        if (h > 0) {
            return `${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
        }
        return `${m}:${s.toString().padStart(2, '0')}`
    })

    /**
     * Initialize call service - set up WebSocket callback
     */
    function initialize() {
        websocket.setCallSignalCallback(handleCallSignal)
    }

    /**
     * Handle incoming call signals
     */
    function handleCallSignal(data) {
        const { type, payload } = data
        const userStore = useUserStore()
        const currentUserId = userStore.currentUser?.id

        console.log('=== Call Signal Received ===')
        console.log('Type:', type)
        console.log('Payload:', JSON.stringify(payload, null, 2))
        console.log('Current user ID:', currentUserId)
        console.log('Current call ID:', currentCall.value?.callId)
        console.log('=============================')

        switch (type) {
            case 'CALL_INVITE':
                handleIncomingCall(payload)
                break
            case 'CALL_ACCEPT':
                if (currentCall.value?.callId === payload.callId) {
                    handleCallAccepted(payload)
                }
                break
            case 'CALL_REJECT':
                if (currentCall.value?.callId === payload.callId) {
                    handleCallRejected(payload)
                }
                break
            case 'CALL_CANCEL':
                if (currentCall.value?.callId === payload.callId) {
                    handleCallCancelled(payload)
                }
                break
            case 'CALL_BUSY':
                if (currentCall.value?.callId === payload.callId) {
                    handleCallBusy(payload)
                }
                break
            case 'CALL_TIMEOUT':
                if (currentCall.value?.callId === payload.callId) {
                    handleCallTimeout(payload)
                }
                break
            case 'CALL_END':
                if (currentCall.value?.callId === payload.callId) {
                    handleCallEnded(payload)
                }
                break
            case 'CALL_OFFER':
                if (currentCall.value?.callId === payload.callId) {
                    handleOffer(payload)
                }
                break
            case 'CALL_ANSWER':
                if (currentCall.value?.callId === payload.callId) {
                    handleAnswer(payload)
                }
                break
            case 'CALL_ICE_CANDIDATE':
                if (currentCall.value?.callId === payload.callId) {
                    handleIceCandidate(payload)
                }
                break
            case 'CALL_MUTE':
                if (currentCall.value?.callId === payload.callId) {
                    handleRemoteMute(payload)
                }
                break
            case 'CALL_VIDEO_TOGGLE':
                if (currentCall.value?.callId === payload.callId) {
                    handleRemoteVideoToggle(payload)
                }
                break
        }
    }

    /**
     * Handle incoming call
     */
    function handleIncomingCall(payload) {
        const userStore = useUserStore()

        // If already in a call, send busy
        if (isInCall.value || callStatus.value !== CallStatus.IDLE) {
            websocket.sendCallBusy(payload.callId, payload.callerId, userStore.currentUser.id)
            return
        }

        // Set current call
        currentCall.value = {
            callId: payload.callId,
            callType: payload.callType,
            direction: 'incoming',
            remoteUser: {
                id: payload.callerId,
                name: payload.callerName || 'Unknown',
                avatar: payload.callerAvatar || ''
            },
            startTime: null
        }

        callStatus.value = CallStatus.RINGING
        playRingtone()
        startRingTimeout()

        // Electron notification
        if (window.electronAPI) {
            window.electronAPI.showNotification(
                payload.callType === 'video' ? 'Video Call' : 'Voice Call',
                `${payload.callerName || 'Someone'} is calling...`
            )
        }
    }

    /**
     * Initiate outgoing call
     */
    async function initiateCall(remoteUser, callType = 'audio') {
        const userStore = useUserStore()

        if (!webrtc.constructor.isSupported()) {
            throw new Error('WebRTC is not supported in this browser')
        }

        if (isInCall.value || callStatus.value !== CallStatus.IDLE) {
            throw new Error('Already in a call')
        }

        // Check permissions first
        const permissionResult = await webrtc.constructor.checkPermissions(callType)
        if (!permissionResult.granted) {
            throw new Error(permissionResult.error || 'Permission denied')
        }

        // For Electron, also check system permissions
        const electronPermissions = await webrtc.constructor.requestElectronPermissions(callType)
        if (!electronPermissions) {
            throw new Error('System permission denied. Please allow microphone/camera access in system settings.')
        }

        const callId = generateCallId()

        // Set current call
        currentCall.value = {
            callId,
            callType,
            direction: 'outgoing',
            remoteUser: {
                id: remoteUser.id,
                name: remoteUser.nickname || remoteUser.name || 'Unknown',
                avatar: remoteUser.avatar || remoteUser.avatarUrl || ''
            },
            startTime: null
        }

        callStatus.value = CallStatus.CALLING

        try {
            // Get local media stream
            const stream = await webrtc.getLocalStream(callType)
            localStream.value = stream
            isVideoEnabled.value = callType === 'video'

            // Send call invite
            websocket.sendCallInvite(
                callId,
                callType,
                userStore.currentUser.id,
                remoteUser.id
            )

            // Play ringback tone
            playRingbackTone()
            startRingTimeout()

        } catch (error) {
            console.error('Failed to initiate call:', error)
            resetCallState()
            throw error
        }
    }

    /**
     * Accept incoming call
     */
    async function acceptCall() {
        if (!currentCall.value || callStatus.value !== CallStatus.RINGING) {
            return
        }

        const userStore = useUserStore()
        stopRingtone()
        clearRingTimeout()

        // Check permissions first before accepting
        const callType = currentCall.value.callType
        const permissionResult = await webrtc.constructor.checkPermissions(callType)
        if (!permissionResult.granted) {
            console.error('Permission denied:', permissionResult.error)
            // Reject the call due to permission issues
            websocket.sendCallReject(
                currentCall.value.callId,
                currentCall.value.remoteUser.id,
                userStore.currentUser.id,
                'permission_denied'
            )
            callEndReason.value = CallEndReason.FAILED
            callStatus.value = CallStatus.ENDED
            setTimeout(() => {
                resetCallState()
            }, 2000)
            throw new Error(permissionResult.error || 'Permission denied')
        }

        // For Electron, also check system permissions
        const electronPermissions = await webrtc.constructor.requestElectronPermissions(callType)
        if (!electronPermissions) {
            websocket.sendCallReject(
                currentCall.value.callId,
                currentCall.value.remoteUser.id,
                userStore.currentUser.id,
                'permission_denied'
            )
            callEndReason.value = CallEndReason.FAILED
            callStatus.value = CallStatus.ENDED
            setTimeout(() => {
                resetCallState()
            }, 2000)
            throw new Error('System permission denied. Please allow microphone/camera access in system settings.')
        }

        callStatus.value = CallStatus.CONNECTING

        try {
            // Get local media stream
            const stream = await webrtc.getLocalStream(currentCall.value.callType)
            localStream.value = stream
            isVideoEnabled.value = currentCall.value.callType === 'video'

            // Create peer connection
            webrtc.createPeerConnection()

            // Set up WebRTC callbacks
            setupWebRTCCallbacks()

            // Send accept signal
            websocket.sendCallAccept(
                currentCall.value.callId,
                currentCall.value.remoteUser.id,
                userStore.currentUser.id
            )

        } catch (error) {
            console.error('Failed to accept call:', error)
            endCall(CallEndReason.FAILED)
            throw error
        }
    }

    /**
     * Reject incoming call
     */
    function rejectCall(reason = 'rejected') {
        if (!currentCall.value || callStatus.value !== CallStatus.RINGING) {
            return
        }

        const userStore = useUserStore()
        stopRingtone()
        clearRingTimeout()

        websocket.sendCallReject(
            currentCall.value.callId,
            currentCall.value.remoteUser.id,
            userStore.currentUser.id,
            reason
        )

        callEndReason.value = CallEndReason.REJECTED
        callStatus.value = CallStatus.ENDED

        setTimeout(() => {
            resetCallState()
        }, 2000)
    }

    /**
     * Cancel outgoing call
     */
    function cancelCall() {
        if (!currentCall.value || callStatus.value !== CallStatus.CALLING) {
            return
        }

        const userStore = useUserStore()
        stopRingbackTone()
        clearRingTimeout()

        websocket.sendCallCancel(
            currentCall.value.callId,
            userStore.currentUser.id,
            currentCall.value.remoteUser.id
        )

        callEndReason.value = CallEndReason.CANCELLED
        callStatus.value = CallStatus.ENDED

        setTimeout(() => {
            resetCallState()
        }, 2000)
    }

    /**
     * End active call
     */
    function endCall(reason = CallEndReason.COMPLETED) {
        if (!currentCall.value) {
            return
        }

        const userStore = useUserStore()
        const duration = callDuration.value

        stopAllAudio()
        clearRingTimeout()
        stopDurationTimer()

        websocket.sendCallEnd(
            currentCall.value.callId,
            userStore.currentUser.id,
            currentCall.value.remoteUser.id,
            duration
        )

        callEndReason.value = reason
        callStatus.value = CallStatus.ENDED

        // Cleanup WebRTC
        webrtc.cleanup()

        setTimeout(() => {
            resetCallState()
        }, 2000)
    }

    /**
     * Handle call accepted by remote
     */
    async function handleCallAccepted(payload) {
        console.log('Call accepted by remote:', payload)
        stopRingbackTone()
        clearRingTimeout()

        callStatus.value = CallStatus.CONNECTING

        try {
            // Create peer connection
            console.log('Creating peer connection (caller side)...')
            webrtc.createPeerConnection()

            // Set up WebRTC callbacks
            setupWebRTCCallbacks()

            // Create and send offer (caller initiates WebRTC)
            console.log('Creating SDP offer...')
            const offer = await webrtc.createOffer()
            console.log('SDP offer created successfully')

            const userStore = useUserStore()

            console.log('Sending CALL_OFFER:', {
                callId: currentCall.value.callId,
                callerId: userStore.currentUser.id,
                calleeId: currentCall.value.remoteUser.id
            })

            websocket.sendCallOffer(
                currentCall.value.callId,
                userStore.currentUser.id,
                currentCall.value.remoteUser.id,
                offer
            )

        } catch (error) {
            console.error('Failed to create offer:', error)
            endCall(CallEndReason.FAILED)
        }
    }

    /**
     * Handle call rejected by remote
     */
    function handleCallRejected(payload) {
        stopRingbackTone()
        clearRingTimeout()

        callEndReason.value = CallEndReason.REJECTED
        callStatus.value = CallStatus.ENDED

        setTimeout(() => {
            resetCallState()
        }, 2000)
    }

    /**
     * Handle call cancelled by caller
     */
    function handleCallCancelled(payload) {
        stopRingtone()
        clearRingTimeout()

        callEndReason.value = CallEndReason.CANCELLED
        callStatus.value = CallStatus.ENDED

        setTimeout(() => {
            resetCallState()
        }, 2000)
    }

    /**
     * Handle call busy
     */
    function handleCallBusy(payload) {
        stopRingbackTone()
        clearRingTimeout()

        callEndReason.value = CallEndReason.BUSY
        callStatus.value = CallStatus.ENDED

        setTimeout(() => {
            resetCallState()
        }, 2000)
    }

    /**
     * Handle call timeout
     */
    function handleCallTimeout(payload) {
        stopAllAudio()
        clearRingTimeout()

        callEndReason.value = CallEndReason.TIMEOUT
        callStatus.value = CallStatus.ENDED

        setTimeout(() => {
            resetCallState()
        }, 2000)
    }

    /**
     * Handle call ended by remote
     */
    function handleCallEnded(payload) {
        stopAllAudio()
        clearRingTimeout()
        stopDurationTimer()

        callEndReason.value = CallEndReason.COMPLETED
        callStatus.value = CallStatus.ENDED

        webrtc.cleanup()

        setTimeout(() => {
            resetCallState()
        }, 2000)
    }

    /**
     * Handle WebRTC offer from remote
     */
    async function handleOffer(payload) {
        try {
            const answer = await webrtc.createAnswer(payload.sdp)

            // If answer is null, it means we already processed this offer
            if (!answer) {
                console.log('Offer already processed, skipping')
                return
            }

            const userStore = useUserStore()

            // For CALL_ANSWER: callerId should be the original caller (who initiated the call)
            // If we are the callee (incoming call), remoteUser is the original caller
            // callerId = original caller, calleeId = original callee
            const isIncoming = currentCall.value.direction === 'incoming'
            const originalCallerId = isIncoming ? currentCall.value.remoteUser.id : userStore.currentUser.id
            const originalCalleeId = isIncoming ? userStore.currentUser.id : currentCall.value.remoteUser.id

            console.log('Sending CALL_ANSWER:', {
                callId: currentCall.value.callId,
                callerId: originalCallerId,
                calleeId: originalCalleeId,
                direction: currentCall.value.direction
            })

            websocket.sendCallAnswer(
                currentCall.value.callId,
                originalCallerId,
                originalCalleeId,
                answer
            )
        } catch (error) {
            console.error('Failed to create answer:', error)
            endCall(CallEndReason.FAILED)
        }
    }

    /**
     * Handle WebRTC answer from remote
     */
    async function handleAnswer(payload) {
        console.log('Received CALL_ANSWER from remote:', {
            callId: payload.callId,
            hasSdp: !!payload.sdp
        })
        try {
            await webrtc.setRemoteAnswer(payload.sdp)
            console.log('Remote answer set successfully')
        } catch (error) {
            // InvalidStateError is handled inside setRemoteAnswer, but just in case
            if (error.name === 'InvalidStateError') {
                console.warn('Ignoring InvalidStateError in handleAnswer')
                return
            }
            console.error('Failed to set remote answer:', error)
            endCall(CallEndReason.FAILED)
        }
    }

    /**
     * Handle ICE candidate from remote
     */
    async function handleIceCandidate(payload) {
        console.log('Received ICE candidate from remote:', {
            callId: payload.callId,
            candidateType: payload.candidate?.type
        })
        try {
            await webrtc.addIceCandidate(payload.candidate)
        } catch (error) {
            console.error('Failed to add ICE candidate:', error)
        }
    }

    /**
     * Handle remote mute status change
     */
    function handleRemoteMute(payload) {
        if (currentCall.value) {
            currentCall.value.remoteIsMuted = payload.isMuted
        }
    }

    /**
     * Handle remote video toggle
     */
    function handleRemoteVideoToggle(payload) {
        if (currentCall.value) {
            currentCall.value.remoteVideoEnabled = payload.isVideoEnabled
        }
    }

    /**
     * Set up WebRTC callbacks
     */
    function setupWebRTCCallbacks() {
        const userStore = useUserStore()

        // Handle remote stream
        webrtc.onRemoteStream = (stream) => {
            console.log('Remote stream received in call store')
            console.log('Remote stream id:', stream?.id)
            console.log('Remote audio tracks:', stream?.getAudioTracks().length)
            console.log('Remote video tracks:', stream?.getVideoTracks().length)

            // Force Vue reactivity by setting to null first, then to the new stream
            remoteStream.value = null

            // Use nextTick to ensure reactivity
            setTimeout(() => {
                remoteStream.value = stream
                console.log('remoteStream.value set, has value:', !!remoteStream.value)

                // Log track details
                if (stream) {
                    stream.getAudioTracks().forEach((track, i) => {
                        console.log(`Audio track ${i}: enabled=${track.enabled}, readyState=${track.readyState}, muted=${track.muted}`)
                    })
                    stream.getVideoTracks().forEach((track, i) => {
                        console.log(`Video track ${i}: enabled=${track.enabled}, readyState=${track.readyState}, muted=${track.muted}`)
                    })
                }
            }, 0)
        }

        // Handle ICE candidate
        webrtc.onIceCandidate = (candidate) => {
            console.log('Sending ICE candidate to remote:', {
                callId: currentCall.value.callId,
                userId: userStore.currentUser.id,
                remoteUserId: currentCall.value.remoteUser.id,
                candidateType: candidate?.type
            })
            websocket.sendIceCandidate(
                currentCall.value.callId,
                userStore.currentUser.id,
                currentCall.value.remoteUser.id,
                candidate
            )
        }

        // Handle connection state
        webrtc.onConnectionStateChange = (state) => {
            console.log('Connection state changed:', state)
            if (state === 'connected') {
                callStatus.value = CallStatus.CONNECTED
                callStartTime.value = Date.now()
                currentCall.value.startTime = callStartTime.value
                startDurationTimer()
            } else if (state === 'failed' || state === 'disconnected') {
                endCall(CallEndReason.FAILED)
            }
        }

        // Handle ICE connection state
        webrtc.onIceConnectionStateChange = (state) => {
            console.log('ICE connection state changed:', state)
            if (state === 'connected' || state === 'completed') {
                if (callStatus.value === CallStatus.CONNECTING) {
                    callStatus.value = CallStatus.CONNECTED
                    callStartTime.value = Date.now()
                    currentCall.value.startTime = callStartTime.value
                    startDurationTimer()
                }
            }
        }
    }

    /**
     * Toggle mute
     */
    function toggleMute() {
        isMuted.value = !isMuted.value
        webrtc.setAudioMuted(isMuted.value)

        if (currentCall.value) {
            const userStore = useUserStore()
            websocket.sendMuteStatus(
                currentCall.value.callId,
                userStore.currentUser.id,
                currentCall.value.remoteUser.id,
                isMuted.value
            )
        }
    }

    /**
     * Toggle speaker (for future use with audio routing)
     */
    function toggleSpeaker() {
        isSpeakerOn.value = !isSpeakerOn.value
    }

    /**
     * Toggle video
     */
    function toggleVideo() {
        isVideoEnabled.value = !isVideoEnabled.value
        webrtc.setVideoEnabled(isVideoEnabled.value)

        if (currentCall.value) {
            const userStore = useUserStore()
            websocket.sendVideoToggle(
                currentCall.value.callId,
                userStore.currentUser.id,
                currentCall.value.remoteUser.id,
                isVideoEnabled.value
            )
        }
    }

    /**
     * Switch camera
     */
    async function switchCamera() {
        isFrontCamera.value = !isFrontCamera.value
        try {
            const stream = await webrtc.switchCamera(isFrontCamera.value)
            localStream.value = stream
        } catch (error) {
            console.error('Failed to switch camera:', error)
            isFrontCamera.value = !isFrontCamera.value
        }
    }

    /**
     * Start duration timer
     */
    function startDurationTimer() {
        stopDurationTimer()
        durationTimer = setInterval(() => {
            if (callStartTime.value) {
                callDuration.value = Math.floor((Date.now() - callStartTime.value) / 1000)
            }
        }, 1000)
    }

    /**
     * Stop duration timer
     */
    function stopDurationTimer() {
        if (durationTimer) {
            clearInterval(durationTimer)
            durationTimer = null
        }
    }

    /**
     * Start ring timeout
     */
    function startRingTimeout() {
        clearRingTimeout()
        const userStore = useUserStore()

        ringTimeoutId = setTimeout(() => {
            if (callStatus.value === CallStatus.RINGING || callStatus.value === CallStatus.CALLING) {
                websocket.sendCallSignal('CALL_TIMEOUT', {
                    callId: currentCall.value?.callId,
                    callerId: currentCall.value?.direction === 'outgoing'
                        ? userStore.currentUser.id
                        : currentCall.value?.remoteUser.id,
                    calleeId: currentCall.value?.direction === 'outgoing'
                        ? currentCall.value?.remoteUser.id
                        : userStore.currentUser.id
                })
                handleCallTimeout({})
            }
        }, RING_TIMEOUT)
    }

    /**
     * Clear ring timeout
     */
    function clearRingTimeout() {
        if (ringTimeoutId) {
            clearTimeout(ringTimeoutId)
            ringTimeoutId = null
        }
    }

    /**
     * Play ringtone for incoming call
     */
    function playRingtone() {
        try {
            // Use a simple beep pattern if no audio file available
            ringtone = new Audio('/sounds/ringtone.mp3')
            ringtone.loop = true
            ringtone.play().catch(() => {
                console.log('Failed to play ringtone')
            })
        } catch (e) {
            console.log('Ringtone not available')
        }
    }

    /**
     * Stop ringtone
     */
    function stopRingtone() {
        if (ringtone) {
            ringtone.pause()
            ringtone.currentTime = 0
            ringtone = null
        }
    }

    /**
     * Play ringback tone for outgoing call
     */
    function playRingbackTone() {
        try {
            ringbackTone = new Audio('/sounds/ringback.mp3')
            ringbackTone.loop = true
            ringbackTone.play().catch(() => {
                console.log('Failed to play ringback tone')
            })
        } catch (e) {
            console.log('Ringback tone not available')
        }
    }

    /**
     * Stop ringback tone
     */
    function stopRingbackTone() {
        if (ringbackTone) {
            ringbackTone.pause()
            ringbackTone.currentTime = 0
            ringbackTone = null
        }
    }

    /**
     * Stop all audio
     */
    function stopAllAudio() {
        stopRingtone()
        stopRingbackTone()
    }

    /**
     * Reset call state
     */
    function resetCallState() {
        currentCall.value = null
        callStatus.value = CallStatus.IDLE
        callEndReason.value = null
        isMuted.value = false
        isSpeakerOn.value = true
        isVideoEnabled.value = true
        isFrontCamera.value = true
        localStream.value = null
        remoteStream.value = null
        callStartTime.value = null
        callDuration.value = 0
        stopDurationTimer()
        clearRingTimeout()
        stopAllAudio()
    }

    /**
     * Cleanup on logout/disconnect
     */
    function cleanup() {
        if (isInCall.value) {
            endCall(CallEndReason.FAILED)
        }
        resetCallState()
        webrtc.cleanup()
    }

    return {
        // State
        currentCall,
        callStatus,
        callEndReason,
        isMuted,
        isSpeakerOn,
        isVideoEnabled,
        isFrontCamera,
        localStream,
        remoteStream,
        callStartTime,
        callDuration,
        incomingCalls,

        // Computed
        isInCall,
        hasIncomingCall,
        isOutgoingCall,
        formattedDuration,

        // Actions
        initialize,
        initiateCall,
        acceptCall,
        rejectCall,
        cancelCall,
        endCall,
        toggleMute,
        toggleSpeaker,
        toggleVideo,
        switchCamera,
        cleanup,

        // Constants
        CallStatus,
        CallEndReason
    }
})
