import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authAPI, userAPI, resolveFileUrl } from '@/services/api'
import { useChatStore } from '@/stores/chat'
import offlineStore from '@/services/offlineStore'

export const useUserStore = defineStore('user', () => {
    const currentUser = ref(null)
    const token = ref(null)
    const isLoading = ref(false)

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
                profileBackground: profileBackground || 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)'
            }
            token.value = authToken

            // Persist to localStorage
            localStorage.setItem('user', JSON.stringify(currentUser.value))
            localStorage.setItem('token', token.value)

            return currentUser.value
        } catch (error) {
            throw error.response?.data?.message || error.message || 'Registration failed'
        } finally {
            isLoading.value = false
        }
    }

    const login = async (usernameOrEmail, password) => {
        isLoading.value = true
        try {
            const response = await authAPI.login(usernameOrEmail, password)
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
                profileBackground: profileBackground || 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)'
            }
            token.value = authToken

            // Persist to localStorage
            localStorage.setItem('user', JSON.stringify(currentUser.value))
            localStorage.setItem('token', token.value)

            return currentUser.value
        } catch (error) {
            throw error.response?.data?.message || error.message || 'Login failed'
        } finally {
            isLoading.value = false
        }
    }

    const loadUserFromStorage = () => {
        const storedUser = localStorage.getItem('user')
        const storedToken = localStorage.getItem('token')

        if (storedUser && storedToken) {
            currentUser.value = JSON.parse(storedUser)
            token.value = storedToken
        }
    }

    const logout = async () => {
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
            showEmail: settings.showEmail
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
        try {
            const response = await userAPI.getUserProfile(currentUser.value.id)
            const profile = response.data
            currentUser.value = {
                ...currentUser.value,
                nickname: profile.nickname,
                avatar: resolveFileUrl(profile.avatarUrl),
                bio: profile.bio,
                profileBackground: profile.profileBackground,
                showOnlineStatus: profile.showOnlineStatus,
                showLastSeen: profile.showLastSeen,
                showPhone: profile.showPhone,
                showEmail: profile.showEmail,
                socialLinks: profile.socialLinks || {}
            }
            localStorage.setItem('user', JSON.stringify(currentUser.value))
            return currentUser.value
        } catch (error) {
            console.error('Failed to load user profile:', error)
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
