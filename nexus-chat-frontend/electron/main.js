const path = require('node:path')
const os = require('node:os')
const { app, BrowserWindow, ipcMain, Notification, shell, powerSaveBlocker, session, systemPreferences, Menu } = require('electron')

const pkg = require('../package.json')

app.setName('Nexus')

const DEV_SERVER_URL = 'http://127.0.0.1:5173'
const UPDATE_REPO = process.env.NEXUS_UPDATE_REPO || 'AngkinV/Nexus-Chat'

let mainWindow = null
let sleepBlockerId = null

function enableCorsForBackend() {
  const filter = {
    urls: [
      'https://chat.angkin.cn/*',
      'http://chat.angkin.cn/*',
      'https://101.35.1.70/*',
      'http://101.35.1.70/*',
      'http://localhost:8080/*',
      'http://127.0.0.1:8080/*'
    ]
  }

  session.defaultSession.webRequest.onHeadersReceived(filter, (details, callback) => {
    const isLocalBackend =
      details.url.startsWith('http://localhost:8080/') ||
      details.url.startsWith('http://127.0.0.1:8080/')

    // Keep local development responses untouched so Spring can return the
    // exact origin and credential headers required by SockJS/XHR.
    if (isLocalBackend) {
      callback({ responseHeaders: details.responseHeaders })
      return
    }

    const responseHeaders = {
      ...details.responseHeaders,
      'Access-Control-Allow-Origin': [DEV_SERVER_URL],
      'Access-Control-Allow-Methods': ['GET, POST, PUT, PATCH, DELETE, OPTIONS'],
      'Access-Control-Allow-Headers': ['Authorization, Content-Type, X-Requested-With, Accept, Origin'],
      'Access-Control-Allow-Credentials': ['true']
    }

    callback({ responseHeaders })
  })
}

function isDev() {
  return !app.isPackaged
}

function getIconPath() {
  return path.join(__dirname, '..', 'public', 'icons', 'app-icon.png')
}

function getPreloadPath() {
  return path.join(__dirname, 'preload.js')
}

function getIndexPath() {
  return path.join(__dirname, '..', 'dist', 'index.html')
}

function compareVersions(currentVersion, latestVersion) {
  const normalize = (value) =>
    String(value)
      .replace(/^v/i, '')
      .split(/[.-]/)
      .map((part) => Number.parseInt(part, 10))
      .map((part) => (Number.isFinite(part) ? part : 0))

  const current = normalize(currentVersion)
  const latest = normalize(latestVersion)
  const maxLength = Math.max(current.length, latest.length)

  for (let index = 0; index < maxLength; index += 1) {
    const currentPart = current[index] || 0
    const latestPart = latest[index] || 0

    if (latestPart > currentPart) return 1
    if (latestPart < currentPart) return -1
  }

  return 0
}

function createMainWindow() {
  mainWindow = new BrowserWindow({
    title: 'Nexus',
    width: 1440,
    height: 920,
    minWidth: 1100,
    minHeight: 720,
    show: false,
    frame: false,
    titleBarStyle: process.platform === 'darwin' ? 'hiddenInset' : 'hidden',
    backgroundColor: '#f3f5f8',
    icon: getIconPath(),
    webPreferences: {
      preload: getPreloadPath(),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  })

  // Keep the taskbar/window identity stable during initial load and navigation.
  mainWindow.on('page-title-updated', event => event.preventDefault())

  mainWindow.once('ready-to-show', () => {
    mainWindow.show()
  })

  mainWindow.on('closed', () => {
    mainWindow = null
  })

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })

  mainWindow.webContents.on('will-navigate', (event, url) => {
    const currentUrl = mainWindow.webContents.getURL()
    const isLocalAppUrl = url.startsWith('file://') || url.startsWith(DEV_SERVER_URL) || url === currentUrl

    if (!isLocalAppUrl) {
      event.preventDefault()
      shell.openExternal(url)
    }
  })

  if (isDev()) {
    mainWindow.loadURL(DEV_SERVER_URL)
  } else {
    mainWindow.loadFile(getIndexPath())
  }
}

async function requestMediaPermissions(callType = 'audio') {
  if (process.platform !== 'darwin' || !systemPreferences?.getMediaAccessStatus) {
    return { granted: true }
  }

  const permissions = callType === 'video' ? ['microphone', 'camera'] : ['microphone']

  for (const permission of permissions) {
    const status = systemPreferences.getMediaAccessStatus(permission)

    if (status === 'granted') {
      continue
    }

    if (status === 'denied' || status === 'restricted') {
      return { granted: false, permission, status }
    }

    const granted = await systemPreferences.askForMediaAccess(permission)
    if (!granted) {
      return { granted: false, permission, status: 'denied' }
    }
  }

  return { granted: true }
}

async function checkForUpdates() {
  const currentVersion = app.getVersion()
  const apiUrl = `https://api.github.com/repos/${UPDATE_REPO}/releases/latest`

  try {
    const response = await fetch(apiUrl, {
      headers: {
        'Accept': 'application/vnd.github+json',
        'User-Agent': `${pkg.name}/${currentVersion}`
      }
    })

    if (!response.ok) {
      throw new Error(`GitHub API returned ${response.status}`)
    }

    const release = await response.json()
    const latestVersion = release.tag_name || release.name || currentVersion
    const hasUpdate = compareVersions(currentVersion, latestVersion) === 1

    return {
      hasUpdate,
      currentVersion,
      latestVersion,
      releaseNotes: release.body || '',
      downloadUrl: release.html_url || '',
      publishedAt: release.published_at || ''
    }
  } catch (error) {
    return {
      hasUpdate: false,
      currentVersion,
      latestVersion: currentVersion,
      error: error.message
    }
  }
}

ipcMain.handle('window:minimize', () => {
  mainWindow?.minimize()
})

ipcMain.handle('window:maximize', () => {
  if (!mainWindow) return false
  if (mainWindow.isMaximized()) {
    mainWindow.unmaximize()
    return false
  }

  mainWindow.maximize()
  return true
})

ipcMain.handle('window:close', () => {
  mainWindow?.close()
})

ipcMain.handle('app:get-version', () => app.getVersion())

ipcMain.handle('app:open-external', (_, url) => {
  if (typeof url !== 'string' || !url.trim()) {
    return false
  }

  shell.openExternal(url)
  return true
})

ipcMain.handle('app:show-notification', (_, title, body) => {
  if (!Notification.isSupported()) {
    return false
  }

  const notification = new Notification({
    title: title || pkg.productName || pkg.name,
    body: body || ''
  })

  notification.show()
  return true
})

ipcMain.handle('app:check-for-updates', () => checkForUpdates())

/**
 * Settings → 清空缓存.
 *
 * What is measured and what is cleared are the same thing, so the number on the
 * card means something: Electron can size the HTTP cache (getCacheSize) and
 * nothing else, so that is the figure shown before and after.
 *
 * Clearing also drops the shader cache, Cache Storage and service workers —
 * all of which are re-created on demand. It never touches cookies,
 * localStorage or IndexedDB: those hold the sign-in, the user's settings and
 * their encryption key.
 */
const CLEARABLE_STORAGES = ['shadercache', 'cachestorage', 'serviceworkers']

ipcMain.handle('app:cache-info', async () => {
  try {
    const httpBytes = Number(await session.defaultSession.getCacheSize()) || 0
    return { available: true, httpBytes }
  } catch (error) {
    return { available: false, error: error.message }
  }
})

ipcMain.handle('app:clear-cache', async () => {
  const ses = session.defaultSession
  try {
    const beforeBytes = Number(await ses.getCacheSize()) || 0
    await ses.clearCache()
    let storagesCleared = true
    try {
      await ses.clearStorageData({ storages: CLEARABLE_STORAGES })
    } catch {
      storagesCleared = false // the HTTP cache is gone; say the rest was not
    }
    const remainingBytes = Number(await ses.getCacheSize()) || 0
    return {
      ok: true,
      storagesCleared,
      remainingBytes,
      freedBytes: Math.max(0, beforeBytes - remainingBytes)
    }
  } catch (error) {
    return { ok: false, error: error.message }
  }
})

/**
 * Dock / taskbar badge for unread messages.
 * macOS uses the dock badge string; Windows and Linux use setBadgeCount.
 */
/**
 * Whole-window zoom. Chromium keeps the factor per-origin, so this survives a
 * reload only if the renderer re-applies it — settings.js does that on boot.
 */
ipcMain.handle('window:set-zoom', (_, percent) => {
  const n = Number(percent)
  if (!Number.isFinite(n)) return false
  const factor = Math.min(3, Math.max(0.5, n / 100))
  try {
    mainWindow?.webContents.setZoomFactor(factor)
    return true
  } catch {
    return false
  }
})

ipcMain.handle('window:get-zoom', () => {
  try {
    return Math.round((mainWindow?.webContents.getZoomFactor() ?? 1) * 100)
  } catch {
    return 100
  }
})

/**
 * Focus changes, so the renderer can suspend 3D work in the background.
 * Sent to every window: the main window is the only one today, but the
 * broadcast keeps it correct if a settings window is ever added.
 */
const sendFocus = (win, focused) => {
  if (win && !win.isDestroyed()) win.webContents.send('window:focus-changed', focused)
}
app.on('browser-window-focus', (_, win) => sendFocus(win, true))
app.on('browser-window-blur', (_, win) => sendFocus(win, false))
ipcMain.handle('window:get-state', (event) => {
  const win = BrowserWindow.fromWebContents(event.sender)
  return { focused: !!win?.isFocused(), visible: !!win?.isVisible() && !win?.isMinimized() }
})

// These are real native menu roles, not labels that merely look translated.
const setMenuLocale = (locale) => {
  const zh = locale !== 'en'
  const menu = [
    ...(process.platform === 'darwin' ? [{ label: app.getName(), submenu: [
      { role: 'about', label: zh ? '关于 Nexus' : 'About Nexus' }, { type: 'separator' },
      { role: 'hide', label: zh ? '隐藏 Nexus' : 'Hide Nexus' },
      { role: 'hideOthers', label: zh ? '隐藏其他应用' : 'Hide Others' },
      { role: 'unhide', label: zh ? '显示全部' : 'Show All' }, { type: 'separator' },
      { role: 'quit', label: zh ? '退出 Nexus' : 'Quit Nexus' }
    ] }] : []),
    { label: zh ? '编辑' : 'Edit', submenu: [
      { role: 'undo', label: zh ? '撤销' : 'Undo' }, { role: 'redo', label: zh ? '重做' : 'Redo' },
      { type: 'separator' }, { role: 'cut', label: zh ? '剪切' : 'Cut' },
      { role: 'copy', label: zh ? '复制' : 'Copy' }, { role: 'paste', label: zh ? '粘贴' : 'Paste' },
      { role: 'selectAll', label: zh ? '全选' : 'Select All' }
    ] },
    { label: zh ? '窗口' : 'Window', submenu: [
      { role: 'minimize', label: zh ? '最小化' : 'Minimize' },
      { role: 'close', label: zh ? '关闭窗口' : 'Close Window' }
    ] }
  ]
  Menu.setApplicationMenu(Menu.buildFromTemplate(menu))
  return true
}
ipcMain.handle('app:set-menu-locale', (_, locale) => setMenuLocale(locale === 'en' ? 'en' : 'zh'))

ipcMain.handle('app:set-badge', (_, count) => {
  const n = Number(count) || 0
  try {
    if (process.platform === 'darwin' && app.dock) {
      app.dock.setBadge(n > 0 ? String(n) : '')
    } else if (typeof app.setBadgeCount === 'function') {
      app.setBadgeCount(n > 0 ? n : 0)
    }
    return true
  } catch {
    return false
  }
})

/**
 * Live process-memory and GPU-feature reading for the settings screen.
 *
 * What these numbers are, precisely:
 * - memory: resident memory (RAM) summed over every process this app owns —
 *   what Activity Monitor shows for the bundle. It is NOT video memory;
 *   Electron does not expose VRAM, so none is reported.
 * - peak: the highest such total seen since launch, sampled every 10 s. (Adding
 *   up each process's own peak would overstate it: they do not peak together.)
 * - totalSystemMB: installed RAM, so the UI can draw a ratio against something real.
 * - webgl / compositing: the Chromium feature flags for exactly those paths.
 */
const readAppMemoryKb = () => {
  let total = 0
  for (const metric of app.getAppMetrics()) total += metric.memory?.workingSetSize || 0
  return total
}

let peakAppMemoryKb = 0
const sampleAppMemory = () => {
  try {
    const now = readAppMemoryKb()
    if (now > peakAppMemoryKb) peakAppMemoryKb = now
    return now
  } catch {
    return 0
  }
}
app.whenReady().then(() => {
  sampleAppMemory()
  const timer = setInterval(sampleAppMemory, 10_000)
  timer.unref?.()
})

const isHardwareAccelerated = (value) => String(value || '').startsWith('enabled')

ipcMain.handle('system:gpu-status', () => {
  try {
    const workingSetKb = sampleAppMemory()
    const features = app.getGPUFeatureStatus ? app.getGPUFeatureStatus() : {}
    return {
      available: true,
      memoryMB: Math.round(workingSetKb / 1024),
      peakMemoryMB: Math.round(peakAppMemoryKb / 1024),
      totalSystemMB: Math.round(os.totalmem() / 1024 / 1024),
      webglAccelerated: isHardwareAccelerated(features.webgl) || isHardwareAccelerated(features.webgl2),
      compositingAccelerated: isHardwareAccelerated(features.gpu_compositing)
    }
  } catch (error) {
    return { available: false, error: error.message }
  }
})
ipcMain.handle('system:request-media-permissions', (_, callType) => requestMediaPermissions(callType))

ipcMain.handle('system:prevent-display-sleep', (_, shouldPrevent) => {
  if (shouldPrevent) {
    if (!sleepBlockerId || !powerSaveBlocker.isStarted(sleepBlockerId)) {
      sleepBlockerId = powerSaveBlocker.start('prevent-display-sleep')
    }
    return true
  }

  if (sleepBlockerId && powerSaveBlocker.isStarted(sleepBlockerId)) {
    powerSaveBlocker.stop(sleepBlockerId)
  }
  sleepBlockerId = null
  return false
})

app.whenReady()
  .then(() => {
    if (process.platform === 'darwin' && app.dock) {
      app.dock.setIcon(getIconPath())
    }

    enableCorsForBackend()
    createMainWindow()

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        createMainWindow()
      }
    })
  })
  .catch((error) => {
    console.error('Failed to initialize Electron app:', error)
    app.quit()
  })

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})
