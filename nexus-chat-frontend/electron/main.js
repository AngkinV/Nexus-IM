const path = require('node:path')
const { app, BrowserWindow, ipcMain, Notification, shell, powerSaveBlocker, session, systemPreferences } = require('electron')

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
 * Live GPU / memory reading for the settings screen.
 *
 * Numbers come from Electron's own metrics, not from a mock: getAppMetrics()
 * reports the renderer's working set, and getGPUFeatureStatus() reports which
 * hardware acceleration paths Chromium actually enabled.
 */
ipcMain.handle('system:gpu-status', () => {
  try {
    const metrics = app.getAppMetrics()
    // Sum resident memory across every process this app owns (main, renderer,
    // GPU, utility). This is what Activity Monitor would show for the bundle.
    let workingSetKb = 0
    let peakWorkingSetKb = 0
    for (const m of metrics) {
      workingSetKb += m.memory?.workingSetSize || 0
      peakWorkingSetKb += m.memory?.peakWorkingSetSize || 0
    }

    const features = app.getGPUFeatureStatus ? app.getGPUFeatureStatus() : {}
    // Chromium reports 'enabled', 'disabled_software', 'unavailable_software', etc.
    const accelerated = Object.values(features).some((v) => String(v).startsWith('enabled'))

    return {
      available: true,
      memoryMB: Math.round(workingSetKb / 1024),
      peakMemoryMB: Math.round(peakWorkingSetKb / 1024),
      gpuAccelerated: accelerated,
      features
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
