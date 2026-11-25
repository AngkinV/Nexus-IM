import { resolvePublicBackendUrls } from './publicBackendUrls.mjs'

const DEFAULT_DEV_API_BASE_URL = 'http://127.0.0.1:8080/api'
const DEFAULT_DEV_WS_URL = 'http://127.0.0.1:8080/ws'
const DEFAULT_BUILD_API_BASE_URL = 'http://localhost:8080/api'
const DEFAULT_BUILD_WS_URL = 'http://localhost:8080/ws'
const isElectron = typeof __IS_ELECTRON__ !== 'undefined' && __IS_ELECTRON__

const resolveElectronUrl = (value, fallback) => !value || value.startsWith('/') ? fallback : value
const envApiBaseUrl = import.meta.env.VITE_API_BASE_URL
const envWsUrl = import.meta.env.VITE_WS_URL
const publicUrls = !import.meta.env.DEV && !isElectron ? resolvePublicBackendUrls(envApiBaseUrl, envWsUrl) : null

// Local development stays local, regardless of the production environment.
// Electron preserves its local-backend fallback; a public Web build never does.
export const API_BASE_URL = import.meta.env.DEV
    ? DEFAULT_DEV_API_BASE_URL
    : isElectron ? resolveElectronUrl(envApiBaseUrl, DEFAULT_BUILD_API_BASE_URL) : publicUrls.apiBaseUrl
export const WS_URL = import.meta.env.DEV
    ? DEFAULT_DEV_WS_URL
    : isElectron ? resolveElectronUrl(envWsUrl, DEFAULT_BUILD_WS_URL) : publicUrls.wsUrl
export const SERVER_BASE_URL = API_BASE_URL.replace(/\/api\/?$/, '')
