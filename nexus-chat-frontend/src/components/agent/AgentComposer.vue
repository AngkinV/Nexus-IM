<template>
  <footer class="composer-foot">
    <el-collapse class="scope-controls">
      <el-collapse-item name="scope" :title="g('agentScope.title')">
        <p>{{ g('agentScope.nextSelection') }}</p>
        <p>{{ g('agentScope.candidate') }}</p>
        <el-button :disabled="busy || workspace.scopeLoading" @click="loadScope">{{ g('agentScope.reload') }}</el-button>
        <p v-if="!resources.length && !workspace.scopeLoading">{{ g('agentScope.empty') }}</p>
        <el-select :model-value="targetKeys" multiple :aria-label="g('agentScope.title')" :disabled="busy || workspace.scopeLoading" @update:model-value="chooseTargets">
          <el-option v-for="item in resources" :key="resourceKey(item)" :value="resourceKey(item)" :label="`${g('agentScope.' + item.type)} · ${item.label} (#${item.id})`" />
        </el-select>
        <el-input :model-value="workspace.scopeSelection.timeRange.fromInclusive || ''" :aria-label="g('agentScope.fromLabel')" :placeholder="g('agentScope.fromLabel')" @update:model-value="v => changeRange('fromInclusive', v)" />
        <el-input :model-value="workspace.scopeSelection.timeRange.toExclusive || ''" :aria-label="g('agentScope.toLabel')" :placeholder="g('agentScope.toLabel')" @update:model-value="v => changeRange('toExclusive', v)" />
        <el-input :model-value="workspace.scopeSelection.timeRange.timeZone" :aria-label="g('agentScope.zone')" :placeholder="g('agentScope.zone')" @update:model-value="v => changeRange('timeZone', v)" />
        <p v-if="!targetKeys.length">{{ g('agentScope.none') }}</p>
        <p>{{ g('agentScope.version') }}: {{ workspace.bindingVersion ?? '—' }} · {{ g('agentScope.generation') }}: {{ workspace.sessionGeneration ?? '—' }}</p>
        <p>{{ g('agentScope.override') }}</p>
        <p role="status">{{ g(workspace.scopeConfirmed ? 'agentScope.confirmed' : 'agentScope.unconfirmed') }}</p>
        <el-button :disabled="busy" @click="confirmScope">{{ g('agentScope.confirm') }}</el-button>
        <el-button :disabled="busy || !workspace.scopeConfirmed" @click="saveScope">{{ g('agentScope.save') }}</el-button>
        <p role="status">{{ g(receiptActive ? 'agentScope.currentReceipt' : 'agentScope.recentReceipt') }}</p>
        <p v-if="receiptMessage?.scopeState === 'unavailable'" role="status">{{ g('agentScope.resultUnavailable') }}</p>
        <template v-else-if="receipt">
          <p>{{ g('agentScope.receiptObjects') }}: {{ g('agentScope.receiptChats') }} [{{ receipt.chatIds.join(', ') || '—' }}] · {{ g('agentScope.receiptContacts') }} [{{ receipt.contactUserIds.join(', ') || '—' }}]</p>
          <p>{{ g('agentScope.fromLabel') }}: {{ receipt.timeRange.fromInclusive ?? '—' }} · {{ g('agentScope.toLabel') }}: {{ receipt.timeRange.toExclusive ?? '—' }} · {{ receipt.timeRange.timeZone }}</p>
          <p>{{ g('agentScope.authorizationVersion') }}: {{ receipt.authorizationVersion }}</p>
        </template>
        <p v-else>{{ g('agentScope.receiptUnknown') }}</p>
        <p v-if="workspace.scopeError" role="alert">{{ workspace.scopeError }}</p>
      </el-collapse-item>
    </el-collapse>
    <div class="prompt-box">
      <div v-if="selected.length" class="selected-files"><button v-for="file in selected" :key="file.id" type="button" :title="t('removeContext')" @click="toggle(file)"><span aria-hidden="true" class="material-symbols-outlined">draft</span>{{ file.fileName }} <span aria-hidden="true">×</span></button></div>
      <label class="sr-only" for="agent-workbench-prompt">{{ t('prompt') }}</label>
      <textarea id="agent-workbench-prompt" ref="input" rows="2" :value="draft" :placeholder="g('agent.placeholder')" @input="inputChanged" @keydown="keyDown" @compositionstart="composing = true" @compositionend="endComposition"></textarea>
      <div class="control-line"><div class="tools"><button type="button" class="tool" :aria-label="t('attach')" :title="t('attach')" :disabled="uploading || busy" @click="picker.click()"><span class="material-symbols-outlined" aria-hidden="true">attach_file</span></button><input ref="picker" type="file" multiple hidden @change="uploadFiles" />
        <button type="button" class="tool" :class="{ on: web }" :aria-label="t('web')" :aria-pressed="web" :title="t('webHint')" @click="web = !web"><span class="material-symbols-outlined" aria-hidden="true">language</span></button>
        <button type="button" class="tool" :class="{ on: collaboration }" :aria-label="t('collaboration')" :aria-pressed="collaboration" :title="t('collaborationHint')" @click="collaboration = !collaboration"><span class="material-symbols-outlined" aria-hidden="true">hub</span></button>
        <button type="button" class="tool" :aria-label="t('workspace')" :aria-expanded="workspaceOpen" :title="t('workspace')" @click="openWorkspace"><span class="material-symbols-outlined" aria-hidden="true">folder_open</span></button>
        <ElButton text class="tool mcp-entry" :aria-label="t('mcp')" :aria-expanded="mcpOpen" :title="t('mcp')" :disabled="agent.switching" @click="mcpOpen = true"><span class="material-symbols-outlined" aria-hidden="true">extension</span></ElButton>
        <KnowledgeBaseSelector @manage="emit('manage-kb')" />
      </div><div class="send-tools"><span class="count">{{ count }}/4000</span>
        <button v-if="!agent.streaming" type="button" class="send" :aria-label="g('agent.send')" :title="g('agent.send')" :disabled="!draft.trim() || busy || uploading || (targetKeys.length > 0 && !workspace.scopeConfirmed)" @click="send"><span class="material-symbols-outlined" aria-hidden="true">arrow_upward</span></button>
        <button v-else type="button" class="send" :aria-label="g(agent.messages.at(-1)?.status === 'stop_requested' ? 'agent.stopping' : 'agent.stop')" :title="g(agent.messages.at(-1)?.status === 'stop_requested' ? 'agent.stopping' : 'agent.stop')" :disabled="agent.messages.at(-1)?.status === 'stop_requested'" @click="agent.cancelStream()?.catch?.(() => {})"><span class="material-symbols-outlined" aria-hidden="true">stop</span></button>
      </div></div>
      <p v-if="uploading" role="status" class="feedback">{{ t('uploading') }}</p>
      <p v-if="errorText" class="feedback" role="alert">{{ errorText }} <button v-if="retryFile" type="button" @click="upload([retryFile])">{{ t('retry') }}</button></p>
    </div>
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
import AgentMcpManager from './AgentMcpManager.vue'

const props = defineProps({ options: { type: Object, required: true }, mcpRunAuthorization: { type: Object, default: null } })
const emit = defineEmits(['manage-kb'])
const { t: g } = useI18n({ useScope: 'global' })
const { t } = useI18n({ useScope: 'local', messages: {
  en: { prompt:'Message', attach:'Attach context files', removeContext:'Remove from this prompt', web:'Web search', webHint:'Web search sends your question, not attachment contents, to the search service.', collaboration:'Collaboration', collaborationHint:'Collaboration runs three analyses and a synthesis using the selected model.', workspace:'Session files and tasks', mcp:'MCP services', uploading:'Uploading…', maxFiles:'Choose up to four files for this message.', fileSize:'Each attachment must be 5 MB or less.', files:'Context files', noFiles:'No files in this session.', tasks:'Session tasks', noTasks:'No tasks created from artifacts.', retry:'Retry', deleteFile:'Remove this file from the session?', sendFailed:'Could not send the message.' },
  zh: { prompt:'消息', attach:'添加上下文附件', removeContext:'从本轮移除', web:'联网检索', webHint:'联网检索只向搜索服务发送问题，不发送附件内容。', collaboration:'多智能体协同', collaborationHint:'协同模式将使用所选模型分别执行三次分析，再汇总回答。', workspace:'会话文件与任务', mcp:'MCP 服务', uploading:'正在上传…', maxFiles:'每轮最多选择四个文件。', fileSize:'单个附件不能超过 5 MB。', files:'上下文文件', noFiles:'会话中暂无文件。', tasks:'会话任务', noTasks:'尚未从产物创建任务。', retry:'重试', deleteFile:'从本会话移除这个文件？', sendFailed:'消息未发送。' }
} })
const agent = useAgentStore(), knowledge = useKnowledgeStore(), workspace = useAgentWorkspaceStore()
const receiptMessage = computed(() => agent.messages.findLast(m => m.role === 'assistant' && m.runId))
const receipt = computed(() => receiptMessage.value?.scopeState === 'observed' ? receiptMessage.value.actualScope : null)
const receiptActive = computed(() => agent.streaming && receiptMessage.value?.status === 'streaming')
const draft = ref(''), input = ref(null), picker = ref(null), composing = ref(false)
const selection = ref([]), web = ref(false), collaboration = ref(false), workspaceOpen = ref(false)
const mcpOpen = ref(false)
const submitting = ref(false), uploading = ref(false), errorText = ref(''), retryFile = ref(null)
const count = computed(() => Array.from(draft.value).length)
const busy = computed(() => agent.switching || submitting.value || agent.streaming)
const selected = computed(() => workspace.attachments.filter(f => selection.value.includes(f.id)))
const resources = computed(() => [...workspace.scopeResources.contacts, ...workspace.scopeResources.groups])
const resourceKey = item => `${item.type}:${item.id}`
const targetKeys = computed(() => workspace.scopeSelection.targets.map(resourceKey))
function chooseTargets(keys) {
  // A half-typed time boundary must not make choosing a target fail silently.
  try { workspace.setScope({ ...workspace.scopeSelection, targets: keys.map(key => resources.value.find(item => resourceKey(item) === key)).filter(Boolean).map(({ type, id }) => ({ type, id })) }) }
  catch (e) { errorText.value = messageOf(e) }
}
function changeRange(field, value) {
  // Editing incomplete ISO input must still invalidate the previous confirmation.
  workspace.scopeConfirmed = false
  workspace.scopeSelection = { ...workspace.scopeSelection, timeRange: { ...workspace.scopeSelection.timeRange, [field]: value || null } }
}
async function loadScope() { try { const sid = await agent.ensureSession(); await workspace.loadScope(sid) } catch (e) { errorText.value = messageOf(e) } }
function confirmScope() { try { workspace.confirmScope() } catch (e) { errorText.value = messageOf(e) } }
async function saveScope() { try { await workspace.saveScope(agent.sessionId) } catch (e) { errorText.value = messageOf(e) } }
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
  if (!text || busy.value || uploading.value || composing.value || (targetKeys.value.length && !workspace.scopeConfirmed)) return
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
.scope-controls{--el-color-primary:#888;--el-color-primary-light-3:#aaa;--el-color-primary-light-5:#bbb;--el-color-primary-light-7:#ccc;--el-color-primary-light-9:#eee;--el-color-primary-dark-2:#666;min-width:0;max-width:100%;margin-bottom:8px;color:inherit}.scope-controls :deep(.el-collapse-item__content){display:grid;gap:8px;color:inherit;overflow-wrap:anywhere}.scope-controls :deep(.el-select),.scope-controls :deep(.el-input){width:100%;min-width:0}.scope-controls :deep(.el-button){min-height:44px;margin:0;max-width:100%;white-space:normal}.scope-controls :deep(.el-collapse-item__header){min-height:44px}.scope-controls :deep(.el-input__wrapper),.scope-controls :deep(.el-select__wrapper){min-height:44px}.scope-controls :deep(:focus-visible){outline:2px solid #999;outline-offset:2px}
.mcp-entry.el-button{min-width:44px;min-height:44px;padding:0;margin:0;color:#aaaab2;background:transparent;border:0}.mcp-entry.el-button:hover,.mcp-entry.el-button:focus-visible{color:#fff;background:#323236}
.composer-foot{flex:none;padding:4px max(16px,calc((100% - 768px)/2)) max(10px,env(safe-area-inset-bottom));background:var(--wb-base,#131316);min-width:0}.prompt-box{padding:9px 10px 7px;background:var(--wb-card,#101012);border:1px solid #39393d;border-radius:12px}.prompt-box:focus-within{border-color:#9d9da3}textarea{display:block;width:100%;resize:none;background:transparent;border:0;outline:0;color:#eee;font:inherit;font-size:14px;line-height:1.55;min-height:40px;max-height:132px;padding:2px 4px;overflow-y:auto}textarea::placeholder{color:#96969e}.control-line,.tools,.send-tools,.selected-files{display:flex;align-items:center;gap:4px}.control-line{justify-content:space-between}.tools{min-width:0;flex-wrap:wrap}.send-tools{gap:6px}.tool,.send{width:30px;height:30px;display:grid;place-items:center;border-radius:6px;color:#aaaab2}.tool:hover,.tool.on,.tool:focus-visible{color:white;background:#323236}.tool.on{box-shadow:inset 0 0 0 1px #777}.send{background:#fafafa;color:#131316}.send:disabled,.tool:disabled{opacity:.45}.material-symbols-outlined{font-size:17px}.count,.disclosure,.feedback{font:11px/1.5 var(--wb-mono,monospace);color:#aaaab2}.disclosure{display:flex;gap:6px;flex-wrap:wrap;margin:4px 4px 0}.selected-files{flex-wrap:wrap;padding-bottom:5px}.selected-files button{display:flex;align-items:center;gap:4px;border:1px solid #424247;border-radius:20px;padding:2px 7px;font-size:11px}.feedback button,.file-row button{text-decoration:underline}.feedback{margin-top:5px}.workspace h4{font-weight:600;margin:14px 0 6px}.file-row,.task-row{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:6px 0;overflow-wrap:anywhere}.workspace input{accent-color:#888}.task-row{justify-content:flex-start}.composer-foot button:focus-visible{outline:2px solid #eee;outline-offset:2px}.tools :deep(.kb-text){max-width:110px}@media(max-width:700px){.composer-foot{padding-inline:10px}.tools :deep(.kb-text){max-width:68px}.prompt-box{padding:6px}}@media(pointer:coarse){textarea{font-size:16px}.tool,.send{min-width:44px;min-height:44px}.tools{gap:1px}.tools :deep(.kb-text){display:none}.count{font-size:10px}}
</style>
