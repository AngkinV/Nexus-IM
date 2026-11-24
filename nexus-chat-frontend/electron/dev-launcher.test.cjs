const { test, beforeEach, afterEach } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { EventEmitter } = require('node:events')
const { APP_NAME, DEV_BUNDLE_ID, brandPlist, prepareMacBundle, launch } = require('./dev-launcher.cjs')

const base = path.resolve(__dirname, '..', 'electron-out')
const baseExisted = fs.existsSync(base)
let root, source, binary, project, xml
beforeEach(() => {
  fs.mkdirSync(base, { recursive: true })
  root = fs.mkdtempSync(path.join(base, 'launcher-test-'))
  source = path.join(root, 'dependency', 'Electron.app')
  binary = path.join(source, 'Contents', 'MacOS', 'Electron')
  project = path.join(root, 'project')
  fs.mkdirSync(path.dirname(binary), { recursive: true })
  fs.mkdirSync(path.join(source, 'Contents', 'Resources'), { recursive: true })
  fs.mkdirSync(path.join(project, 'public', 'icons'), { recursive: true })
  fs.writeFileSync(binary, '#!/bin/sh\nexit 0\n', { mode: 0o755 })
  xml = '<?xml version="1.0"?><plist version="1.0"><dict>\n<key>CFBundleName</key><string>Electron</string>\n<key>CFBundleExecutable</key><string>Electron</string>\n<key>CFBundleIdentifier</key><string>com.github.Electron</string>\n<key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoads</key><true/></dict>\n</dict></plist>'
  fs.writeFileSync(path.join(source, 'Contents', 'Info.plist'), xml)
  fs.writeFileSync(path.join(project, 'public', 'icons', 'icon.icns'), 'test-icon')
})
afterEach(() => {
  fs.rmSync(root, { recursive: true, force: true })
  if (!baseExisted && fs.existsSync(base) && fs.readdirSync(base).length === 0) fs.rmdirSync(base)
})

test('branding changes only the copied identity; missing fields go in the root dictionary', () => {
  const branded = brandPlist(xml)
  assert.equal(APP_NAME, 'Nexus')
  assert.equal(DEV_BUNDLE_ID, 'com.nexus.chat.dev')
  assert.match(branded, /<key>CFBundleName<\/key>\s*<string>Nexus<\/string>/)
  assert.match(branded, /<key>CFBundleDisplayName<\/key>\s*<string>Nexus<\/string>/)
  assert.match(branded, /<key>CFBundleIdentifier<\/key>\s*<string>com.nexus.chat.dev<\/string>/)
  assert.match(branded, /<key>CFBundleExecutable<\/key><string>Electron<\/string>/)
  assert.ok(branded.indexOf('CFBundleDisplayName') > branded.indexOf('<true/></dict>'), 'not inside the nested security dictionary')
  assert.equal(brandPlist(branded), branded, 'idempotent')
  assert.throws(() => brandPlist('not plist'), /Invalid/)
  assert.throws(() => brandPlist('<plist><dict><key>CFBundleName</key><integer>1</integer></dict></plist>'), /Unexpected type/)
})

test('preparing a Nexus.app cache preserves source files and framework symlinks; reuse skips signing', () => {
  const versions = path.join(source, 'Contents', 'Frameworks', 'Electron.framework', 'Versions')
  fs.mkdirSync(path.join(versions, 'A'), { recursive: true })
  fs.symlinkSync('A', path.join(versions, 'Current'))
  const commands = []
  const exec = (...args) => { commands.push(args); return '' }
  const copied = prepareMacBundle(binary, project, { exec })
  assert.match(copied, /Nexus\.app\/Contents\/MacOS\/Electron$/)
  assert.equal(commands.length, 1)
  assert.equal(commands[0][0], '/usr/bin/codesign')
  assert.equal(fs.readFileSync(path.join(source, 'Contents', 'Info.plist'), 'utf8'), xml)
  const contents = path.dirname(path.dirname(copied))
  assert.equal(fs.readlinkSync(path.join(contents, 'Frameworks', 'Electron.framework', 'Versions', 'Current')), 'A')
  assert.equal(fs.readFileSync(path.join(contents, 'Resources', 'nexus.icns'), 'utf8'), 'test-icon')
  assert.equal(prepareMacBundle(binary, project, { exec: () => assert.fail('cached bundle must not be re-signed') }), copied)
  fs.writeFileSync(path.join(project, 'public', 'icons', 'icon.icns'), 'updated-icon')
  assert.notEqual(prepareMacBundle(binary, project, { exec }), copied, 'icon/runtime revisions get a new immutable cache')
  assert.ok(fs.existsSync(copied), 'never replace an already-running cached bundle')
})

test('failed signing cannot leave a partially usable app or staging directory', () => {
  assert.throws(() => prepareMacBundle(binary, project, { exec: () => { throw new Error('signing failed') } }), /signing failed/)
  assert.deepEqual(fs.readdirSync(path.join(project, 'electron-out', 'dev')), [])
  assert.equal(fs.readFileSync(path.join(source, 'Contents', 'Info.plist'), 'utf8'), xml)
})

test('damaged cache fails clearly without overwriting a bundle potentially used by another process', () => {
  const copied = prepareMacBundle(binary, project, { exec: () => '' })
  const info = path.join(path.dirname(path.dirname(copied)), 'Info.plist')
  fs.writeFileSync(info, 'broken-cache')
  assert.throws(() => prepareMacBundle(binary, project, { exec: () => assert.fail() }), /cache is damaged/)
  assert.equal(fs.readFileSync(info, 'utf8'), 'broken-cache')
})

function processFixture() {
  const proc = Object.assign(new EventEmitter(), { env: { ELECTRON_RUN_AS_NODE: '1', PATH: 'test-path' }, exitCode: undefined })
  const child = Object.assign(new EventEmitter(), { exitCode: null, signalCode: null, signals: [], kill(signal) { this.signals.push(signal) } })
  const spawned = []
  return { proc, child, spawned, spawnProcess: (...args) => { spawned.push(args); return child } }
}

test('macOS launches the branded binary and forwards arguments/signals without leaking listeners', () => {
  const f = processFixture()
  const returned = launch({ ...f, platform: 'darwin', electronPath: binary, projectRoot: project,
    args: ['--test-argument'], prepare: (input, appRoot) => { assert.equal(input, binary); assert.equal(appRoot, project); return 'branded-binary' } })
  assert.equal(returned, f.child)
  assert.equal(f.spawned[0][0], 'branded-binary')
  assert.deepEqual(f.spawned[0][1], [project, '--test-argument'])
  assert.equal(f.spawned[0][2].env.ELECTRON_RUN_AS_NODE, undefined)
  assert.equal(f.proc.env.ELECTRON_RUN_AS_NODE, '1', 'do not mutate parent environment')
  f.proc.emit('SIGTERM')
  assert.deepEqual(f.child.signals, ['SIGTERM'])
  f.child.emit('exit', null, 'SIGTERM')
  assert.equal(f.proc.exitCode, 143)
  assert.equal(f.proc.listenerCount('SIGINT') + f.proc.listenerCount('SIGTERM'), 0)
})

test('other platforms use the ordinary Electron binary and preserve exit status', () => {
  const f = processFixture()
  launch({ ...f, platform: 'win32', electronPath: binary, projectRoot: project, args: [], prepare: () => assert.fail('not macOS') })
  assert.equal(f.spawned[0][0], binary)
  f.child.emit('exit', 7, null)
  assert.equal(f.proc.exitCode, 7)
  assert.equal(f.proc.listenerCount('SIGTERM'), 0)
})

test('spawn failures report failure and release signal handlers', t => {
  const f = processFixture(), errors = []
  t.mock.method(console, 'error', line => errors.push(line))
  launch({ ...f, platform: 'linux', electronPath: binary, projectRoot: project, args: [] })
  f.child.emit('error', new Error('ENOENT'))
  assert.equal(f.proc.exitCode, 1)
  assert.equal(f.proc.listenerCount('SIGINT'), 0)
  assert.match(errors[0], /Could not launch desktop app/)
})

test('npm dev actually uses the branded launcher; packaged identity and window title remain Nexus', () => {
  const pkg = JSON.parse(fs.readFileSync(path.resolve(__dirname, '..', 'package.json')))
  assert.match(pkg.scripts.dev, /node electron\/dev-launcher\.cjs/)
  assert.equal(pkg.build.productName, 'Nexus')
  const main = fs.readFileSync(path.join(__dirname, 'main.js'), 'utf8')
  assert.match(main, /title: 'Nexus'/)
  assert.match(main, /page-title-updated.*preventDefault/)
})
