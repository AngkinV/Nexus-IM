const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('electronAPI', {
  platform: process.platform,
  minimizeWindow: () => ipcRenderer.invoke('window:minimize'),
  maximizeWindow: () => ipcRenderer.invoke('window:maximize'),
  closeWindow: () => ipcRenderer.invoke('window:close'),
  getAppVersion: () => ipcRenderer.invoke('app:get-version'),
  showNotification: (title, body) => ipcRenderer.invoke('app:show-notification', title, body),
  checkForUpdates: () => ipcRenderer.invoke('app:check-for-updates'),
  openExternal: (url) => ipcRenderer.invoke('app:open-external', url),
  requestMediaPermissions: (callType) => ipcRenderer.invoke('system:request-media-permissions', callType),
  preventDisplaySleep: (shouldPrevent) => ipcRenderer.invoke('system:prevent-display-sleep', shouldPrevent)
})
