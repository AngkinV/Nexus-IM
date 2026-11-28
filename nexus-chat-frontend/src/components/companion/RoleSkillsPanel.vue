<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { companionAPI } from '@/services/api'
import { companionSkills as api } from '@/services/companionSkills'
import { skillBundle } from './skillPackage'

const props = defineProps({ roleId: { type: [Number, String], default: null }, seedText: { type: String, default: '' }, artifactId: { type: String, default: '' }, sessionId: { type: String, default: '' } })
const { t } = useI18n({ useScope: 'local', messages: {
  zh: { title: '角色技能', refresh: '刷新', empty: '还没有技能。导入 SKILL.md 或包含参考资料的 JSON 包。', select: '选择技能', version: '版本', role: '试聊／应用角色', import: '导入文件', draft: '保存为草稿', edit: '编辑包内容', revise: '创建新版本', publish: '确认发布', apply: '应用此版本', export: '导出包', remove: '删除技能', trial: '隔离试聊', trialInput: '试聊消息', trialHelp: '试聊使用角色的真实模型，不写入聊天、记忆或关系数值。', trialLabel: '试聊结果 · 不写入角色记忆', latest: '编辑时选择最新版本。新版本不会自动替换已绑定版本。', boundary: '绑定更改从下一场交流生效；当前场次使用开始时的快照。', identity: '允许替换姓名、背景等身份设定', bindings: '已绑定版本', unbind: '解除绑定', published: '已发布', unpublished: '草稿 · 未启用', loading: '加载中', saving: '处理中', done: '已按后端回执保存', invalid: '包校验未通过', source: '来自工作台产物；保存后独立于原会话', confirmDelete: '删除此技能？已保存的场次快照不变，新场次不会加载已删除的技能。', confirmUnbind: '解除这个角色的技能绑定？', confirm: '确认', cancel: '取消', fileTooLarge: '文件不能超过 256 KiB', replyFailed: '试聊未得到完整回复，请检查角色模型配置。', current: '技能与版本', error: '操作失败，请刷新后重试。', changed: '技能或绑定已改变，请刷新后再操作。', enabled: '启用', notBound: '未绑定技能', packageLabel: 'SKILL.md 或 JSON 文本包', chooseRole: '选择角色', chooseSkill: '选择技能与版本' },
  en: { title: 'Role skills', refresh: 'Refresh', empty: 'No skills yet. Import SKILL.md or a JSON package with references.', select: 'Select skill', version: 'Revision', role: 'Trial / target role', import: 'Import file', draft: 'Save draft', edit: 'Edit package', revise: 'Create revision', publish: 'Confirm publish', apply: 'Apply this revision', export: 'Export package', remove: 'Delete skill', trial: 'Isolated trial', trialInput: 'Trial message', trialHelp: 'Uses the role’s real model without storing turns, memories or relationship changes.', trialLabel: 'Trial result · not stored in role memory', latest: 'Select the latest revision to edit. New revisions do not replace pinned versions.', boundary: 'Binding changes apply at the next scene boundary. The current scene keeps its starting snapshot.', identity: 'Allow replacement of identity fields such as name and background', bindings: 'Pinned revisions', unbind: 'Unbind', published: 'Published', unpublished: 'Draft · not enabled', loading: 'Loading', saving: 'Working', done: 'Saved with a server receipt', invalid: 'Package validation failed', source: 'Workbench artifact; published assets remain independent of the source session', confirmDelete: 'Delete this skill? Existing scene snapshots stay unchanged. New scenes will not load a deleted skill.', confirmUnbind: 'Unbind this skill from the role?', confirm: 'Confirm', cancel: 'Cancel', fileTooLarge: 'Files must not exceed 256 KiB', replyFailed: 'No complete trial reply. Check the role’s model settings.', current: 'Skills and revisions', error: 'Operation failed. Refresh and retry.', changed: 'The skill or binding changed. Refresh and retry.', enabled: 'Enabled', notBound: 'No skills bound', packageLabel: 'SKILL.md or JSON text package', chooseRole: 'Select role', chooseSkill: 'Choose a skill and revision' }
} })
const user = useUserStore()
const owner = computed(() => user.currentUser?.id)
const library = ref([]), roles = ref([]), bindings = ref([])
const selectedId = ref(null), revision = ref(null), selectedRole = ref(null)
const effectiveRole = computed(() => props.roleId || selectedRole.value)
const asset = computed(() => library.value.find(s => String(s.id) === String(selectedId.value)))
const version = computed(() => asset.value?.versions?.find(v => v.revision === revision.value))
const published = computed(() => version.value?.status?.toLowerCase() === 'published')
const packageText = ref(props.seedText), trialInput = ref(''), trialReply = ref(''), trialReceipt = ref(null)
const replaceIdentity = ref(false), editing = ref(false), loading = ref(false), busy = ref(false), error = ref(''), notice = ref('')
let generation = 0
const context = () => ({ owner: owner.value, session: localStorage.getItem('token') })
const ticket = () => ({ generation, ...context() })
const current = s => s.generation === generation && String(s.owner) === String(owner.value) && s.session === localStorage.getItem('token')
const message = e => e?.response?.status === 409 ? t('changed') : e?.response?.status === 422 ? t('replyFailed') : e instanceof SyntaxError || /SKILL|package|Skill/.test(e?.message || '') ? t('invalid') : e?.message === t('replyFailed') ? t('replyFailed') : t('error')
function invalidate() { generation++; trialReply.value=''; trialReceipt.value=null; busy.value=false; error.value=''; notice.value='' }
onBeforeUnmount(invalidate)
async function run(operation, commit) {
  if (busy.value || loading.value || !owner.value) return
  const s=ticket(); busy.value=true; error.value=''; notice.value=''
  try { const result=await operation(s); if(current(s)) { commit?.(result); notice.value=t('done') } }
  catch(e) { if(current(s)) error.value=message(e) }
  finally { if(current(s)) busy.value=false }
}
function upsert(value) {
  if(!value?.id || !Array.isArray(value.versions)) throw new Error(t('error'))
  library.value=[value,...library.value.filter(s => s.id !== value.id)]
  selectedId.value=value.id; revision.value=value.latestRevision
}
async function load() {
  if(!owner.value) return
  const s=ticket(); loading.value=true; error.value=''
  try {
    const list=await api.list(s)
    const roleResponse=props.roleId ? null : await companionAPI.getRoles(s.owner)
    if(!current(s)) return
    library.value=Array.isArray(list)?list:[]
    if(roleResponse) roles.value=Array.isArray(roleResponse.data)?roleResponse.data:[]
    if(effectiveRole.value) { const rows=await api.bindings(effectiveRole.value,s); if(current(s)) bindings.value=rows }
  } catch(e) { if(current(s)) error.value=message(e) }
  finally { if(current(s)) loading.value=false }
}
watch(() => [owner.value,props.roleId], () => { invalidate(); loading.value=false; library.value=[]; roles.value=[]; bindings.value=[]; selectedRole.value=null; selectedId.value=null; revision.value=null; packageText.value=props.seedText; editing.value=false; replaceIdentity.value=false; load() }, { immediate: true })
watch(() => [selectedId.value,revision.value], () => { invalidate(); if(asset.value && !asset.value.versions.some(v => v.revision === revision.value)) revision.value=asset.value.latestRevision })
watch(effectiveRole, async () => {
  invalidate(); bindings.value=[]
  if(effectiveRole.value && owner.value) { const s=ticket(); try { const r=await api.bindings(effectiveRole.value,s); if(current(s)) bindings.value=r } catch(e) { if(current(s)) error.value=message(e) } }
})
function importFile(upload) {
  const file=upload.raw
  if(!file || file.size>262144) { error.value=t('fileTooLarge'); return }
  const s=ticket(); file.text().then(text => { if(current(s)) { packageText.value=text; editing.value=true } }).catch(e => { if(current(s)) error.value=message(e) })
}
function saveDraft() {
  let bundle
  try { bundle=skillBundle(packageText.value) } catch(e) { error.value=message(e); return }
  run(s => props.artifactId && props.sessionId ? api.fromArtifact(props.sessionId,props.artifactId,s) : api.create(bundle,s),upsert)
}
function editPackage() { if(!asset.value || !version.value) return; const a=asset.value.id,v=version.value.revision; run(s => api.export(a,v,s),r => { packageText.value=JSON.stringify(r,null,2); editing.value=true }) }
function revise() {
  if(!asset.value || !version.value || version.value.revision!==asset.value.latestRevision) return
  let bundle; try { bundle=skillBundle(packageText.value) } catch(e) { error.value=message(e); return }
  const a=asset.value.id,v=version.value
  run(s => api.revise(a,bundle,v.hash,s),upsert)
}
function publish() { if(!asset.value || !version.value) return; const a=asset.value.id,v=version.value; run(s => api.publish(a,v.revision,v.hash,s),upsert) }
function bindAsset() {
  if(!published.value || !effectiveRole.value) return
  const role=effectiveRole.value, a=asset.value.id, v=version.value.revision
  const existing=bindings.value.find(b => b.skillId===a)
  run(s => api.bind(role,{skillId:a,revision:v,enabled:true,replaceIdentity:replaceIdentity.value,expectedVersion:existing?.version},s),r => { bindings.value=r })
}
function toggleBinding(row, enabled) {
  const role=effectiveRole.value
  run(s => api.bind(role,{skillId:row.skillId,revision:row.revision,enabled,replaceIdentity:row.replaceIdentity,expectedVersion:row.version},s),r => { bindings.value=r })
}
async function confirmAction(text) {
  try { await ElMessageBox.confirm(text,t('title'),{confirmButtonText:t('confirm'),cancelButtonText:t('cancel'),customClass:'companion-skill-confirm'}); return true } catch { return false }
}
async function unbind(row) { const s=ticket(),role=effectiveRole.value; if(await confirmAction(t('confirmUnbind')) && current(s)) run(c => api.unbind(role,row.skillId,row.version,c),r => { bindings.value=r }) }
async function remove() { if(!asset.value) return; const s=ticket(),a=asset.value.id; if(await confirmAction(t('confirmDelete')) && current(s)) run(c => api.remove(a,c),() => { library.value=library.value.filter(v => v.id!==a); selectedId.value=null; revision.value=null; load() }) }
function trial() {
  if(!asset.value || !version.value || !effectiveRole.value || !trialInput.value.trim()) return
  const a=asset.value.id,v=version.value.revision,body={roleId:effectiveRole.value,input:trialInput.value,replaceIdentity:replaceIdentity.value}
  run(s => api.trial(a,v,body,s),r => { trialReceipt.value=r.contextReceipt; if(!r.ok) throw new Error(t('replyFailed')); trialReply.value=r.reply })
}
function exportPackage() {
  if(!asset.value || !version.value) return
  const a=asset.value.id,v=version.value.revision
  run(s => api.export(a,v,s),r => { const url=URL.createObjectURL(new Blob([JSON.stringify(r,null,2)],{type:'application/json'})); const link=document.createElement('a'); link.href=url; link.download=`skill-${a}-r${v}.json`; link.click(); URL.revokeObjectURL(url) })
}
</script>

<template>
  <section class="skill-panel" :aria-busy="busy || loading">
    <header><h3>{{ t('title') }}</h3><el-button :disabled="busy || loading" @click="load">{{ t('refresh') }}</el-button></header>
    <p class="skill-help">{{ t('boundary') }}</p>
    <p v-if="artifactId" class="skill-help">{{ t('source') }}</p>
    <p v-if="error" class="skill-error" role="alert">{{ error }}</p>
    <p v-else-if="notice || loading || busy" class="skill-help" role="status">{{ loading ? t('loading') : busy ? t('saving') : notice }}</p>
    <el-form label-position="top" @submit.prevent>
      <el-form-item v-if="!roleId" :label="t('role')"><el-select v-model="selectedRole" :placeholder="t('chooseRole')" :disabled="busy"><el-option v-for="r in roles" :key="r.id" :value="r.id" :label="r.name" /></el-select></el-form-item>
      <p v-if="!library.length" class="skill-help">{{ t('empty') }}</p>
      <el-form-item v-if="library.length" :label="t('current')"><el-select v-model="selectedId" :placeholder="t('select')" :disabled="busy" @change="revision = asset?.latestRevision"><el-option v-for="s in library" :key="s.id" :value="s.id" :label="s.name" /></el-select></el-form-item>
      <el-form-item v-if="asset" :label="t('version')"><el-select v-model="revision" :disabled="busy"><el-option v-for="v in asset.versions" :key="v.revision" :value="v.revision" :label="`r${v.revision} · ${v.packageVersion} · ${v.status?.toLowerCase() === 'published' ? t('published') : t('unpublished')}`" /></el-select></el-form-item>
      <div class="skill-actions"><el-upload accept=".md,.json" :auto-upload="false" :show-file-list="false" :on-change="importFile" :disabled="busy"><el-button :disabled="busy">{{ t('import') }}</el-button></el-upload><el-button v-if="asset" :disabled="busy" @click="editPackage">{{ t('edit') }}</el-button></div>
      <el-form-item v-if="editing || seedText || !library.length" :label="t('packageLabel')"><el-input v-model="packageText" type="textarea" :rows="7" :disabled="busy" :aria-label="t('packageLabel')" /></el-form-item>
      <div class="skill-actions"><el-button :disabled="busy || !packageText.trim()" @click="saveDraft">{{ t('draft') }}</el-button><el-button v-if="asset && editing" :disabled="busy || version?.revision !== asset.latestRevision" @click="revise">{{ t('revise') }}</el-button><el-button v-if="asset" :disabled="busy" @click="exportPackage">{{ t('export') }}</el-button><el-button v-if="asset" :disabled="busy" @click="remove">{{ t('remove') }}</el-button></div>
      <template v-if="version">
        <p class="skill-help">{{ t('latest') }}</p><p class="skill-help">{{ published ? t('published') : t('unpublished') }} · r{{ version.revision }}</p>
        <el-checkbox v-model="replaceIdentity" :disabled="busy">{{ t('identity') }}</el-checkbox>
        <div class="skill-actions"><el-button v-if="!published" :disabled="busy" @click="publish">{{ t('publish') }}</el-button><el-button :disabled="busy || !published || !effectiveRole" @click="bindAsset">{{ t('apply') }}</el-button></div>
        <el-form-item :label="t('trialInput')"><el-input v-model="trialInput" type="textarea" :rows="2" maxlength="2000" :disabled="busy" :aria-label="t('trialInput')" /></el-form-item><p class="skill-help">{{ t('trialHelp') }}</p><el-button :disabled="busy || !effectiveRole || !trialInput.trim()" @click="trial">{{ t('trial') }}</el-button>
        <div v-if="trialReply" class="skill-trial"><small>{{ trialReceipt?.trial ? t('trialLabel') : '' }}</small><p>{{ trialReply }}</p></div>
      </template>
    </el-form>
    <h4 v-if="effectiveRole">{{ t('bindings') }}</h4><p v-if="effectiveRole && !bindings.length" class="skill-help">{{ t('notBound') }}</p>
    <div v-for="b in bindings" :key="b.skillId" class="skill-binding"><span>{{ b.name }} · r{{ b.revision }}</span><el-switch :model-value="b.enabled" :disabled="busy" :aria-label="`${t('enabled')} ${b.name}`" @change="toggleBinding(b,$event)" /><el-button :disabled="busy" @click="unbind(b)">{{ t('unbind') }}</el-button></div>
  </section>
</template>

<style scoped>
.skill-panel { min-width:0; color:var(--el-text-color-primary); }
header,.skill-actions,.skill-binding { display:flex; align-items:center; flex-wrap:wrap; gap:8px; }
header { justify-content:space-between; } h3 { margin:0; font-size:15px; } h4 { font-size:13px; margin:20px 0 10px; }
.skill-help,small { color:var(--el-text-color-secondary); font-size:12px; line-height:1.6; overflow-wrap:anywhere; }
.skill-error { font-size:13px; padding:10px; border:1px solid var(--el-border-color); border-radius:8px; overflow-wrap:anywhere; }
.skill-actions { margin:12px 0; } .skill-binding { padding:10px 0; border-bottom:1px solid var(--el-border-color-lighter); }
.skill-binding span { flex:1; min-width:120px; font-size:13px; overflow-wrap:anywhere; } .skill-panel :deep(.el-select) { width:100%; }
.skill-panel :deep(.el-button + .el-button) { margin-left:0; } .skill-trial { padding:12px; margin:12px 0; border:1px solid var(--el-border-color); border-radius:10px; }
.skill-trial p { font-size:14px; line-height:1.7; white-space:pre-wrap; overflow-wrap:anywhere; }
@media(max-width:640px) { .skill-panel :deep(.el-button) { min-height:44px; } }
</style>
<style>
.companion-skill-confirm,.el-overlay:has(.companion-skill-confirm),.el-overlay-message-box:has(.companion-skill-confirm) { z-index:22000 !important; }
</style>
