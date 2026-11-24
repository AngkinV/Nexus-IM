const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { execFileSync, spawn } = require('node:child_process')

const APP_NAME = 'Nexus'
const DEV_BUNDLE_ID = 'com.nexus.chat.dev'
const digest = value => crypto.createHash('sha256').update(value).digest('hex')
const xmlEscape = value => String(value).replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }[ch]))

/** Brand a copy, never Electron's shared/downloaded runtime. */
function brandPlist(xml) {
  if (!/<plist\b/.test(xml) || !xml.includes('</dict>')) throw new Error('Invalid Electron bundle metadata')
  for (const [key, value] of Object.entries({
    CFBundleName: APP_NAME, CFBundleDisplayName: APP_NAME,
    CFBundleIdentifier: DEV_BUNDLE_ID, CFBundleIconFile: 'nexus.icns'
  })) {
    const entry = `<key>${key}</key>\n\t<string>${xmlEscape(value)}</string>`
    const pattern = new RegExp(`<key>${key}</key>\\s*<string>[^<]*<\\/string>`)
    if (pattern.test(xml)) xml = xml.replace(pattern, entry)
    else if (xml.includes(`<key>${key}</key>`)) throw new Error(`Unexpected type for ${key}`)
    else {
      const end = xml.lastIndexOf('</dict>')
      xml = xml.slice(0, end) + `\t${entry}\n` + xml.slice(end)
    }
  }
  return xml
}

function prepareMacBundle(electronPath, projectRoot, { exec = execFileSync } = {}) {
  const sourceBinary = fs.realpathSync(electronPath)
  const sourceBundle = path.dirname(path.dirname(path.dirname(sourceBinary)))
  if (!sourceBundle.endsWith('.app') || path.basename(path.dirname(sourceBinary)) !== 'MacOS') {
    throw new Error('Electron executable is not in a macOS app bundle')
  }
  const sourceInfo = path.join(sourceBundle, 'Contents', 'Info.plist')
  const rawInfo = fs.readFileSync(sourceInfo)
  const xml = rawInfo.subarray(0, 8).toString() === 'bplist00'
    ? exec('/usr/bin/plutil', ['-convert', 'xml1', '-o', '-', sourceInfo], { encoding: 'utf8' })
    : rawInfo.toString('utf8')
  const brandedInfo = brandPlist(xml)
  const icon = fs.readFileSync(path.join(projectRoot, 'public', 'icons', 'icon.icns'))
  const stat = fs.statSync(sourceBinary)
  const fingerprint = digest(JSON.stringify({ schema: 1, sourceBinary, size: stat.size,
    modified: stat.mtimeMs, info: digest(rawInfo), branding: digest(brandedInfo), icon: digest(icon) }))
  // Versioned caches never replace an app bundle another dev process is using.
  const cacheRoot = path.join(projectRoot, 'electron-out', 'dev')
  const cacheDir = path.join(cacheRoot, fingerprint)
  const bundle = path.join(cacheDir, `${APP_NAME}.app`)
  const binary = path.join(bundle, 'Contents', 'MacOS', path.basename(sourceBinary))
  const info = path.join(bundle, 'Contents', 'Info.plist')
  const manifest = JSON.stringify({ fingerprint, info: digest(brandedInfo), icon: digest(icon) })
  function reusable() {
    try {
      fs.accessSync(binary, fs.constants.X_OK)
      return fs.readFileSync(path.join(cacheDir, 'manifest.json'), 'utf8') === manifest &&
        digest(fs.readFileSync(info)) === digest(brandedInfo) &&
        digest(fs.readFileSync(path.join(bundle, 'Contents', 'Resources', 'nexus.icns'))) === digest(icon)
    } catch { return false }
  }
  if (reusable()) return binary
  if (fs.existsSync(cacheDir)) throw new Error('Nexus development bundle cache is damaged; remove its electron-out/dev cache before retrying')
  fs.mkdirSync(cacheRoot, { recursive: true })
  const staging = fs.mkdtempSync(path.join(cacheRoot, '.stage-'))
  try {
    const stagedBundle = path.join(staging, `${APP_NAME}.app`)
    // COPYFILE_FICLONE uses cheap APFS clones when available, with a portable
    // copy fallback. Preserve relative framework symlinks exactly.
    fs.cpSync(sourceBundle, stagedBundle, { recursive: true, verbatimSymlinks: true, mode: fs.constants.COPYFILE_FICLONE })
    fs.writeFileSync(path.join(stagedBundle, 'Contents', 'Info.plist'), brandedInfo)
    fs.writeFileSync(path.join(stagedBundle, 'Contents', 'Resources', 'nexus.icns'), icon)
    // Changed Info.plist/resources require a local ad-hoc signature on macOS.
    exec('/usr/bin/codesign', ['--force', '--deep', '--sign', '-', stagedBundle], { stdio: 'pipe' })
    fs.writeFileSync(path.join(staging, 'manifest.json'), manifest)
    try { fs.renameSync(staging, cacheDir) }
    catch (error) {
      // Another simultaneous launcher may have finished the same cache first.
      if (!reusable()) throw error
    }
    if (!reusable()) throw new Error('Nexus development bundle could not be verified')
    return binary
  } finally {
    fs.rmSync(staging, { recursive: true, force: true })
  }
}

function launch({ platform = process.platform, electronPath = require('electron'),
  projectRoot = path.resolve(__dirname, '..'), args = process.argv.slice(2),
  proc = process, spawnProcess = spawn, prepare = prepareMacBundle } = {}) {
  const binary = platform === 'darwin' ? prepare(electronPath, projectRoot) : electronPath
  const env = { ...proc.env }
  delete env.ELECTRON_RUN_AS_NODE
  const child = spawnProcess(binary, [projectRoot, ...args], { cwd: projectRoot, stdio: 'inherit', env })
  const handlers = new Map(['SIGINT', 'SIGTERM'].map(signal => [signal, () => {
    if (child.exitCode == null && child.signalCode == null) child.kill(signal)
  }]))
  for (const [signal, handler] of handlers) proc.on(signal, handler)
  const cleanup = () => { for (const [signal, handler] of handlers) proc.removeListener(signal, handler) }
  child.once('error', error => { cleanup(); console.error(`[Nexus] Could not launch desktop app: ${error.message}`); proc.exitCode = 1 })
  child.once('exit', (code, signal) => {
    cleanup()
    proc.exitCode = code ?? (signal === 'SIGINT' ? 130 : signal === 'SIGTERM' ? 143 : 1)
  })
  return child
}

if (require.main === module) {
  try { launch() }
  catch (error) { console.error(`[Nexus] ${error.message}`); process.exitCode = 1 }
}
module.exports = { APP_NAME, DEV_BUNDLE_ID, brandPlist, prepareMacBundle, launch }
