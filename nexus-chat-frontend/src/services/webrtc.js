/**
 * WebRTC Service for Audio/Video Calls
 * Handles peer connection, media streams, and ICE candidates
 */

class WebRTCService {
    constructor() {
        this.peerConnection = null
        this.localStream = null
        this.remoteStream = null
        this.onRemoteStream = null
        this.onIceCandidate = null
        this.onConnectionStateChange = null
        this.onIceConnectionStateChange = null
        this.iceCandidateQueue = []
        this.isRemoteDescriptionSet = false
    }

    /**
     * ICE server configuration
     * Using Google's public STUN servers for NAT traversal
     * In production, add your own TURN server for relay fallback
     */
    get iceServers() {
        return {
            iceServers: [
                { urls: 'stun:stun.l.google.com:19302' },
                { urls: 'stun:stun1.l.google.com:19302' },
                { urls: 'stun:stun2.l.google.com:19302' },
                { urls: 'stun:stun3.l.google.com:19302' },
                { urls: 'stun:stun4.l.google.com:19302' },
                // Add TURN server for production:
                // {
                //     urls: 'turn:your-turn-server.com:3478',
                //     username: 'username',
                //     credential: 'password'
                // }
            ],
            iceCandidatePoolSize: 10
        }
    }

    /**
     * Get local media stream (audio/video)
     * @param {string} callType - 'audio' or 'video'
     * @returns {Promise<MediaStream>}
     */
    async getLocalStream(callType = 'audio') {
        try {
            const constraints = {
                audio: {
                    echoCancellation: true,
                    noiseSuppression: true,
                    autoGainControl: true
                },
                video: callType === 'video' ? {
                    width: { ideal: 1280 },
                    height: { ideal: 720 },
                    facingMode: 'user'
                } : false
            }

            this.localStream = await navigator.mediaDevices.getUserMedia(constraints)
            return this.localStream
        } catch (error) {
            console.error('Failed to get local media stream:', error)
            throw error
        }
    }

    /**
     * Create and configure RTCPeerConnection
     * @returns {RTCPeerConnection}
     */
    createPeerConnection() {
        if (this.peerConnection) {
            this.closePeerConnection()
        }

        this.peerConnection = new RTCPeerConnection(this.iceServers)
        this.isRemoteDescriptionSet = false
        this.iceCandidateQueue = []

        // Handle ICE candidates
        this.peerConnection.onicecandidate = (event) => {
            if (event.candidate && this.onIceCandidate) {
                this.onIceCandidate(event.candidate)
            }
        }

        // Handle remote stream
        this.peerConnection.ontrack = (event) => {
            console.log('Remote track received:', event.track.kind, 'enabled:', event.track.enabled, 'readyState:', event.track.readyState)
            console.log('Event streams count:', event.streams?.length)

            // Use the stream from the event if available (preferred method)
            if (event.streams && event.streams[0]) {
                console.log('Using stream from event.streams[0]')
                this.remoteStream = event.streams[0]

                // Log all tracks in the stream
                const audioTracks = this.remoteStream.getAudioTracks()
                const videoTracks = this.remoteStream.getVideoTracks()
                console.log('Remote stream audio tracks:', audioTracks.length, audioTracks.map(t => ({ enabled: t.enabled, readyState: t.readyState })))
                console.log('Remote stream video tracks:', videoTracks.length, videoTracks.map(t => ({ enabled: t.enabled, readyState: t.readyState })))
            } else {
                // Fallback: create new MediaStream and add tracks
                console.log('No event.streams, creating new MediaStream')
                if (!this.remoteStream) {
                    this.remoteStream = new MediaStream()
                }
                this.remoteStream.addTrack(event.track)
            }

            // Always notify callback with the current stream
            if (this.onRemoteStream) {
                this.onRemoteStream(this.remoteStream)
            }

            // Monitor track events
            event.track.onended = () => {
                console.log('Remote track ended:', event.track.kind)
            }
            event.track.onmute = () => {
                console.log('Remote track muted:', event.track.kind)
            }
            event.track.onunmute = () => {
                console.log('Remote track unmuted:', event.track.kind)
            }
        }

        // Handle connection state changes
        this.peerConnection.onconnectionstatechange = () => {
            console.log('Connection state:', this.peerConnection.connectionState)
            if (this.onConnectionStateChange) {
                this.onConnectionStateChange(this.peerConnection.connectionState)
            }
        }

        // Handle ICE connection state changes
        this.peerConnection.oniceconnectionstatechange = () => {
            console.log('ICE connection state:', this.peerConnection.iceConnectionState)
            if (this.onIceConnectionStateChange) {
                this.onIceConnectionStateChange(this.peerConnection.iceConnectionState)
            }
        }

        // Handle ICE gathering state
        this.peerConnection.onicegatheringstatechange = () => {
            console.log('ICE gathering state:', this.peerConnection.iceGatheringState)
        }

        // Add local tracks to peer connection
        if (this.localStream) {
            this.localStream.getTracks().forEach(track => {
                this.peerConnection.addTrack(track, this.localStream)
            })
        }

        return this.peerConnection
    }

    /**
     * Create SDP Offer (caller side)
     * @returns {Promise<RTCSessionDescriptionInit>}
     */
    async createOffer() {
        if (!this.peerConnection) {
            throw new Error('Peer connection not initialized')
        }

        try {
            const offer = await this.peerConnection.createOffer({
                offerToReceiveAudio: true,
                offerToReceiveVideo: true
            })
            await this.peerConnection.setLocalDescription(offer)
            return offer
        } catch (error) {
            console.error('Failed to create offer:', error)
            throw error
        }
    }

    /**
     * Create SDP Answer (callee side)
     * @param {RTCSessionDescriptionInit} offer - Remote offer
     * @returns {Promise<RTCSessionDescriptionInit>}
     */
    async createAnswer(offer) {
        if (!this.peerConnection) {
            throw new Error('Peer connection not initialized')
        }

        // Check signaling state - only create answer if we haven't already
        const signalingState = this.peerConnection.signalingState
        if (signalingState === 'stable' && this.isRemoteDescriptionSet) {
            console.log('Already have remote description set, skipping createAnswer')
            return null
        }

        try {
            await this.peerConnection.setRemoteDescription(new RTCSessionDescription(offer))
            this.isRemoteDescriptionSet = true

            // Process queued ICE candidates
            await this.processIceCandidateQueue()

            const answer = await this.peerConnection.createAnswer()
            await this.peerConnection.setLocalDescription(answer)
            return answer
        } catch (error) {
            console.error('Failed to create answer:', error)
            throw error
        }
    }

    /**
     * Set remote answer (caller side)
     * @param {RTCSessionDescriptionInit} answer - Remote answer
     */
    async setRemoteAnswer(answer) {
        if (!this.peerConnection) {
            console.warn('Peer connection not initialized, cannot set remote answer')
            return
        }

        // Check if we're in the right state to set remote answer
        // We should only set remote answer when in 'have-local-offer' state
        const signalingState = this.peerConnection.signalingState
        console.log('setRemoteAnswer called, current signaling state:', signalingState)

        if (signalingState === 'stable') {
            console.log('Peer connection already in stable state, skipping setRemoteAnswer')
            return // Already stable, no need to set answer again
        }

        if (signalingState !== 'have-local-offer') {
            console.warn(`Cannot set remote answer in signaling state: ${signalingState}`)
            return
        }

        try {
            await this.peerConnection.setRemoteDescription(new RTCSessionDescription(answer))
            this.isRemoteDescriptionSet = true
            console.log('Remote answer set successfully')

            // Process queued ICE candidates
            await this.processIceCandidateQueue()
        } catch (error) {
            // Don't throw for state errors - they're expected in some race conditions
            if (error.name === 'InvalidStateError') {
                console.warn('InvalidStateError when setting remote answer (race condition), ignoring:', error.message)
                return
            }
            console.error('Failed to set remote answer:', error)
            throw error
        }
    }

    /**
     * Add remote ICE candidate
     * @param {RTCIceCandidateInit} candidate - ICE candidate
     */
    async addIceCandidate(candidate) {
        if (!this.peerConnection) {
            console.warn('Peer connection not initialized, queuing ICE candidate')
            this.iceCandidateQueue.push(candidate)
            return
        }

        // Queue candidate if remote description not set yet
        if (!this.isRemoteDescriptionSet) {
            console.log('Remote description not set, queuing ICE candidate')
            this.iceCandidateQueue.push(candidate)
            return
        }

        try {
            await this.peerConnection.addIceCandidate(new RTCIceCandidate(candidate))
            console.log('ICE candidate added successfully')
        } catch (error) {
            console.error('Failed to add ICE candidate:', error)
        }
    }

    /**
     * Process queued ICE candidates
     */
    async processIceCandidateQueue() {
        console.log(`Processing ${this.iceCandidateQueue.length} queued ICE candidates`)
        while (this.iceCandidateQueue.length > 0) {
            const candidate = this.iceCandidateQueue.shift()
            try {
                await this.peerConnection.addIceCandidate(new RTCIceCandidate(candidate))
            } catch (error) {
                console.error('Failed to add queued ICE candidate:', error)
            }
        }
    }

    /**
     * Toggle audio mute
     * @param {boolean} muted
     */
    setAudioMuted(muted) {
        if (this.localStream) {
            this.localStream.getAudioTracks().forEach(track => {
                track.enabled = !muted
            })
        }
    }

    /**
     * Toggle video enabled
     * @param {boolean} enabled
     */
    setVideoEnabled(enabled) {
        if (this.localStream) {
            this.localStream.getVideoTracks().forEach(track => {
                track.enabled = enabled
            })
        }
    }

    /**
     * Switch camera (front/back)
     * @param {boolean} useFrontCamera
     * @returns {Promise<MediaStream>}
     */
    async switchCamera(useFrontCamera) {
        if (!this.localStream) return null

        const videoTrack = this.localStream.getVideoTracks()[0]
        if (!videoTrack) return this.localStream

        try {
            // Stop current video track
            videoTrack.stop()

            // Get new video track
            const newStream = await navigator.mediaDevices.getUserMedia({
                video: {
                    width: { ideal: 1280 },
                    height: { ideal: 720 },
                    facingMode: useFrontCamera ? 'user' : 'environment'
                }
            })

            const newVideoTrack = newStream.getVideoTracks()[0]

            // Replace track in local stream
            this.localStream.removeTrack(videoTrack)
            this.localStream.addTrack(newVideoTrack)

            // Replace track in peer connection
            if (this.peerConnection) {
                const sender = this.peerConnection.getSenders().find(s =>
                    s.track && s.track.kind === 'video'
                )
                if (sender) {
                    await sender.replaceTrack(newVideoTrack)
                }
            }

            return this.localStream
        } catch (error) {
            console.error('Failed to switch camera:', error)
            throw error
        }
    }

    /**
     * Get connection statistics
     * @returns {Promise<Object>}
     */
    async getStats() {
        if (!this.peerConnection) return null

        try {
            const stats = await this.peerConnection.getStats()
            const result = {
                bytesReceived: 0,
                bytesSent: 0,
                packetsLost: 0,
                roundTripTime: 0,
                jitter: 0
            }

            stats.forEach(report => {
                if (report.type === 'inbound-rtp') {
                    result.bytesReceived += report.bytesReceived || 0
                    result.packetsLost += report.packetsLost || 0
                    result.jitter = report.jitter || 0
                }
                if (report.type === 'outbound-rtp') {
                    result.bytesSent += report.bytesSent || 0
                }
                if (report.type === 'candidate-pair' && report.state === 'succeeded') {
                    result.roundTripTime = report.currentRoundTripTime || 0
                }
            })

            return result
        } catch (error) {
            console.error('Failed to get stats:', error)
            return null
        }
    }

    /**
     * Close peer connection and release resources
     */
    closePeerConnection() {
        if (this.peerConnection) {
            this.peerConnection.close()
            this.peerConnection = null
        }
        this.isRemoteDescriptionSet = false
        this.iceCandidateQueue = []
    }

    /**
     * Stop all media tracks and cleanup
     */
    cleanup() {
        // Stop local stream tracks
        if (this.localStream) {
            this.localStream.getTracks().forEach(track => {
                track.stop()
            })
            this.localStream = null
        }

        // Stop remote stream tracks
        if (this.remoteStream) {
            this.remoteStream.getTracks().forEach(track => {
                track.stop()
            })
            this.remoteStream = null
        }

        // Close peer connection
        this.closePeerConnection()

        // Clear callbacks
        this.onRemoteStream = null
        this.onIceCandidate = null
        this.onConnectionStateChange = null
        this.onIceConnectionStateChange = null
    }

    /**
     * Check if browser supports WebRTC
     * @returns {boolean}
     */
    static isSupported() {
        return !!(
            navigator.mediaDevices &&
            navigator.mediaDevices.getUserMedia &&
            window.RTCPeerConnection
        )
    }

    /**
     * Check available media devices
     * @returns {Promise<{hasAudio: boolean, hasVideo: boolean}>}
     */
    static async checkDevices() {
        try {
            const devices = await navigator.mediaDevices.enumerateDevices()
            return {
                hasAudio: devices.some(d => d.kind === 'audioinput'),
                hasVideo: devices.some(d => d.kind === 'videoinput')
            }
        } catch (error) {
            console.error('Failed to enumerate devices:', error)
            return { hasAudio: false, hasVideo: false }
        }
    }

    /**
     * Check and request media permissions
     * @param {string} callType - 'audio' or 'video'
     * @returns {Promise<{granted: boolean, error: string|null, errorCode: string|null}>}
     */
    static async checkPermissions(callType = 'audio') {
        // Check if we're in a secure context
        if (!window.isSecureContext) {
            return {
                granted: false,
                error: 'Media access requires HTTPS. Please use a secure connection.',
                errorCode: 'INSECURE_CONTEXT'
            }
        }

        // Check if getUserMedia is available
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
            return {
                granted: false,
                error: 'Your browser does not support media access.',
                errorCode: 'NOT_SUPPORTED'
            }
        }

        // Try to query permission status if available
        try {
            if (navigator.permissions && navigator.permissions.query) {
                const micPermission = await navigator.permissions.query({ name: 'microphone' })

                if (micPermission.state === 'denied') {
                    return {
                        granted: false,
                        error: 'Microphone access was denied. Please enable it in your browser settings.',
                        errorCode: 'PERMISSION_DENIED'
                    }
                }

                if (callType === 'video') {
                    const camPermission = await navigator.permissions.query({ name: 'camera' })
                    if (camPermission.state === 'denied') {
                        return {
                            granted: false,
                            error: 'Camera access was denied. Please enable it in your browser settings.',
                            errorCode: 'PERMISSION_DENIED'
                        }
                    }
                }
            }
        } catch (e) {
            // permissions.query not supported, continue to try getUserMedia
            console.log('Permissions API not supported, will try getUserMedia directly')
        }

        // Actually try to get media to trigger permission prompt
        try {
            const constraints = {
                audio: true,
                video: callType === 'video'
            }
            const stream = await navigator.mediaDevices.getUserMedia(constraints)
            // Stop all tracks immediately - we just needed to check permissions
            stream.getTracks().forEach(track => track.stop())
            return { granted: true, error: null, errorCode: null }
        } catch (error) {
            console.error('Permission check failed:', error)

            let errorMessage = 'Failed to access media devices.'
            let errorCode = 'UNKNOWN'

            if (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError') {
                errorMessage = callType === 'video'
                    ? 'Camera and microphone access was denied. Please allow access in your browser settings.'
                    : 'Microphone access was denied. Please allow access in your browser settings.'
                errorCode = 'PERMISSION_DENIED'
            } else if (error.name === 'NotFoundError' || error.name === 'DevicesNotFoundError') {
                errorMessage = callType === 'video'
                    ? 'No camera or microphone found. Please connect a device.'
                    : 'No microphone found. Please connect a microphone.'
                errorCode = 'DEVICE_NOT_FOUND'
            } else if (error.name === 'NotReadableError' || error.name === 'TrackStartError') {
                errorMessage = 'Your camera or microphone is being used by another application.'
                errorCode = 'DEVICE_IN_USE'
            } else if (error.name === 'OverconstrainedError') {
                errorMessage = 'Could not find a device that meets the requirements.'
                errorCode = 'OVERCONSTRAINED'
            } else if (error.name === 'SecurityError') {
                errorMessage = 'Media access is not allowed in this context. Please use HTTPS.'
                errorCode = 'SECURITY_ERROR'
            }

            return {
                granted: false,
                error: errorMessage,
                errorCode: errorCode
            }
        }
    }

    /**
     * Request media permissions in Electron
     * @param {string} callType - 'audio' or 'video'
     * @returns {Promise<boolean>}
     */
    static async requestElectronPermissions(callType = 'audio') {
        // Check if running in Electron
        if (typeof window !== 'undefined' && window.electronAPI && window.electronAPI.requestMediaPermissions) {
            try {
                const result = await window.electronAPI.requestMediaPermissions(callType)
                return result.granted !== false
            } catch (error) {
                console.error('Failed to request Electron permissions:', error)
                return false
            }
        }
        return true // Not Electron, return true to continue
    }
}

export default new WebRTCService()
