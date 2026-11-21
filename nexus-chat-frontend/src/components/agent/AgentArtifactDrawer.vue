<script setup>
import { computed, nextTick, onBeforeUnmount, onUnmounted, ref, shallowRef, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAgentWorkspaceStore } from '@/stores/agentWorkspace'
import { artifactFileName, artifactTabs, artifactText } from './artifactView.js'

const props = defineProps({
  visible: Boolean,
  artifact: { type: Object, default: null },
  run: { type: Object, default: () => ({}) },
  sessionId: { type: String, default: '' },
  artifacts: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:visible', 'select'])
const { t } = useI18n({ useScope: 'local', messages: {
  en: {
    title: 'Artifact inspector', empty: 'No artifact selected.', choose: 'Choose artifact', switcher: 'Switch artifact',
    views: 'Artifact views', diff: 'Diff', raw: 'Raw', log: 'Log', graph: 'Graph', close: 'Close',
    bytes: '{n} bytes', role: 'Source: {role}', lines: '{n} lines', emptyText: 'No content recorded.', emptyLog: 'No execution log recorded.',
    nodes: 'Nodes', edges: 'Edges', sources: 'Sources', runId: 'Run ID', artifactId: 'Artifact ID', noNodes: 'No recorded nodes.',
    copy: 'Copy', copied: 'Copied to clipboard.', noClipboard: 'Clipboard access is unavailable.', download: 'Download artifact',
    apply: 'Apply patch', base: 'Base file', chooseBase: 'Select an uploaded file', noBase: 'No uploaded attachments in this session.',
    preview: 'Preview patch', review: 'Review patch', before: 'Before', after: 'After', baseHash: 'Base hash', patchHash: 'Patch hash',
    confirm: 'Confirm new file', cancel: 'Cancel', unchanged: 'Confirmation creates a new session file. The source stays unchanged.',
    invalidPreview: 'This preview does not match the selected file and patch. Preview again.', applied: 'New session file: {file}. Source unchanged.',
    create: 'Create session tasks', checklist: 'Session tasks', export: 'Export tasks (JSON)', taskSaved: 'Task status saved.', taskCount: 'Stored tasks: {n}.',
    save: 'Save to session', saveGraph: 'Save graph to session', saved: 'Saved session attachment: {file}.',
    failed: 'The operation failed.', unconfirmed: 'The workspace did not return a saved record.', noSession: 'Select a session to save changes.',
    loading: 'Loading workspace…', working: 'Working…', loadFailed: 'Could not load the workspace', retry: 'Retry'
  },
  zh: {
    title: '产物预览', empty: '暂无产物', choose: '选择产物', switcher: '切换产物',
    views: '产物视图', diff: '差异对比', raw: '原始源码', log: '执行日志', graph: '溯源拓扑', close: '关闭',
    bytes: '{n} 字节', role: '来源：{role}', lines: '{n} 行', emptyText: '没有记录内容。', emptyLog: '暂无执行日志。',
    nodes: '节点', edges: '依赖关系', sources: '来源', runId: '运行 ID', artifactId: '产物 ID', noNodes: '暂无节点记录。',
    copy: '复制', copied: '已复制到剪贴板。', noClipboard: '无法访问剪贴板。', download: '下载产物',
    apply: '应用补丁', base: '源文件', chooseBase: '选择已上传的附件', noBase: '当前会话暂无已上传的附件。',
    preview: '预览补丁', review: '核对补丁', before: '应用前', after: '应用后', baseHash: '源文件哈希', patchHash: '补丁哈希',
    confirm: '确认创建新文件', cancel: '取消', unchanged: '确认后会创建新的会话文件，不会修改源文件。',
    invalidPreview: '预览与所选文件或补丁不匹配，请重新预览。', applied: '已创建新的会话文件：{file}。源文件未更改。',
    create: '创建会话任务', checklist: '会话任务', export: '导出任务 (JSON)', taskSaved: '任务状态已保存。', taskCount: '已保存的任务：{n} 项。',
    save: '保存为会话附件', saveGraph: '保存拓扑为会话附件', saved: '已保存为会话附件：{file}。',
    failed: '操作失败。', unconfirmed: '工作区未返回已保存的记录。', noSession: '请选择会话后再保存。',
    loading: '正在加载工作区…', working: '正在处理…', loadFailed: '无法加载工作区', retry: '重试'
  }
} })

const workspace = useAgentWorkspaceStore()
const uid = useId()
const tabIcons = { diff: 'difference', raw: 'code', log: 'terminal', graph: 'account_tree' }
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const rows = value => Array.isArray(value) ? value.filter(record) : []
const nonblank = value => typeof value === 'string' && value.trim().length > 0
const hasId = row => record(row) && nonblank(row.id)
const context = computed(() => props.run ?? {})
const options = computed(() => rows(props.artifacts).filter(hasId))
const filename = computed(() => props.artifact ? artifactFileName(props.artifact) : '')
const fileLabel = file => file.fileName ? artifactFileName({ name: file.fileName }) : file.id
const tabs = computed(() => props.artifact ? artifactTabs(props.artifact, context.value) : [])
const active = ref('')
function textFor(tab) {
  const value = artifactText(props.artifact, tab, context.value)
  return typeof value === 'string' ? value : ''
}
const raw = computed(() => textFor('raw'))
const current = computed(() => active.value === 'raw' ? raw.value : textFor(active.value))
const hasContent = computed(() => typeof props.artifact?.content === 'string')
const bytes = computed(() => new Blob([raw.value]).size)
const role = computed(() => props.artifact?.agentId || props.artifact?.sourceRole)
function linesOf(value) {
  if (!value) return []
  const lines = value.split(/\r\n|\n|\r/)
  if (lines.at(-1) === '') lines.pop()
  return lines
}
const lines = computed(() => linesOf(current.value))
// File and index headers are not changes. Use the hunk's actual old/new locations.
const diffLines = computed(() => {
  if (active.value !== 'diff') return []
  let oldLine = null, newLine = null, oldLeft = 0, newLeft = 0
  return lines.value.map(text => {
    const hunk = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/.exec(text)
    if (hunk) {
      oldLine = Number(hunk[1]); oldLeft = Number(hunk[2] ?? 1)
      newLine = Number(hunk[3]); newLeft = Number(hunk[4] ?? 1)
      return { text, type: 'hunk' }
    }
    if (oldLeft > 0 && text.startsWith('-')) { oldLeft--; return { text, type: 'removed', number: oldLine++ } }
    if (newLeft > 0 && text.startsWith('+')) { newLeft--; return { text, type: 'added', number: newLine++ } }
    if (oldLeft > 0 && newLeft > 0 && text.startsWith(' ')) {
      oldLine++; oldLeft--; newLeft--
      return { text, type: 'context', number: newLine++ }
    }
    return { text, type: 'meta' }
  })
})
function parseJson(value) { try { return JSON.parse(value) } catch { return null } }
const graph = computed(() => active.value === 'graph' ? parseJson(current.value) : null)
const isTaskList = computed(() => {
  if (props.artifact?.kind !== 'json') return false
  const data = parseJson(raw.value), items = data?.todos ?? data?.items
  return Array.isArray(items) && items.length > 0 && items.every(item => nonblank(item) || nonblank(item?.title) || nonblank(item?.task))
})

const loaded = ref(false), loading = ref(false), loadError = ref('')
const patchOpen = ref(false), baseId = ref(''), reviewed = shallowRef(null)
const busy = ref(''), notice = ref(''), error = ref('')
const baseField = ref(null), patchButton = ref(null), reviewTitle = ref(null), closeButton = ref(null)
const tabButtons = new Map()
const ready = computed(() => loaded.value && !!props.sessionId && (!workspace.sessionId || workspace.sessionId === props.sessionId))
const attachments = computed(() => ready.value ? rows(workspace.attachments).filter(hasId) : [])
const base = computed(() => attachments.value.find(a => a.id === baseId.value))
const tasks = computed(() => ready.value ? rows(workspace.tasks).filter(task => hasId(task) && nonblank(task.title)) : [])
const canWrite = computed(() => props.visible && ready.value && hasId(props.artifact) && hasContent.value && !busy.value)
let scope = 0, loadSequence = 0, opener = null, closing = false

// Every pending operation owns a generation. Changing away and back to the same
// IDs must still invalidate the old continuation (including its catch/finally).
function invalidate() {
  scope++; reviewed.value = null; busy.value = ''; notice.value = ''; error.value = ''
}
function resetSelection() { invalidate(); patchOpen.value = false; baseId.value = '' }
const isCurrent = ticket => ticket === scope && props.visible && !closing
const messageOf = err => (typeof err === 'string' && err) || err?.response?.data?.message || err?.message || t('failed')
async function loadWorkspace() {
  const sequence = ++loadSequence
  loaded.value = false; loadError.value = ''; loading.value = !!(props.visible && props.sessionId)
  if (!loading.value) return
  try {
    await workspace.load(props.sessionId)
    if (sequence === loadSequence) loaded.value = true
  } catch (err) { if (sequence === loadSequence) loadError.value = messageOf(err) }
  finally { if (sequence === loadSequence) loading.value = false }
}
watch([() => props.visible, () => props.sessionId], loadWorkspace, { immediate: true })
watch([() => props.visible, () => props.sessionId, () => props.artifact?.id, () => props.artifact?.kind,
  () => props.artifact?.content, () => props.artifact?.sha256, () => props.artifact?.runId, () => props.run?.runId], () => {
  resetSelection(); active.value = tabs.value[0]?.id || ''
}, { immediate: true, flush: 'sync' })
watch(tabs, list => { if (!list.some(tab => tab.id === active.value)) active.value = list[0]?.id || '' })
watch([baseId, () => base.value?.id, () => base.value?.sha256, () => base.value?.content], () => {
  if (patchOpen.value && busy.value !== 'apply') invalidate()
}, { flush: 'sync' })

function selectArtifact(id) {
  const artifact = options.value.find(a => a.id === id)
  if (!props.visible || !artifact || artifact.id === props.artifact?.id) return
  resetSelection(); emit('select', artifact)
}
function switchTab(tab, focus = false) {
  if (!tabs.value.some(item => item.id === tab)) return
  if (active.value !== tab) { resetSelection(); active.value = tab }
  if (focus) nextTick(() => tabButtons.get(tab)?.focus())
}
function tabKey(event) {
  const ids = tabs.value.map(tab => tab.id), index = ids.indexOf(active.value)
  const destination = { ArrowRight: index + 1, ArrowLeft: index - 1, Home: 0, End: ids.length - 1 }[event.key]
  if (destination === undefined || !ids.length) return
  event.preventDefault(); event.stopPropagation(); switchTab(ids[(destination + ids.length) % ids.length], true)
}
function requestClose() {
  if (!props.visible || closing) return
  closing = true; resetSelection(); emit('update:visible', false)
}
function shortcut(event) {
  if (!props.visible || closing || event.isComposing) return
  const digit = !event.altKey && !event.shiftKey && (event.ctrlKey || event.metaKey) && /^[123]$/.test(event.key)
  if (!digit && event.key !== 'Escape') return
  event.preventDefault(); event.stopImmediatePropagation()
  if (digit) selectArtifact(options.value[Number(event.key) - 1]?.id)
  else requestClose()
}
function focusDrawer() { nextTick(() => { if (props.visible && !closing) (tabButtons.get(active.value) || closeButton.value)?.focus() }) }
function restoreFocus() { if (opener?.isConnected) opener.focus({ preventScroll: true }); opener = null }
watch(() => props.visible, open => {
  if (typeof window === 'undefined') return
  if (open) {
    closing = false; opener = document.activeElement; window.addEventListener('keydown', shortcut, true)
  } else { window.removeEventListener('keydown', shortcut, true); nextTick(() => { if (!props.visible) restoreFocus() }) }
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { closing = true; loadSequence++; invalidate(); if (typeof window !== 'undefined') window.removeEventListener('keydown', shortcut, true) })
onUnmounted(restoreFocus)

async function perform(name, request, complete, needsWorkspace = true) {
  if (!props.visible || closing || busy.value || !props.artifact || (needsWorkspace && !canWrite.value)) return
  const ticket = scope
  busy.value = name; error.value = ''; notice.value = ''
  try {
    const result = await request()
    if (isCurrent(ticket)) complete(result)
  } catch (err) { if (isCurrent(ticket)) error.value = messageOf(err) }
  finally { if (isCurrent(ticket)) busy.value = '' }
}
function copy() {
  const text = current.value
  return perform('copy', () => {
    if (!globalThis.navigator?.clipboard?.writeText) throw new Error(t('noClipboard'))
    return navigator.clipboard.writeText(text)
  }, () => { notice.value = t('copied') }, false)
}
function downloadText(text, name, mime) {
  const url = URL.createObjectURL(new Blob([text], { type: mime }))
  const link = document.createElement('a')
  try { link.href = url; link.download = name; document.body.appendChild(link); link.click() }
  finally { link.remove(); setTimeout(() => URL.revokeObjectURL(url), 0) }
}
function download(exportTasks = false) {
  if (!props.visible || !props.artifact || (exportTasks && !tasks.value.length)) return
  error.value = ''; notice.value = ''
  try {
    if (exportTasks) downloadText(JSON.stringify(tasks.value, null, 2), `${filename.value}.tasks.json`, 'application/json;charset=utf-8')
    else downloadText(raw.value, filename.value, ['json', 'graph'].includes(props.artifact.kind) ? 'application/json;charset=utf-8' : 'text/plain;charset=utf-8')
  } catch (err) { error.value = messageOf(err) }
}
function openPatch() {
  if (!canWrite.value || props.artifact.kind !== 'patch') return
  switchTab('diff'); resetSelection(); patchOpen.value = true; nextTick(() => baseField.value?.focus())
}
function cancelPatch() { resetSelection(); nextTick(() => patchButton.value?.focus()) }
function matchesPreview(preview) {
  return record(preview) && base.value && preview.baseAttachmentId === baseId.value &&
    nonblank(preview.baseHash) && nonblank(preview.patchHash) &&
    (!base.value.sha256 || base.value.sha256 === preview.baseHash) && (!props.artifact.sha256 || props.artifact.sha256 === preview.patchHash) &&
    typeof preview.before === 'string' && typeof preview.after === 'string' && typeof preview.diff === 'string'
}
function previewPatch() {
  if (!patchOpen.value || !base.value) return
  reviewed.value = null
  return perform('preview', () => workspace.previewPatch(props.sessionId, props.artifact.id, baseId.value), result => {
    if (!matchesPreview(result)) throw new Error(t('invalidPreview'))
    reviewed.value = Object.freeze({ ...result }); nextTick(() => reviewTitle.value?.focus())
  })
}
function confirmPatch() {
  const preview = reviewed.value
  if (!canWrite.value || !patchOpen.value || !matchesPreview(preview)) return
  reviewed.value = null // One confirmation consumes this exact preview, even on failure.
  return perform('apply', () => workspace.applyPatch(props.sessionId, props.artifact.id, preview), file => {
    if (!hasId(file) || file.id === preview.baseAttachmentId) throw new Error(t('unconfirmed'))
    patchOpen.value = false; baseId.value = ''; notice.value = t('applied', { file: fileLabel(file) }); nextTick(() => patchButton.value?.focus())
  })
}
function saveArtifact() {
  return perform('save', () => workspace.saveArtifact(props.sessionId, props.artifact.id), file => {
    if (!hasId(file)) throw new Error(t('unconfirmed'))
    notice.value = t('saved', { file: fileLabel(file) })
  })
}
function createTasks() {
  if (!isTaskList.value) return
  return perform('create', () => workspace.createTasks(props.sessionId, props.artifact.id), result => {
    const saved = Array.isArray(result) ? result : result?.tasks
    if (!Array.isArray(saved) || !saved.length || !saved.every(hasId)) throw new Error(t('unconfirmed'))
    notice.value = t('taskCount', { n: saved.length })
  })
}
function toggleTask(task, event) {
  const status = event.target.checked ? 'done' : 'pending'
  event.target.checked = task.status === 'done' // Native checkboxes toggle before change. Wait for storage.
  return perform('task', () => workspace.updateTask(props.sessionId, task.id, status), result => {
    if (result?.id !== task.id || result.status !== status) throw new Error(t('unconfirmed'))
    notice.value = t('taskSaved')
  })
}
</script>

<template>
  <el-drawer :model-value="visible" direction="rtl" size="min(100%, 36rem)" append-to-body destroy-on-close
    :show-close="false" :title="filename || t('title')" class="agent-artifact-drawer" modal-class="agent-artifact-scrim"
    :before-close="requestClose" @open-auto-focus="focusDrawer" @opened="focusDrawer"
    @update:model-value="value => { if (!value) requestClose() }">
    <template #header>
      <div class="drawer-heading">
        <span class="file-icon material-symbols-outlined" aria-hidden="true">{{ artifact?.kind === 'graph' ? 'schema' : 'data_object' }}</span>
        <div class="file-info">
          <div class="file-title">
            <div v-if="options.length > 1 || (!artifact && options.length)" class="file-picker">
              <span>{{ filename || t('choose') }}</span><span class="material-symbols-outlined" aria-hidden="true">expand_more</span>
              <select :value="artifact?.id ?? ''" :aria-label="t('switcher')" @change="selectArtifact($event.target.value)">
                <option v-if="!artifact || !options.some(a => a.id === artifact.id)" :value="artifact?.id ?? ''" disabled>{{ filename || t('choose') }}</option>
                <option v-for="(a, i) in options" :key="a.id" :value="a.id">{{ artifactFileName(a) }}{{ i < 3 ? ` · Ctrl/⌘+${i + 1}` : '' }}</option>
              </select>
            </div>
            <strong v-else :title="filename">{{ filename || t('title') }}</strong>
            <span v-if="artifact?.kind" class="badge">{{ artifact.kind }}</span>
          </div>
          <div v-if="artifact" class="metadata">
            <span v-if="hasContent">{{ t('bytes', { n: bytes }) }}</span>
            <span v-if="role">{{ t('role', { role }) }}</span><span v-if="artifact.nodeId">{{ artifact.nodeId }}</span>
          </div>
        </div>
        <button v-if="artifact" type="button" class="quiet" :aria-label="t('copy')" :disabled="!!busy || !current" @click="copy">
          <span class="material-symbols-outlined" aria-hidden="true">content_copy</span><span class="copy-label">{{ t('copy') }}</span>
        </button>
        <button ref="closeButton" type="button" class="quiet square" :aria-label="t('close')" @click="requestClose"><span class="material-symbols-outlined" aria-hidden="true">close</span></button>
      </div>
    </template>

    <div v-if="artifact" class="tab-bar">
      <div class="tabs" role="tablist" :aria-label="t('views')" @keydown="tabKey">
        <button v-for="tab in tabs" :id="`${uid}-tab-${tab.id}`" :key="tab.id" :ref="el => el ? tabButtons.set(tab.id, el) : tabButtons.delete(tab.id)"
          role="tab" type="button" :aria-selected="active === tab.id" :aria-controls="`${uid}-panel`" :tabindex="active === tab.id ? 0 : -1" @click="switchTab(tab.id)">
          <span class="material-symbols-outlined" aria-hidden="true">{{ tabIcons[tab.id] }}</span>{{ t(tab.id) }}
        </button>
      </div>
      <span v-if="hasContent" class="encoding">UTF-8</span>
    </div>
    <div v-if="!artifact" class="empty-state"><span class="material-symbols-outlined" aria-hidden="true">draft</span>{{ t('empty') }}</div>
    <div v-else class="scroll-body">
      <section v-if="patchOpen" class="card workspace-card" :aria-label="t('review')">
        <label class="field-label" :for="`${uid}-base`">{{ t('base') }}</label>
        <select :id="`${uid}-base`" ref="baseField" :aria-label="t('base')" :value="baseId" :disabled="busy === 'apply' || !attachments.length" @change="baseId = $event.target.value">
          <option value="" disabled>{{ t('chooseBase') }}</option>
          <option v-for="file in attachments" :key="file.id" :value="file.id">{{ fileLabel(file) }}</option>
        </select>
        <p v-if="ready && !attachments.length" class="hint">{{ t('noBase') }}</p><p class="hint">{{ t('unchanged') }}</p>
        <template v-if="reviewed">
          <h3 ref="reviewTitle" tabindex="-1">{{ t('review') }}</h3>
          <dl class="hashes"><dt>{{ t('baseHash') }}</dt><dd>{{ reviewed.baseHash }}</dd><dt>{{ t('patchHash') }}</dt><dd>{{ reviewed.patchHash }}</dd></dl>
          <div class="comparison"><div><h4>{{ t('before') }}</h4><pre>{{ reviewed.before }}</pre></div><div><h4>{{ t('after') }}</h4><pre>{{ reviewed.after }}</pre></div></div>
          <details class="preview-diff"><summary>{{ t('diff') }}</summary><pre>{{ reviewed.diff }}</pre></details>
        </template>
      </section>
      <div :id="`${uid}-panel`" role="tabpanel" tabindex="0" :aria-labelledby="`${uid}-tab-${active}`" class="card view-panel">
        <template v-if="active === 'diff' && diffLines.length">
          <div v-for="(line, i) in diffLines" :key="i" class="code-line" :class="line.type">
            <span v-if="line.number != null" class="gutter" aria-hidden="true">{{ line.number }}</span><code>{{ line.text }}</code>
          </div>
        </template>
        <template v-else-if="active === 'graph' && graph">
          <h3><span class="material-symbols-outlined" aria-hidden="true">account_tree</span>{{ t('graph') }}</h3>
          <dl class="hashes"><template v-if="graph.runId"><dt>{{ t('runId') }}</dt><dd>{{ graph.runId }}</dd></template><template v-if="graph.artifactId"><dt>{{ t('artifactId') }}</dt><dd>{{ graph.artifactId }}</dd></template></dl>
          <h4>{{ t('nodes') }} · {{ rows(graph.nodes).length }}</h4>
          <div v-for="n in rows(graph.nodes)" :key="n.id" class="graph-node"><span><strong>{{ n.title || n.kind }}</strong><code>{{ n.id }}</code></span><small>{{ [n.agentId, n.state].filter(Boolean).join(' · ') }}</small></div>
          <p v-if="!rows(graph.nodes).length" class="hint">{{ t('noNodes') }}</p>
          <template v-if="rows(graph.edges).length"><h4>{{ t('edges') }}</h4><div v-for="edge in graph.edges" :key="`${edge.from}:${edge.to}`" class="edge">{{ edge.from }} → {{ edge.to }}</div></template>
          <template v-if="rows(graph.sources).length"><h4>{{ t('sources') }}</h4><div v-for="s in graph.sources" :key="s.id" class="source"><b>{{ s.id }}</b> · {{ s.title || s.kind }}</div></template>
        </template>
        <template v-else>
          <h3><span>{{ active === 'raw' ? filename : t(active || 'raw') }}</span><small v-if="active === 'raw'">{{ t('lines', { n: lines.length }) }}</small></h3>
          <p v-if="!lines.length" class="hint">{{ t(active === 'log' ? 'emptyLog' : 'emptyText') }}</p>
          <div v-for="(line, i) in lines" :key="i" class="code-line"><span v-if="active === 'raw'" class="gutter" aria-hidden="true">{{ i + 1 }}</span><code>{{ line }}</code></div>
        </template>
        <details v-if="active === 'raw' && artifact.sha256" class="digest"><summary>SHA-256</summary><code>{{ artifact.sha256 }}</code></details>
      </div>
      <section v-if="isTaskList && tasks.length" class="card checklist" :aria-label="t('checklist')">
        <h3>{{ t('checklist') }}<small>{{ tasks.filter(task => task.status === 'done').length }} / {{ tasks.length }}</small></h3>
        <label v-for="task in tasks" :key="task.id" class="task-row"><input type="checkbox" :checked="task.status === 'done'" :disabled="!canWrite" @change="toggleTask(task, $event)"><span>{{ task.title }}</span></label>
        <button type="button" class="secondary" @click="download(true)">{{ t('export') }}</button>
      </section>
    </div>

    <template #footer>
      <p v-if="error || loadError" class="feedback problem" role="alert">{{ error || `${t('loadFailed')}: ${loadError}` }}<button v-if="loadError" type="button" class="quiet" @click="loadWorkspace">{{ t('retry') }}</button></p>
      <p v-else-if="notice || busy || loading || (artifact && !sessionId)" class="feedback" role="status">{{ notice || (busy ? t('working') : loading ? t('loading') : t('noSession')) }}</p>
      <div class="actions">
        <template v-if="artifact">
          <template v-if="patchOpen">
            <button v-if="reviewed" type="button" class="primary" :disabled="!canWrite" @click="confirmPatch">{{ t('confirm') }}</button>
            <button v-else type="button" class="primary" :disabled="!canWrite || !base" @click="previewPatch">{{ t('preview') }}</button>
            <button type="button" class="secondary" :disabled="busy === 'apply'" @click="cancelPatch">{{ t('cancel') }}</button>
          </template>
          <template v-else>
            <button v-if="artifact.kind === 'patch'" ref="patchButton" type="button" class="primary" :disabled="!canWrite" @click="openPatch"><span class="material-symbols-outlined" aria-hidden="true">bolt</span>{{ t('apply') }}</button>
            <button v-else-if="isTaskList" type="button" class="primary" :disabled="!canWrite" @click="createTasks"><span class="material-symbols-outlined" aria-hidden="true">checklist</span>{{ t('create') }}</button>
            <button v-else type="button" class="primary" :disabled="!canWrite" @click="saveArtifact"><span class="material-symbols-outlined" aria-hidden="true">attach_file</span>{{ t(artifact.kind === 'graph' ? 'saveGraph' : 'save') }}</button>
            <button type="button" class="secondary" :disabled="!hasContent" @click="download()"><span class="material-symbols-outlined" aria-hidden="true">download</span>{{ t('download') }}</button>
          </template>
        </template>
        <button type="button" class="quiet dismiss" @click="requestClose"><kbd>Esc</kbd> {{ t('close') }}</button>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
/* The overlay and drawer chrome are teleported by Element Plus. Namespace these
   global selectors to this instance type; leave the application theme untouched. */
:global(.el-overlay.agent-artifact-scrim) { background: rgb(8 8 8 / .72); backdrop-filter: grayscale(1) blur(4px); }
:global(.el-drawer.agent-artifact-drawer) {
  --paper: #101010; --card: #151515; --raised: #222; --line: #323232; --ink: #f3f3f3; --muted: #aaa;
  --mono: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, monospace;
  background: var(--paper); color: var(--ink); color-scheme: dark; border-inline-start: 1px solid var(--line);
  font: 13px/1.5 var(--mono); text-align: start; max-width: 100%; box-shadow: -6px 0 26px #0004;
}
:global(.agent-artifact-drawer .el-drawer__header), :global(.agent-artifact-drawer .el-drawer__footer) {
  display: block; margin: 0; padding: 0; background: #171717; color: var(--ink); flex: none;
}
:global(.agent-artifact-drawer .el-drawer__header) { border-bottom: 1px solid var(--line); }
:global(.agent-artifact-drawer .el-drawer__footer) { border-top: 1px solid var(--line); padding-bottom: env(safe-area-inset-bottom, 0px); }
:global(.agent-artifact-drawer .el-drawer__body) { display: flex; flex-direction: column; min-height: 0; padding: 0; overflow: hidden; }
.drawer-heading, .tab-bar, .actions { display: flex; align-items: center; gap: 8px; padding-inline: 16px; }
.drawer-heading { min-height: 56px; padding-block: 4px; }
.file-icon, .badge { background: var(--raised); border: 1px solid var(--line); border-radius: 4px; }
.file-icon { display: grid; place-items: center; width: 28px; height: 28px; flex: none; }
.material-symbols-outlined { font-size: 16px; flex: none; }
.file-info { flex: 1; min-width: 0; }
.file-title, .metadata, h3, .file-picker { display: flex; align-items: center; gap: 7px; min-width: 0; }
.file-title strong, .file-picker > span:first-child, .metadata { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-title strong, .file-picker { font-weight: 600; }
.file-picker { position: relative; max-width: 100%; cursor: pointer; }
.file-picker select { position: absolute; inset: 50% 0 auto; transform: translateY(-50%); width: 100%; height: 44px; opacity: 0; cursor: pointer; }
.file-picker:focus-within, :is(button, select, input, summary, [tabindex]):focus-visible { outline: 2px solid #eee; outline-offset: -2px; border-radius: 4px; }
.badge { padding: 0 6px; font-size: 10px; letter-spacing: .09em; text-transform: uppercase; color: #ccc; }
.metadata { color: var(--muted); font-size: 11px; gap: 0; }
.metadata span + span::before { content: '·'; padding: 0 5px; }
button, select, summary, .task-row { min-height: 44px; }
button { display: inline-flex; align-items: center; justify-content: center; gap: 5px; min-width: 44px; padding: 5px 9px; border: 1px solid transparent; border-radius: 5px; font: inherit; line-height: 1.3; cursor: pointer; }
button:disabled, select:disabled { opacity: .47; cursor: not-allowed; }
.quiet, .tabs button { background: transparent; color: #ccc; }
.square { padding: 0; }
button:not(:disabled):hover, summary:hover { background: #303030; color: #fff; }
.tab-bar { flex: none; min-height: 52px; border-bottom: 1px solid #242424; justify-content: space-between; }
.tabs { display: flex; flex-wrap: wrap; gap: 3px; }
.tabs button { font-size: 12px; }
.tabs button[aria-selected='true'] { background: var(--raised); border-color: #565656; color: #fff; }
.encoding, small, .hint { color: var(--muted); font-size: 12px; }
.scroll-body, .empty-state { flex: 1; min-height: 0; overflow: auto; overscroll-behavior: contain; padding: 16px; scrollbar-color: #555 transparent; }
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; color: var(--muted); }
.card { border: 1px solid #292929; border-radius: 5px; background: var(--card); padding: 12px; overflow-wrap: anywhere; }
.card + .card { margin-top: 12px; }
.view-panel { font-size: 12px; line-height: 1.65; }
.code-line { display: flex; align-items: baseline; gap: 8px; min-height: 1.65em; padding-inline: 3px; }
code, pre, kbd { font-family: var(--mono); }
.code-line code, pre { white-space: pre-wrap; overflow-wrap: anywhere; tab-size: 4; }
.code-line code { min-width: 0; }
.gutter { color: #a0a0a0; min-width: 3ch; text-align: end; flex: none; user-select: none; }
.meta { color: var(--muted); }
.hunk { margin-block: 6px; background: #212121; font-weight: 600; }
.added, .hunk { border-radius: 3px; }
.added { background: #303030; color: #fff; }
.removed { color: #a6a6a6; text-decoration: line-through; }
h3, h4, p, dl, dd, pre { margin: 0; }
h3 { border-bottom: 1px solid var(--line); padding-bottom: 6px; margin-bottom: 8px; font-size: 13px; font-weight: 600; }
h3 small { margin-inline-start: auto; font-weight: 400; white-space: nowrap; }
h4, .field-label { display: block; font-size: 12px; font-weight: 600; margin-block: 10px 5px; }
.graph-node, .comparison > div { border-radius: 4px; border: 1px solid var(--line); background: var(--paper); padding: 8px; }
.graph-node { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-block: 5px; }
.graph-node code { display: block; color: #bbb; }
.hashes { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 3px 10px; font-size: 12px; }
.hashes dt { color: var(--muted); }
select { color: var(--ink); background: var(--raised); border: 1px solid #565656; border-radius: 4px; padding-inline: 8px; font: inherit; }
.workspace-card select { width: 100%; }.workspace-card .field-label { margin-top: 0; }
.hint { margin-block: 8px; }
.comparison { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin-top: 10px; }
.comparison h4 { margin-top: 0; color: var(--muted); }
summary { display: list-item; align-content: center; color: #ccc; cursor: pointer; }
.digest, .preview-diff { border-top: 1px solid var(--line); margin-top: 8px; }
.task-row { display: flex; align-items: center; gap: 10px; border-bottom: 1px solid var(--line); cursor: pointer; }
.task-row input { accent-color: #eee; width: 18px; height: 18px; margin: 0; flex: none; }
.task-row input:checked + span { color: var(--muted); text-decoration: line-through; }
.checklist button { margin-top: 8px; }
.actions { gap: 8px; flex-wrap: wrap; padding-block: 10px; }
.primary, .secondary { font-family: system-ui, sans-serif; font-weight: 600; padding-inline: 12px; }
.primary { background: #f4f4f4; color: #161616; }.primary:not(:disabled):hover { background: #d6d6d6; color: #101010; }
.secondary { background: var(--raised); color: var(--ink); border-color: var(--line); }
.dismiss { margin-inline-start: auto; font-size: 12px; }
.feedback { padding: 8px 16px 0; overflow-wrap: anywhere; font-size: 12px; color: #d4d4d4; }
.problem { font-weight: 600; }.feedback button { margin-inline-start: 8px; text-decoration: underline; }
@media (max-width: 480px) {
  .drawer-heading, .tab-bar, .actions { padding-inline: 10px; gap: 4px; }
  .scroll-body { padding: 10px; }.comparison { grid-template-columns: 1fr; }
  .copy-label, .encoding { display: none; }.tabs { width: 100%; }.tabs button { flex: 1; padding-inline: 5px; }
  .actions { gap: 4px; }.actions button { font-size: 12px; }
}
@media (prefers-reduced-motion: reduce) {
  :global(.agent-artifact-scrim), :global(.el-drawer.agent-artifact-drawer) { animation-duration: .01ms !important; transition-duration: .01ms !important; }
}
</style>
