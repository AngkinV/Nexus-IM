<template>
  <ElDrawer :model-value="modelValue" :title="t('mcp.title')" size="min(100vw, 920px)" append-to-body destroy-on-close class="agent-mcp-drawer" @update:model-value="close" @closed="restoreFocus">
    <section class="mcp-manager" :aria-label="t('mcp.title')">
      <p>{{ t('mcp.boundary') }}</p>
      <section class="run-authorization" :aria-label="t('mcp.runAuthorization')">
        <p>{{ t('mcp.runAuthorization') }}<span v-if="!reportedAuthorization">: {{ t('mcp.notReported') }}</span></p>
        <template v-if="reportedAuthorization">
          <p>{{ t('mcp.authorizationContext') }}: {{ reportedAuthorization.runId }} / {{ reportedAuthorization.attemptId }} / {{ reportedAuthorization.agentId }}</p>
          <p v-if="!reportedAuthorization.services.length">{{ t('mcp.noRunTools') }}</p>
          <p v-for="service in reportedAuthorization.services" :key="service.serverId">{{ serviceLabel(service.serverId) }} · v{{ service.configVersion }} · {{ service.toolIds.join(', ') || t('mcp.noRunTools') }}</p>
        </template>
      </section>
      <div class="mcp-actions">
        <ElButton :loading="workspace.mcp.loading" :disabled="busy || !signedIn" @click="perform(() => workspace.loadMcpServers())">{{ t('mcp.reload') }}</ElButton>
        <ElButton :disabled="busy || !signedIn" @click="edit()">{{ t('mcp.add') }}</ElButton>
      </div>
      <p v-if="!signedIn" role="alert">{{ t('mcp.signIn') }}</p>
      <p v-if="feedback || workspace.mcp.error" role="alert">{{ feedback || errorLabel(workspace.mcp.error) }}</p>
      <p v-if="workspace.mcp.loading" role="status">{{ t('mcp.loading') }}</p>
      <p v-else-if="!workspace.mcp.servers.length">{{ t('mcp.empty') }}</p>
      <div class="mcp-columns">
        <section class="mcp-list" :aria-label="t('mcp.servers')">
          <article v-for="server in workspace.mcp.servers" :key="server.serverId" class="mcp-server">
            <h3>{{ server.displayName }}</h3>
            <p class="mcp-url">{{ server.url }}</p>
            <div class="mcp-tags"><ElTag type="info">{{ t(`mcp.${mcpStateKey(server)}`) }}</ElTag><ElTag type="info">{{ t(`mcp.${server.registrationState}`) }}</ElTag><ElTag type="info">{{ t(server.enabled ? 'mcp.enabled' : 'mcp.disabled') }}</ElTag></div>
            <p>{{ t('mcp.versions') }}: {{ server.configVersion }} / {{ server.authorizationVersion ?? t('mcp.notReported') }} / {{ server.catalogVersion ?? t('mcp.notReported') }}</p>
            <p>{{ t('mcp.credential') }}: {{ t(server.credentialConfigured ? 'mcp.configured' : 'mcp.notConfigured') }}</p>
            <p v-if="server.status.checkedAt">{{ t('mcp.checkedAt') }}: {{ server.status.checkedAt }}</p>
            <p v-if="server.status.latencyMs !== null">{{ t('mcp.latency') }}: {{ server.status.latencyMs }} ms</p>
            <div class="mcp-actions">
              <ElButton :disabled="busy" @click="edit(server)">{{ t('mcp.edit') }}</ElButton>
              <ElSwitch :model-value="server.enabled" :disabled="busy" :aria-label="t('mcp.toggle')" @change="value => perform(() => workspace.patchMcpServer(server.serverId, server.configVersion, { enabled: value }))" />
              <ElButton :disabled="busy || !server.enabled" :title="t('mcp.probeHint')" @click="perform(() => workspace.probeMcpServer(server.serverId, server.configVersion))">{{ t('mcp.test') }}</ElButton>
              <ElButton :disabled="busy" @click="perform(() => workspace.loadMcpStatus(server.serverId, server.configVersion))">{{ t('mcp.status') }}</ElButton>
              <ElButton :disabled="busy" @click="perform(() => workspace.loadMcpTools(server.serverId, server.configVersion))">{{ t('mcp.tools') }}</ElButton>
              <ElButton :disabled="busy || !server.enabled" :title="t('mcp.probeHint')" @click="perform(() => workspace.probeMcpServer(server.serverId, server.configVersion, true))">{{ t('mcp.refresh') }}</ElButton>
              <ElButton :disabled="busy" @click="remove(server)">{{ t('mcp.delete') }}</ElButton>
            </div>
            <ElCollapse v-if="catalog(server)" class="mcp-catalog">
              <ElCollapseItem :title="`${t('mcp.tools')} (${catalog(server).tools.length})`" :name="server.serverId">
                <p>{{ t('mcp.allowBoundary') }}</p>
                <p v-if="!catalog(server).tools.length">{{ t('mcp.noTools') }}</p>
                <article v-for="tool in catalog(server).tools" :key="tool.toolId" class="mcp-tool">
                  <h4>{{ tool.remoteName }} <ElTag type="info">{{ t(tool.risk === 'read_only' ? 'mcp.riskReadOnly' : 'mcp.riskUnclassified') }}</ElTag></h4>
                  <p>{{ tool.description }}</p>
                  <p>{{ t('mcp.readOnlyHint') }}: {{ tool.readOnlyHint == null ? t('mcp.notReported') : t(tool.readOnlyHint ? 'mcp.yes' : 'mcp.no') }}</p>
                  <p>{{ t('mcp.allowed') }}: {{ t(server.allowedTools.includes(tool.toolId) ? 'mcp.yes' : 'mcp.no') }}</p>
                  <pre tabindex="0" :aria-label="t('mcp.schema')">{{ JSON.stringify(tool.inputSchema, null, 2) }}</pre>
                </article>
              </ElCollapseItem>
            </ElCollapse>
          </article>
        </section>
        <section v-if="editing" class="mcp-editor" :aria-label="t('mcp.edit')">
          <h3>{{ t(form.serverId ? 'mcp.edit' : 'mcp.add') }}</h3>
          <ElForm label-position="top" @submit.prevent="save">
            <ElFormItem :label="t('mcp.name')" required><ElInput ref="nameInput" v-model="form.displayName" maxlength="80" :disabled="busy" /></ElFormItem>
            <ElFormItem :label="t('mcp.url')" required><ElInput v-model="form.url" :disabled="busy" /></ElFormItem>
            <p>{{ t('mcp.transport') }}: Streamable HTTP</p>
            <ElFormItem :label="t('mcp.enabled')"><ElSwitch v-model="form.enabled" :disabled="busy" :aria-label="t('mcp.enabled')" /></ElFormItem>
            <ElFormItem :label="t('mcp.timeout')"><ElInputNumber v-model="form.timeoutSec" :min="1" :max="60" :step="1" :disabled="busy" /></ElFormItem>
            <ElFormItem :label="t('mcp.resultLimit')"><ElInputNumber v-model="form.resultMaxChars" :min="256" :max="64000" :step="1" :disabled="busy" /></ElFormItem>
            <ElFormItem :label="t('mcp.credentialKind')"><ElSelect v-model="form.credentialKind" :disabled="busy || form.clearCredential" @change="form.secret = ''"><ElOption label="Bearer" value="bearer" /><ElOption :label="t('mcp.header')" value="header" /></ElSelect></ElFormItem>
            <ElFormItem v-if="form.credentialKind === 'header'" :label="t('mcp.headerName')"><ElSelect v-model="form.headerName" :disabled="busy || form.clearCredential"><ElOption label="X-API-Key" value="X-API-Key" /><ElOption label="api-key" value="api-key" /></ElSelect></ElFormItem>
            <ElFormItem :label="t('mcp.newCredential')"><ElInput v-model="form.secret" maxlength="4000" type="password" autocomplete="new-password" :disabled="busy || form.clearCredential" /></ElFormItem>
            <p>{{ t('mcp.keepCredential') }}</p><p>{{ t('mcp.connectionChanged') }}</p>
            <ElCheckbox v-if="form.serverId" v-model="form.clearCredential" :disabled="busy" @change="form.secret = ''">{{ t('mcp.clearCredential') }}</ElCheckbox>
            <section v-if="form.serverId">
              <h4>{{ t('mcp.allowed') }}</h4><p>{{ t('mcp.allowBoundary') }}</p>
              <ElCheckboxGroup v-model="form.allowedTools" :disabled="busy">
                <ElCheckbox v-for="tool in editableTools" :key="tool.toolId" :value="tool.toolId" :label="tool.toolId">{{ tool.remoteName }}</ElCheckbox>
              </ElCheckboxGroup>
              <p v-if="!editableTools.length">{{ t('mcp.loadToolsFirst') }}</p>
            </section>
            <div class="mcp-actions"><ElButton native-type="submit" :loading="busy" :disabled="!signedIn">{{ t('mcp.save') }}</ElButton><ElButton :disabled="busy" @click="cancelEdit">{{ t('mcp.cancel') }}</ElButton></div>
          </ElForm>
        </section>
      </div>
    </section>
  </ElDrawer>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElDrawer, ElForm, ElFormItem, ElInput, ElInputNumber, ElSelect, ElOption, ElButton, ElSwitch, ElTag, ElCollapse, ElCollapseItem, ElCheckbox, ElCheckboxGroup, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useAgentWorkspaceStore } from '@/stores/agentWorkspace'
import { useUserStore } from '@/stores/user'
import { agentOwnerId } from '@/services/agentApi'
import { clearMcpCredential, mcpPayload, mcpStateKey, publicMcpRunAuthorization } from './mcpManagement'
import { mcpMessages } from './mcpMessages'

const props = defineProps({ modelValue: Boolean, runAuthorization: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue'])
const { t } = useI18n({ useScope: 'local', messages: mcpMessages })
const workspace = useAgentWorkspaceStore(), user = useUserStore()
const busy = ref(false), feedback = ref(''), editing = ref(false), nameInput = ref(null)
const form = reactive({ serverId: null, configVersion: null, displayName: '', url: '', enabled: false, timeoutSec: 10, resultMaxChars: 8000, credentialKind: 'bearer', headerName: '', secret: '', clearCredential: false, allowedTools: [] })
const signedIn = computed(() => Boolean(user.currentUser?.id && user.token))
const reportedAuthorization = computed(() => publicMcpRunAuthorization(props.runAuthorization))
const serviceLabel = id => workspace.mcp.servers.find(server => server.serverId === id)?.displayName || id
const editableTools = computed(() => workspace.mcp.catalogs[form.serverId]?.configVersion === form.configVersion ? workspace.mcp.catalogs[form.serverId].tools : [])
let epoch = 0, returnFocus = null
const catalog = server => workspace.mcp.catalogs[server.serverId]?.configVersion === server.configVersion ? workspace.mcp.catalogs[server.serverId] : null
const errorLabel = code => t(code === 'MCP_CONFLICT' ? 'mcp.conflict' : code === 'MCP_STALE' ? 'mcp.stale' : code === 'MCP_PROBE_FAILED' ? 'mcp.probeFailed' : 'mcp.unavailable')
function cancelEdit() { clearMcpCredential(form); editing.value = false }
function close(value = false) { ++epoch; busy.value = false; cancelEdit(); feedback.value = ''; emit('update:modelValue', value) }
function restoreFocus() { if (returnFocus?.isConnected) returnFocus.focus(); returnFocus = null }
function edit(server) {
  clearMcpCredential(form)
  Object.assign(form, { serverId: server?.serverId ?? null, configVersion: server?.configVersion ?? null,
    displayName: server?.displayName ?? '', url: server?.url ?? '', enabled: server?.enabled ?? false,
    timeoutSec: server?.timeoutSec ?? 10, resultMaxChars: server?.resultMaxChars ?? 8000,
    credentialKind: server?.credentialKind === 'header' ? 'header' : 'bearer', headerName: server?.credentialHeaderName ?? 'X-API-Key', allowedTools: [...(server?.allowedTools || [])] })
  editing.value = true; feedback.value = ''; nextTick(() => nameInput.value?.focus())
}
async function perform(action) {
  if (busy.value) return false
  const start = epoch, owner = agentOwnerId()
  busy.value = true; feedback.value = ''
  try { await action(); return start === epoch && owner === agentOwnerId() }
  catch (error) { if (start === epoch && owner === agentOwnerId()) feedback.value = errorLabel(error?.code); return false }
  finally { if (start === epoch) busy.value = false }
}
async function save() {
  let payload
  try { payload = mcpPayload(form, !form.serverId) }
  catch { feedback.value = t('mcp.invalidForm'); clearMcpCredential(form); return }
  const id = form.serverId, version = form.configVersion, start = epoch
  try {
    const saved = await perform(() => id ? workspace.patchMcpServer(id, version, payload) : workspace.createMcpServer(payload))
    if (saved) cancelEdit()
  } finally {
    if (payload.credential) payload.credential.secret = ''
    if (start === epoch) clearMcpCredential(form)
  }
}
async function remove(server) {
  const start = epoch, owner = agentOwnerId(), id = server.serverId, version = server.configVersion
  try { await ElMessageBox.confirm(t('mcp.deleteConfirm'), t('mcp.delete'), { confirmButtonText: t('mcp.delete'), cancelButtonText: t('mcp.cancel') }) } catch { return }
  if (start !== epoch || owner !== agentOwnerId() || !props.modelValue) return
  const deleted = await perform(() => workspace.deleteMcpServer(id, version))
  if (deleted && form.serverId === id) cancelEdit()
}
watch(() => props.modelValue, open => {
  if (open) { returnFocus = document.activeElement; workspace.syncMcpOwner(); perform(() => workspace.loadMcpServers()) }
  else { ++epoch; busy.value = false; cancelEdit(); feedback.value = '' }
})
watch(() => [user.currentUser?.id, Boolean(user.token)], () => {
  ++epoch; busy.value = false; clearMcpCredential(form); editing.value = false; feedback.value = ''
  workspace.syncMcpOwner(); if (props.modelValue) close()
}, { flush: 'post' })
onBeforeUnmount(() => { ++epoch; clearMcpCredential(form) })
</script>

<style>
.agent-mcp-drawer{--el-color-primary:#eee;--el-color-primary-light-3:#ccc;--el-color-primary-light-5:#999;--el-color-primary-light-7:#555;--el-color-primary-light-9:#303033;--el-color-primary-dark-2:#fff;--el-color-success:#ccc;--el-color-danger:#ddd;--el-color-warning:#ccc;--el-color-info:#bbb;--el-color-info-light-9:#303033;--el-bg-color:#19191c;--el-bg-color-overlay:#222225;--el-fill-color-blank:#19191c;--el-fill-color-light:#2b2b2f;--el-text-color-primary:#eee;--el-text-color-regular:#ddd;--el-text-color-secondary:#bbb;--el-border-color:#555;--el-border-color-light:#444;--el-mask-color:rgba(0,0,0,.7);background:var(--el-bg-color);color:var(--el-text-color-primary)}
.agent-mcp-drawer .el-drawer__header{color:var(--el-text-color-primary);margin-bottom:0}.agent-mcp-drawer .el-drawer__close-btn{min-width:44px;min-height:44px}.agent-mcp-drawer .el-drawer__body{padding:20px;overflow-x:hidden}.mcp-manager{font-size:14px;line-height:1.6;min-width:0}.mcp-manager p{margin:8px 0;overflow-wrap:anywhere}.mcp-columns{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:20px}.mcp-list:only-child{grid-column:1/-1}.mcp-server{padding:16px 0;border-bottom:1px solid var(--el-border-color)}.mcp-server h3,.mcp-editor h3{font-size:16px;margin:4px 0}.mcp-actions,.mcp-tags{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin:12px 0}.mcp-actions .el-button+.el-button{margin-left:0}.mcp-manager .el-button,.mcp-manager .el-checkbox,.mcp-manager .el-switch{min-height:44px}.mcp-manager .el-input__wrapper,.mcp-manager .el-select__wrapper{min-height:44px}.mcp-manager .el-checkbox{white-space:normal;height:auto;max-width:100%}.mcp-manager .el-checkbox__label{overflow-wrap:anywhere}.mcp-manager .el-checkbox-group{display:flex;flex-direction:column}.mcp-manager .el-switch{min-width:44px}.mcp-manager .el-button--primary{color:#19191c}.mcp-tool{padding:12px 0;border-bottom:1px solid var(--el-border-color)}.mcp-tool h4{overflow-wrap:anywhere}.mcp-tool pre{max-width:100%;max-height:260px;overflow:auto;padding:12px;background:var(--el-fill-color-light);white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.6 monospace}.mcp-editor{padding-top:16px;min-width:0}.run-authorization{border-left:2px solid var(--el-border-color);padding-left:12px}.mcp-manager :focus-visible{outline:2px solid #eee;outline-offset:2px}@media(max-width:700px){.mcp-columns{grid-template-columns:minmax(0,1fr)}.agent-mcp-drawer .el-drawer__body{padding:16px}.mcp-manager .el-input{font-size:16px}}@media(prefers-reduced-motion:reduce){.agent-mcp-drawer,.agent-mcp-drawer *{animation:none!important;transition:none!important}}
</style>
