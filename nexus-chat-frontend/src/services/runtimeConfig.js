const DEFAULT_DEV_API_BASE_URL = 'http://127.0.0.1:8080/api'
const DEFAULT_DEV_WS_URL = 'http://127.0.0.1:8080/ws'
const DEFAULT_BUILD_API_BASE_URL = 'http://localhost:8080/api'
const DEFAULT_BUILD_WS_URL = 'http://localhost:8080/ws'

const resolveRuntimeUrl = (value, fallback) => {
    if (!value || value.startsWith('/')) {
        return fallback
    }
    return value
}

const envApiBaseUrl = import.meta.env.VITE_API_BASE_URL
const envWsUrl = import.meta.env.VITE_WS_URL

// In local development we always talk to the local backend directly.
// This avoids inheriting production endpoints from the shell or Electron launch env.
export const API_BASE_URL = import.meta.env.DEV
    ? DEFAULT_DEV_API_BASE_URL
    : resolveRuntimeUrl(envApiBaseUrl, DEFAULT_BUILD_API_BASE_URL)
export const WS_URL = import.meta.env.DEV
    ? DEFAULT_DEV_WS_URL
    : resolveRuntimeUrl(envWsUrl, DEFAULT_BUILD_WS_URL)
export const SERVER_BASE_URL = API_BASE_URL.replace(/\/api\/?$/, '')
