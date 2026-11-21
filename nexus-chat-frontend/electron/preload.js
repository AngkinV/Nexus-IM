const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('electronAPI', {
  platform: process.platform,
  minimizeWindow: () => ipcRenderer.invoke('window:minimize'),
  maximizeWindow: () => ipcRenderer.invoke('window:maximize'),
  closeWindow: () => ipcRenderer.invoke('window:close'),
  getAppVersion: () => ipcRenderer.invoke('app:get-version'),
  showNotification: (title, body) => ipcRenderer.invoke('app:show-notification', title, body),
  checkForUpdates: () => ipcRenderer.invoke('app:check-for-updates'),
  getGpuStatus: () => ipcRenderer.invoke('system:gpu-status'),
  getCacheInfo: () => ipcRenderer.invoke('app:cache-info'),
  clearCache: () => ipcRenderer.invoke('app:clear-cache'),
  setBadge: (count) => ipcRenderer.invoke('app:set-badge', count),
  setZoom: (percent) => ipcRenderer.invoke('window:set-zoom', percent),
  getZoom: () => ipcRenderer.invoke('window:get-zoom'),
  onWindowFocus: (handler) => {
    const listener = (_event, focused) => handler(focused)
    ipcRenderer.on('window:focus-changed', listener)
    return () => ipcRenderer.removeListener('window:focus-changed', listener)
  },
  openExternal: (url) => ipcRenderer.invoke('app:open-external', url),
  requestMediaPermissions: (callType) => ipcRenderer.invoke('system:request-media-permissions', callType),
  preventDisplaySleep: (shouldPrevent) => ipcRenderer.invoke('system:prevent-display-sleep', shouldPrevent)
})
