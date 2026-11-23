import { createRouter, createWebHistory, createWebHashHistory } from 'vue-router'
import { installNavigation, createAuthNavigationGuard } from './navigation'
import Login from '@/views/Login.vue'
import Setup from '@/views/Setup.vue'
import Main from '@/views/Main.vue'
import Settings from '@/views/Settings.vue'
import Profile from '@/views/Profile.vue'
import UserProfile from '@/views/UserProfile.vue'

// Electron 环境使用 Hash 模式，Web 环境使用 History 模式
const isElectron = typeof __IS_ELECTRON__ !== 'undefined' && __IS_ELECTRON__

const router = createRouter({
    history: isElectron ? createWebHashHistory() : createWebHistory(),
    routes: [
        {
            path: '/',
            redirect: '/login'
        },
        {
            path: '/login',
            name: 'Login',
            component: Login
        },
        {
            path: '/setup',
            name: 'Setup',
            component: Setup,
            meta: { requiresAuth: true }
        },
        {
            path: '/main',
            name: 'Main',
            component: Main,
            meta: { requiresAuth: true }
        },
        {
            path: '/settings',
            name: 'Settings',
            component: Settings,
            meta: { requiresAuth: true }
        },
        {
            path: '/profile',
            name: 'Profile',
            component: Profile,
            meta: { requiresAuth: true }
        },
        {
            path: '/user/:id',
            name: 'UserProfile',
            component: UserProfile,
            meta: { requiresAuth: true },
            props: true
        }
    ]
})

// Dev-only review stage for the tavern entrance transition; stripped from production builds.
if (import.meta.env.DEV) {
    router.addRoute({
        path: '/dev/warp',
        name: 'WarpPreview',
        component: () => import('@/views/dev/WarpPreview.vue')
    })
}

installNavigation(router)

// Navigation guard
router.beforeEach(createAuthNavigationGuard(router))

export default router
