import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { isKnownExpiredJwt } from '@/utils/sessionGuard.js'
import { authAPI, userAPI, resolveFileUrl } from '@/services/api'
import { useChatStore } from '@/stores/chat'
import { useSettingsStore } from '@/stores/settings'
import offlineStore from '@/services/offlineStore'

export const useUserStore = defineStore('user', () => {
    const currentUser = ref(null)
    const token = ref(null)
    const isLoading = ref(false)
    let profileSequence = 0

    // Computed: Check if user is logged in
    const isLoggedIn = computed(() => {
        return !!currentUser.value && !!token.value
    })

    const register = async (registerData) => {
        isLoading.value = true
        try {
            const response = await authAPI.register(registerData)
            const { token: authToken, userId, username, nickname, avatarUrl, email, phone, bio, profileBackground } = response.data

            currentUser.value = {
                id: userId,
                username,
                nickname,
                avatar: resolveFileUrl(avatarUrl),
                email,
                phone,
                bio: bio || '',
                showOnlineStatus: true,
                showLastSeen: true,
                showPhone: false,
                showEmail: false,
                profileBackground: profileBackground || 'linear-gradient(135deg, #2a2a2d 0%, #131316 100%)'
            }
            token.value = authToken

            // Persist to localStorage
            localStorage.setItem('user', JSON.stringify(currentUser.value))
            localStorage.setItem('token', token.value)
            await loadSettingsFor(userId)

            return currentUser.value
        } catch (error) {
            throw error.response?.data?.message || error.message || 'Registration failed'
        } finally {
            isLoading.value = false
        }
    }

    const login = async (usernameOrEmail, password, twoFactorCode) => {
        isLoading.value = true
        try {
            const response = twoFactorCode
                ? await authAPI.login(usernameOrEmail, password, twoFactorCode)
                : await authAPI.login(usernameOrEmail, password)
            const { token: authToken, userId, username, nickname, avatarUrl, email, phone, bio, profileBackground } = response.data

            currentUser.value = {
                id: userId,
                username,
                nickname,
                avatar: resolveFileUrl(avatarUrl),
                email,
                phone,
                bio: bio || '',
                showOnlineStatus: true,
                showLastSeen: true,
                showPhone: false,
                showEmail: false,
                profileBackground: profileBackground || 'linear-gradient(135deg, #2a2a2d 0%, #131316 100%)'
            }
            token.value = authToken

            // Persist to localStorage
            localStorage.setItem('user', JSON.stringify(currentUser.value))
            localStorage.setItem('token', token.value)

            await loadSettingsFor(userId)

            return currentUser.value
        } catch (error) {
            throw error.response?.data?.messageKey || error.response?.data?.message || error.message || 'Login failed'
        } finally {
            isLoading.value = false
        }
    }

    /**
     * Switch the settings store to this account before the app renders with it.
     * main.js also watches the signed-in id, so this is about ordering, not
     * correctness — and a settings failure must never block sign-in.
     */
    const loadSettingsFor = async (userId) => {
        try {
            await useSettingsStore().switchUser(userId)
        } catch (error) {
            console.error('Failed to load settings for the signed-in user:', error)
        }
    }

    // Synchronous revisions reject late callbacks even across A → B → A.
    let sessionRevision = 0
    watch([token, currentUser], () => { ++sessionRevision }, { flush: 'sync' })
    const captureSession = () => ({
        revision: sessionRevision, token: token.value, owner: currentUser.value?.id,
        storedToken: localStorage.getItem('token'), storedUser: localStorage.getItem('user')
    })
    const expireSession = (expectedToken, expected) => {
        if (!expected || !expectedToken || expected.revision !== sessionRevision ||
            expected.token !== token.value || expected.owner !== currentUser.value?.id ||
            expected.storedToken !== expectedToken || localStorage.getItem('token') !== expectedToken ||
            localStorage.getItem('user') !== expected.storedUser ||
            (token.value !== null && token.value !== expectedToken)) return false
        ++profileSequence
        token.value = null
        // Existing synchronous owner watchers invalidate social revisions and
        // clear only memory. Never call logout/resetStore/IndexedDB clearAll.
        currentUser.value = null
        localStorage.removeItem('token')
        localStorage.removeItem('user')
        return true
    }

    const loadUserFromStorage = () => {
        const storedUser = localStorage.getItem('user')
        const storedToken = localStorage.getItem('token')
        if (isKnownExpiredJwt(storedToken)) {
            expireSession(storedToken, captureSession())
            return
        }
        if (!storedUser || !storedToken) return

        try {
            const parsed = JSON.parse(storedUser)
            if (!parsed || typeof parsed !== 'object' || parsed.id == null) throw new Error('no user id')
            currentUser.value = parsed
            token.value = storedToken
        } catch {
            // A damaged session cannot be trusted: drop it so the router sends
            // the device to the login page instead of crashing at boot.
            localStorage.removeItem('user')
            localStorage.removeItem('token')
        }
    }

    const logout = async () => {
        // Send settings the server has not confirmed yet while the token still works.
        try {
            await useSettingsStore().flushAccount()
        } catch {
            /* they stay pending on this device and go out at the next sign-in */
        }
        try {
            if (currentUser.value?.id) {
                await authAPI.logout(currentUser.value.id)
            }
        } catch (error) {
            console.error('Logout API error:', error)
        } finally {
            // Clear user data
            currentUser.value = null
            token.value = null
            localStorage.removeItem('user')
            localStorage.removeItem('token')

            // Clear chat store data
            const chatStore = useChatStore()
            chatStore.resetStore()

            // Clear IndexedDB
            offlineStore.clearAll().catch(() => {})
        }
    }

    const updateProfile = async (profileData) => {
        isLoading.value = true
        try {
            // In real app, call API
            // await userAPI.updateProfile(currentUser.value.id, profileData.nickname, profileData.avatar)

            currentUser.value = {
                ...currentUser.value,
                ...profileData
            }

            // Persist to localStorage
            localStorage.setItem('user', JSON.stringify(currentUser.value))

            return currentUser.value
        } catch (error) {
            throw error.response?.data?.message || error.message || 'Update failed'
        } finally {
            isLoading.value = false
        }
    }

    const updateAvatar = async (avatarData) => {
        try {
            // In real app, upload avatar and get URL
            // const response = await fileAPI.uploadFile(avatarFile)
            // const avatarUrl = response.data.url

            currentUser.value.avatar = avatarData
            localStorage.setItem('user', JSON.stringify(currentUser.value))

            return currentUser.value
        } catch (error) {
            throw error.response?.data?.message || error.message || 'Avatar update failed'
        }
    }

    const updateBackground = async (backgroundData) => {
        try {
            if (currentUser.value?.id) {
                await userAPI.updateBackground(currentUser.value.id, backgroundData)
            }
            currentUser.value = {
                ...currentUser.value,
                profileBackground: backgroundData
            }
            localStorage.setItem('user', JSON.stringify(currentUser.value))
            return currentUser.value
        } catch (error) {
            throw error.response?.data?.message || error.message || 'Background update failed'
        }
    }

    const updatePrivacySettings = (settings) => {
        currentUser.value = {
            ...currentUser.value,
            showOnlineStatus: settings.showOnlineStatus,
            showLastSeen: settings.showLastSeen,
            showPhone: settings.showPhone,
            showEmail: settings.showEmail,
            friendRequestMode: settings.friendRequestMode ?? currentUser.value.friendRequestMode
        }
        localStorage.setItem('user', JSON.stringify(currentUser.value))
    }

    const updateSocialLinks = async (socialLinks) => {
        try {
            if (currentUser.value?.id) {
                await userAPI.updateSocialLinks(currentUser.value.id, socialLinks)
            }
            currentUser.value = {
                ...currentUser.value,
                socialLinks
            }
            localStorage.setItem('user', JSON.stringify(currentUser.value))
            return currentUser.value
        } catch (error) {
            throw error.response?.data?.message || error.message || 'Social links update failed'
        }
    }

    const loadUserProfile = async () => {
        if (!currentUser.value?.id) return
        const owner = currentUser.value.id
        const session = token.value
        const sequence = ++profileSequence
        try {
            const response = await userAPI.getUserProfile(owner)
            if (currentUser.value?.id !== owner || token.value !== session || sequence !== profileSequence) return
            const profile = response.data
            if (!profile || String(profile.id) !== String(owner)) throw new Error('Profile identity mismatch')
            currentUser.value = {
                ...currentUser.value,
                nickname: profile.nickname,
                avatar: resolveFileUrl(profile.avatarUrl),
                bio: profile.bio,
                createdAt: profile.createdAt,
                profileBackground: profile.profileBackground,
                showOnlineStatus: profile.showOnlineStatus,
                showLastSeen: profile.showLastSeen,
                showPhone: profile.showPhone,
                showEmail: profile.showEmail,
                // Missing server data stays missing; stale local values must not
                // masquerade as a successfully loaded privacy setting.
                friendRequestMode: profile.friendRequestMode,
                socialLinks: profile.socialLinks || {}
            }
            localStorage.setItem('user', JSON.stringify(currentUser.value))
            return currentUser.value
        } catch (error) {
            console.warn('Could not load authenticated user profile')
        }
    }

    const getSocialLinks = async () => {
        if (!currentUser.value?.id) return {}
        try {
            const response = await userAPI.getSocialLinks(currentUser.value.id)
            const links = {}
            response.data.forEach(link => {
                links[link.platform] = link.url
            })
            currentUser.value = {
                ...currentUser.value,
                socialLinks: links
            }
            localStorage.setItem('user', JSON.stringify(currentUser.value))
            return links
        } catch (error) {
            console.error('Failed to load social links:', error)
            return {}
        }
    }

    const addSocialLink = async (platform, url) => {
        if (!currentUser.value?.id) return
        try {
            await userAPI.addSocialLink(currentUser.value.id, platform, url)
            const links = {
                ...currentUser.value?.socialLinks,
                [platform]: url
            }
            currentUser.value = {
                ...currentUser.value,
                socialLinks: links
            }
            localStorage.setItem('user', JSON.stringify(currentUser.value))
            return links
        } catch (error) {
            throw error.response?.data?.message || error.message || 'Failed to add social link'
        }
    }

    const removeSocialLink = async (platform) => {
        if (!currentUser.value?.id) return
        try {
            await userAPI.deleteSocialLink(currentUser.value.id, platform)
            const links = { ...currentUser.value?.socialLinks }
            delete links[platform]
            currentUser.value = {
                ...currentUser.value,
                socialLinks: links
            }
            localStorage.setItem('user', JSON.stringify(currentUser.value))
            return links
        } catch (error) {
            throw error.response?.data?.message || error.message || 'Failed to remove social link'
        }
    }

    return {
        currentUser,
        token,
        isLoading,
        isLoggedIn,
        register,
        login,
        loadUserFromStorage,
        captureSession,
        expireSession,
        logout,
        updateProfile,
        updateAvatar,
        updateBackground,
        updatePrivacySettings,
        updateSocialLinks,
        loadUserProfile,
        getSocialLinks,
        addSocialLink,
        removeSocialLink
    }
})
