<template>
  <footer class="composer-foot">
    <!-- The field is a capsule: add on the left, send on the right, the text between
         them. Everything that tunes the next message sits on the line underneath. -->
    <div class="prompt-box">
      <div v-if="selected.length" class="selected-files"><button v-for="file in selected" :key="file.id" type="button" :title="t('removeContext')" @click="toggle(file)"><span aria-hidden="true" class="material-symbols-outlined">draft</span>{{ file.fileName }} <span aria-hidden="true">×</span></button></div>
      <label class="sr-only" for="agent-workbench-prompt">{{ t('prompt') }}</label>
      <div class="prompt-row">
        <button type="button" class="tool attach" :aria-label="t('attach')" :title="t('attach')" :disabled="uploading || busy" @click="picker.click()"><span class="material-symbols-outlined" aria-hidden="true">add</span></button><input ref="picker" type="file" multiple hidden @change="uploadFiles" />
        <textarea id="agent-workbench-prompt" ref="input" rows="1" :value="draft" :placeholder="g('agent.placeholder')" @input="inputChanged" @keydown="keyDown" @compositionstart="composing = true" @compositionend="endComposition"></textarea>
        <button v-if="!agent.streaming" type="button" class="send" :aria-label="g('agent.send')" :title="g('agent.send')" :disabled="!draft.trim() || busy || uploading || scopePending" @click="send"><span class="material-symbols-outlined" aria-hidden="true">arrow_upward</span></button>
        <button v-else type="button" class="send" :aria-label="g(agent.messages.at(-1)?.status === 'stop_requested' ? 'agent.stopping' : 'agent.stop')" :title="g(agent.messages.at(-1)?.status === 'stop_requested' ? 'agent.stopping' : 'agent.stop')" :disabled="agent.messages.at(-1)?.status === 'stop_requested'" @click="agent.cancelStream()?.catch?.(() => {})"><span class="material-symbols-outlined" aria-hidden="true">stop</span></button>
      </div>
    </div>
    <div class="control-line"><div class="tools">
        <button type="button" class="tool" :class="{ on: web }" :aria-label="t('web')" :aria-pressed="web" :title="t('webHint')" @click="web = !web"><span class="material-symbols-outlined" aria-hidden="true">language</span></button>
        <button type="button" class="tool" :class="{ on: collaboration }" :aria-label="t('collaboration')" :aria-pressed="collaboration" :title="t('collaborationHint')" @click="collaboration = !collaboration"><span class="material-symbols-outlined" aria-hidden="true">hub</span></button>
        <button type="button" class="tool" :aria-label="t('workspace')" :aria-expanded="workspaceOpen" :title="t('workspace')" @click="openWorkspace"><span class="material-symbols-outlined" aria-hidden="true">folder_open</span></button>
        <ElButton text class="tool mcp-entry" :aria-label="t('mcp')" :aria-expanded="mcpOpen" :title="t('mcp')" :disabled="agent.switching" @click="mcpOpen = true"><span class="material-symbols-outlined" aria-hidden="true">extension</span></ElButton>
        <div class="scope-context-tools">
          <AgentScopePanel ref="scopePanel" :submitting="submitting" />
          <KnowledgeBaseSelector @manage="emit('manage-kb')" />
        </div>
      </div><div class="send-tools"><span class="count">{{ count }}/4000</span></div></div>
    <p v-if="scopePending && draft.trim() && !busy" class="feedback" role="status">{{ g('agentScope.confirmFirst') }} <button type="button" @click="scopePanel.open()">{{ g('agentScope.openPanel') }}</button></p>
    <p v-if="uploading" role="status" class="feedback">{{ t('uploading') }}</p>
    <p v-if="errorText" class="feedback" role="alert">{{ errorText }} <button v-if="retryFile" type="button" @click="upload([retryFile])">{{ t('retry') }}</button></p>
    <div v-if="web || collaboration" class="disclosure" role="status"><span v-if="web">{{ t('webHint') }}</span><span v-if="collaboration">{{ t('collaborationHint') }}</span></div>
    <el-dialog v-model="workspaceOpen" :title="t('workspace')" width="min(92vw, 520px)" append-to-body>
      <section class="workspace"><h4>{{ t('files') }}</h4><p v-if="!workspace.attachments.length">{{ t('noFiles') }}</p>
        <div v-for="file in workspace.attachments" :key="file.id" class="file-row"><label><input type="checkbox" :checked="selection.includes(file.id)" :disabled="!selection.includes(file.id) && selection.length >= 4" @change="toggle(file)" /> {{ file.fileName }}</label><button type="button" :title="g('common.delete')" @click="removeFile(file)">{{ g('common.delete') }}</button></div>
        <h4>{{ t('tasks') }}</h4><p v-if="!workspace.tasks.length">{{ t('noTasks') }}</p>
        <label v-for="task in workspace.tasks" :key="task.id" class="task-row"><input type="checkbox" :checked="task.status === 'done'" @change="changeTask(task)" /> {{ task.title }}</label>
        <p v-if="errorText" role="alert">{{ errorText }}</p>
      </section>
    </el-dialog>
    <AgentMcpManager v-model="mcpOpen" :run-authorization="mcpRunAuthorization" />
  </footer>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ElButton, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useAgentStore } from '@/stores/agent'
import { useKnowledgeStore } from '@/stores/knowledge'
import { useAgentWorkspaceStore } from '@/stores/agentWorkspace'
import KnowledgeBaseSelector from './KnowledgeBaseSelector.vue'
import AgentScopePanel from './AgentScopePanel.vue'
import AgentMcpManager from './AgentMcpManager.vue'

const props = defineProps({ options: { type: Object, required: true }, mcpRunAuthorization: { type: Object, default: null } })
const emit = defineEmits(['manage-kb'])
const { t: g } = useI18n({ useScope: 'global' })
const { t } = useI18n({ useScope: 'local', messages: {
  en: { prompt:'Message', attach:'Attach context files', removeContext:'Remove from this prompt', web:'Web search', webHint:'Web search sends your question, not attachment contents, to the search service.', collaboration:'Collaboration', collaborationHint:'Collaboration runs three analyses and a synthesis using the selected model.', workspace:'Session files and tasks', mcp:'MCP services', uploading:'Uploading…', maxFiles:'Choose up to four files for this message.', fileSize:'Each attachment must be 5 MB or less.', files:'Context files', noFiles:'No files in this session.', tasks:'Session tasks', noTasks:'No tasks created from artifacts.', retry:'Retry', deleteFile:'Remove this file from the session?', sendFailed:'Could not send the message.' },
  zh: { prompt:'消息', attach:'添加上下文附件', removeContext:'从本轮移除', web:'联网检索', webHint:'联网检索只向搜索服务发送问题，不发送附件内容。', collaboration:'多智能体协同', collaborationHint:'协同模式将使用所选模型分别执行三次分析，再汇总回答。', workspace:'会话文件与任务', mcp:'MCP 服务', uploading:'正在上传…', maxFiles:'每轮最多选择四个文件。', fileSize:'单个附件不能超过 5 MB。', files:'上下文文件', noFiles:'会话中暂无文件。', tasks:'会话任务', noTasks:'尚未从产物创建任务。', retry:'重试', deleteFile:'从本会话移除这个文件？', sendFailed:'消息未发送。' }
} })
const agent = useAgentStore(), knowledge = useKnowledgeStore(), workspace = useAgentWorkspaceStore()
const draft = ref(''), input = ref(null), picker = ref(null), composing = ref(false)
const selection = ref([]), web = ref(false), collaboration = ref(false), workspaceOpen = ref(false)
const mcpOpen = ref(false), scopePanel = ref(null)
const submitting = ref(false), uploading = ref(false), errorText = ref(''), retryFile = ref(null)
const count = computed(() => Array.from(draft.value).length)
const busy = computed(() => agent.switching || submitting.value || agent.streaming)
const selected = computed(() => workspace.attachments.filter(f => selection.value.includes(f.id)))
// Selecting chats to read is not consent to read them: with targets chosen, each
// request waits for its own confirmation in the scope panel.
const scopePending = computed(() => workspace.scopeSelection.targets.length > 0 && !workspace.scopeConfirmed)
let epoch = 0
const messageOf = err => err?.response?.data?.message || err?.message || g('common.failed')
watch(() => agent.sessionId, (sid, previous) => {
  workspace.select(sid)
  if (!previous || previous === sid) return
  epoch++; draft.value = ''; selection.value = []; retryFile.value = null; errorText.value = ''; workspaceOpen.value = false; uploading.value = false; submitting.value = false
})
function updateDraft(value) { draft.value = Array.from(value).slice(0, 4000).join('') }
function inputChanged(event) {
  if (composing.value || event.isComposing) draft.value = event.target.value
  else { updateDraft(event.target.value); if (event.target.value !== draft.value) event.target.value = draft.value }
}
function endComposition(e) { composing.value = false; inputChanged(e) }
function keyDown(e) { if (e.key !== 'Enter' || e.shiftKey || e.isComposing || composing.value || e.keyCode === 229) return; e.preventDefault(); send() }
function prefill(value) { updateDraft(value); input.value?.focus() }
defineExpose({ prefill })
watch(draft, async () => { await nextTick(); if (input.value) { input.value.style.height = 'auto'; input.value.style.height = `${Math.min(132, input.value.scrollHeight)}px` } })
function toggle(file) {
  if (selection.value.includes(file.id)) selection.value = selection.value.filter(id => id !== file.id)
  else if (selection.value.length < 4) selection.value = [...selection.value, file.id]
  else errorText.value = t('maxFiles')
}
async function uploadFiles(e) { const files = [...(e.target.files || [])]; e.target.value = ''; await upload(files) }
async function upload(files) {
  if (uploading.value || !files.length) return
  errorText.value = ''; retryFile.value = null
  if (files.length + selection.value.length > 4) { errorText.value = t('maxFiles'); return }
  if (files.some(f => f.size > 5 * 1024 * 1024)) { errorText.value = t('fileSize'); return }
  const start = epoch; uploading.value = true
  let processing
  try {
    const sid = await agent.ensureSession()
    if (start !== epoch) return
    for (const file of files) {
      processing = file; const stored = await workspace.upload(sid, file)
      if (start !== epoch || agent.sessionId !== sid) return
      if (!selection.value.includes(stored.id)) selection.value.push(stored.id)
    }
  } catch (e) { if (start === epoch) { errorText.value = messageOf(e); retryFile.value = processing } }
  finally { if (start === epoch) uploading.value = false }
}
async function send() {
  const text = draft.value.trim()
  if (!text || busy.value || uploading.value || composing.value || scopePending.value) return
  const start = epoch, original = draft.value
  submitting.value = true; errorText.value = ''; draft.value = ''
  try { await agent.sendMessage(text, { attachmentIds: [...selection.value], linkedKbId: knowledge.activeKbId, webSearchEnabled: web.value, collaborationEnabled: collaboration.value, ...props.options }); if (start === epoch) selection.value = [] }
  catch (e) { if (start === epoch) { errorText.value = messageOf(e); if (!draft.value) draft.value = original } }
  finally { if (start === epoch) submitting.value = false }
}
async function openWorkspace() { workspaceOpen.value = true; if (!agent.sessionId) return; try { await workspace.load(agent.sessionId) } catch (e) { errorText.value = messageOf(e) } }
async function removeFile(file) {
  const sid = agent.sessionId
  try { await ElMessageBox.confirm(t('deleteFile'), g('common.delete')) } catch { return }
  if (sid !== agent.sessionId) return
  try { await workspace.removeAttachment(sid, file.id); selection.value = selection.value.filter(id => id !== file.id) } catch(e) { errorText.value = messageOf(e) }
}
async function changeTask(task) { try { await workspace.updateTask(agent.sessionId,task.id, task.status === 'done' ? 'pending' : 'done') } catch(e) { errorText.value = messageOf(e) } }
</script>

<style scoped>
.composer-foot{flex:none;padding:4px max(16px,calc((100% - 768px)/2)) max(10px,env(safe-area-inset-bottom));background:var(--wb-base,#131316);min-width:0}
.prompt-box{padding:6px;background:var(--field-bg);border-radius:22px;box-shadow:0 0 0 1px var(--field-ring) inset;transition:box-shadow var(--field-ease)}
.prompt-box:hover{box-shadow:0 0 0 1px var(--field-ring-hover) inset}
.prompt-box:focus-within{box-shadow:0 0 0 1px var(--field-ring-focus) inset}
.prompt-row{display:flex;align-items:flex-end;gap:8px}
textarea{display:block;flex:1;min-width:0;resize:none;background:transparent;border:0;outline:0;color:var(--field-text);caret-color:#fff;font:inherit;font-size:13.5px;line-height:20px;min-height:32px;max-height:132px;padding:6px 2px;overflow-y:auto}
textarea::placeholder{color:var(--field-placeholder)}
.control-line,.tools,.send-tools,.selected-files{display:flex;align-items:center;gap:4px}
.control-line{justify-content:space-between;min-height:26px;margin-top:6px;padding:0 4px}
.tools{min-width:0;flex-wrap:wrap;gap:2px}
.scope-context-tools{display:inline-flex;align-items:center;gap:2px;min-width:0;max-width:100%}
.send-tools{gap:6px;flex:none}
.tool,.send{flex:none;display:grid;place-items:center;border-radius:50%;transition:background-color var(--field-ease),color var(--field-ease),opacity var(--field-ease)}
.tool{width:28px;height:28px;color:var(--field-icon)}
.tool:hover,.tool.on,.tool:focus-visible{color:#fff;background:var(--field-btn-bg)}
.tool.attach,.send{width:var(--field-btn);height:var(--field-btn)}
.tool.attach{color:var(--field-btn-ink);background:var(--field-btn-bg)}
.tool.attach:hover,.tool.attach:focus-visible{color:#fff;background:var(--field-btn-bg-hover)}
.send{background:#fff;color:#2f3131}
.send:hover:not(:disabled){background:#e2e2e2}
.send:disabled{background:var(--field-btn-bg);color:#6f7274}
.tool:disabled{opacity:.45}
.material-symbols-outlined{font-size:17px}
.tool.attach .material-symbols-outlined,.send .material-symbols-outlined{font-size:18px}
.mcp-entry.el-button{width:28px;height:28px;min-width:0;min-height:0;padding:0;margin:0;color:var(--field-icon);background:transparent;border:0;border-radius:50%}
.mcp-entry.el-button:hover,.mcp-entry.el-button:focus-visible{color:#fff;background:var(--field-btn-bg)}
.count,.disclosure,.feedback{font:11px/1.5 var(--wb-mono,monospace);color:var(--field-icon)}
.count{padding:0 4px;color:var(--field-placeholder)}
.disclosure{display:flex;gap:6px;flex-wrap:wrap;margin:4px 8px 0}
.selected-files{flex-wrap:wrap;padding:2px 2px 6px}
.selected-files button{display:flex;align-items:center;gap:4px;border-radius:13px;padding:3px 9px;font-size:11px;color:var(--field-btn-ink);background:var(--field-btn-bg);transition:background-color var(--field-ease),color var(--field-ease)}
.selected-files button:hover{color:#fff;background:var(--field-btn-bg-hover)}
.selected-files .material-symbols-outlined{font-size:14px}
.feedback button,.file-row button{text-decoration:underline}
.feedback{margin:5px 8px 0}
.workspace h4{font-weight:600;margin:14px 0 6px}
.file-row,.task-row{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:6px 0;overflow-wrap:anywhere}
.workspace input{accent-color:#888}
.task-row{justify-content:flex-start}
.composer-foot button:focus-visible{outline:2px solid #eee;outline-offset:2px}
.tools :deep(.kb-text){max-width:110px}
@media(max-width:480px){.scope-context-tools :deep(.kb-text){display:none}}
@media(max-width:700px){.composer-foot{padding-inline:10px}.tools :deep(.kb-text){max-width:68px}}
@media(pointer:coarse){textarea{font-size:16px;line-height:24px;min-height:44px;padding:10px 2px}.prompt-box{border-radius:28px}.tool,.tool.attach,.send,.mcp-entry.el-button{width:44px;height:44px}.tools{gap:1px}.tools :deep(.kb-text){display:none}.count{font-size:10px}}
@media(prefers-reduced-motion:reduce){.prompt-box,.tool,.send,.selected-files button{transition:none}}
</style>
