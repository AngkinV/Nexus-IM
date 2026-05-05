import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const isElectron = mode === 'electron' || env.ELECTRON === 'true'
  const resolveProxyTarget = (value, fallback) => {
    if (!value || value.startsWith('/')) {
      return fallback
    }
    return new URL(value).origin
  }

  const apiTarget = resolveProxyTarget(env.VITE_API_BASE_URL, 'http://127.0.0.1:8080')
  const wsTarget = resolveProxyTarget(env.VITE_WS_URL, 'http://127.0.0.1:8080')

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    define: {
      global: 'globalThis',
      __IS_ELECTRON__: isElectron
    },
    server: {
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': {
          target: apiTarget,
          changeOrigin: true,
          secure: false,
          configure: (proxy) => {
            proxy.on('error', (error, _req, _res) => {
              console.error('[vite-proxy:/api]', error.message)
            })
          }
        },
        '/ws': {
          target: wsTarget,
          changeOrigin: true,
          secure: false,
          ws: true,
          configure: (proxy) => {
            proxy.on('error', (error, _req, _res) => {
              console.error('[vite-proxy:/ws]', error.message)
            })
          }
        }
      }
    },
    base: isElectron ? './' : '/'
  }
})
