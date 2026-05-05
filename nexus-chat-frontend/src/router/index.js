import { createRouter, createWebHistory, createWebHashHistory } from 'vue-router'
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
            component: Setup
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

// Navigation guard
router.beforeEach((to, from, next) => {
    const token = localStorage.getItem('token')

    if (to.meta.requiresAuth && !token) {
        next('/login')
    } else if (to.path === '/login' && token) {
        next('/main')
    } else {
        next()
    }
})

export default router
