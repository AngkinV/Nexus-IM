<template>
  <section ref="shell" class="agent-chat-view workbench" :aria-label="g('agent.title')">
    <header class="controls">
      <div class="breadcrumb"><button type="button" class="icon-btn" :aria-label="isMobile ? g('common.back') : t('sessions')" :title="isMobile ? g('common.back') : t('sessions')" @click="isMobile ? chat.clearActiveChat() : toggleList()"><span class="material-symbols-outlined" aria-hidden="true">{{ isMobile ? 'arrow_back' : 'side_navigation' }}</span></button><span class="root-name">{{ g('leftPanel.ai') }} /</span><h2 :title="sessionTitle">{{ sessionTitle }}</h2><span class="state-pill" role="status"><i :class="{ active: agent.streaming }"></i>{{ stateLabel }}</span></div>
      <div class="header-actions"><ModelChannelSwitcher :channels="providers.chatProviders" :active-id="activeChannel?.id ?? null" :label="modelLabel" :disabled="agent.streaming" @select="selectProvider" @manage="providersOpen = true" />
      <button type="button" class="output-count" :disabled="!files.length" :title="`${t('outputs')} · ⌘E`" :aria-label="t('outputs')" @click="openLatest"><span class="material-symbols-outlined" aria-hidden="true">folder_zip</span>{{ files.length }}</button>
      <button type="button" class="icon-btn" :disabled="agent.streaming || agent.switching || refreshing || !agent.sessionId" :title="t('reload')" :aria-label="t('reload')" @click="refresh"><span class="material-symbols-outlined" aria-hidden="true">refresh</span></button>
      <button type="button" class="icon-btn" :title="t('tune')" :aria-label="t('tune')" @click="startTuning"><span class="material-symbols-outlined" aria-hidden="true">tune</span></button>
      <el-dropdown trigger="click" @command="more"><button type="button" class="icon-btn" :title="t('more')" :aria-label="t('more')"><span class="material-symbols-outlined" aria-hidden="true">more_vert</span></button><template #dropdown><el-dropdown-menu>
        <el-dropdown-item command="fullscreen" :disabled="!fullscreenSupported">{{ t('fullscreen') }}</el-dropdown-item><el-dropdown-item command="export" :disabled="!agent.hasMessages">{{ g('agent.export') }}</el-dropdown-item><el-dropdown-item command="providers">{{ g('agent.providers.manage') }}</el-dropdown-item><el-dropdown-item command="knowledge">{{ g('agent.knowledge.manage') }}</el-dropdown-item><el-dropdown-item command="clear" divided :disabled="agent.streaming || !agent.hasMessages">{{ g('agent.clearMemory') }}</el-dropdown-item>
      </el-dropdown-menu></template></el-dropdown></div>
    </header>
    <div class="board-dock"><AgentExecutionBoard :run="lastReply" :model-label="modelLabel" @preview="openArtifact" /></div>
    <div ref="feed" class="feed" @scroll.passive="checkScroll"><div class="reading-column">
      <section v-if="!agent.hasMessages" class="welcome"><h3>{{ g('agent.welcomeTitle') }}</h3><p>{{ g('agent.welcomeBody') }}</p><div class="suggestions"><button v-for="item in examples" :key="item" type="button" @click="composer?.prefill(g(`agent.examples.${item}`))">{{ g(`agent.examples.${item}`) }} <span aria-hidden="true">↗</span></button></div></section>
      <article v-for="msg in agent.messages" :key="msg.id" :class="msg.role === 'user' ? 'user-message' : 'assistant-message'">
        <template v-if="msg.role === 'user'"><div class="user-copy"><p>{{ msg.content }}</p><small>{{ time(msg.createdAt) }}<span v-if="msg.createdAt"> · </span>{{ user.currentUser?.nickname || g('agent.you') }}</small></div><span class="avatar"><img v-if="user.currentUser?.avatar" :src="user.currentUser.avatar" alt="" /><span v-else aria-hidden="true" class="material-symbols-outlined">person</span></span></template>
        <template v-else><span class="avatar ai" aria-hidden="true"><span class="material-symbols-outlined">smart_toy</span></span><div class="reply">
          <AgentTrace v-if="msg.scopeState !== 'unavailable' && (msg.trace?.length || msg.status === 'streaming')" :trace="msg.trace || []" :run-id="msg.runId" :live="msg.status === 'streaming'" :elapsed-ms="msg.elapsedMs" :usage="msg.usage" :status="msg.status" :run="msg" @preview="openArtifact" />
          <div v-if="msg.scopeState === 'unavailable' || msg.content || msg.status !== 'streaming'" class="answer-card"><AgentMarkdown v-if="msg.content" :content="msg.content" :sources="msg.sources || []" @source="s => openSources(msg, s)" />
            <p v-if="msg.scopeState === 'unavailable'" class="diagnostic" role="status">{{ g('agentScope.resultUnavailable') }}</p>
            <p v-else-if="msg.error" class="diagnostic" role="alert">{{ errorText(msg.error) }}<small>{{ describeModelError(msg.error).detail }}</small></p>
            <p v-else-if="['interrupted','cancelled','limited','failed','partial','stop_requested'].includes(stateOfRun(msg))" class="diagnostic">{{ t('state.' + stateOfRun(msg)) }}<span v-if="msg.finishReason && stateOfRun(msg) !== 'stop_requested'"> · {{ msg.finishReason }}</span></p>
            <div v-if="msg.artifacts?.length" class="reply-files"><button v-for="f in msg.artifacts" :key="f.id" type="button" @click="openArtifact(f)"><span class="material-symbols-outlined" aria-hidden="true">draft</span>{{ f.name }}</button></div>
            <div v-if="msg.status !== 'streaming'" class="reply-actions"><button type="button" :disabled="!msg.content" @click="copyAnswer(msg)"><span class="material-symbols-outlined" aria-hidden="true">content_copy</span>{{ t('copy') }}</button><button type="button" :aria-label="t('retry')" :disabled="agent.streaming || agent.switching" @click="retry(msg)"><span class="material-symbols-outlined" aria-hidden="true">refresh</span>{{ t('retry') }}</button><button type="button" :aria-label="t('sources')" @click="openSources(msg)"><span class="material-symbols-outlined" aria-hidden="true">link</span>{{ t('sources') }}</button><button v-if="msg.resumable && msg.runId" :disabled="agent.streaming || agent.switching || waitSeconds(msg.retryAt) > 0" type="button" @click="resume(msg)">{{ waitSeconds(msg.retryAt) > 0 ? g('modelError.retryAfter', { seconds: waitSeconds(msg.retryAt) }) : g('agent.resume') }}</button><button v-if="msg.error && describeModelError(msg.error).settings" type="button" @click="providersOpen = true"><span class="material-symbols-outlined" aria-hidden="true">tune</span>{{ g('modelError.openSettings') }}</button><small v-if="msg.createdAt">{{ time(msg.createdAt) }}</small></div>
          </div>
        </div></template>
      </article>
    </div></div>
    <AgentComposer ref="composer" :options="options" @manage-kb="kbOpen = true" />
    <AgentArtifactDrawer v-model:visible="drawerOpen" :session-id="agent.sessionId || ''" :artifact="selectedFile" :run="fileRun" :artifacts="files" @select="openArtifact" />
    <AgentProviderSettings v-model:visible="providersOpen" /><KnowledgeBaseManager v-model:visible="kbOpen" />
    <el-dialog v-model="tuneOpen" :title="t('tune')" width="min(460px, 92vw)" append-to-body><form class="params" @submit.prevent="saveTuning"><label>{{ t('temperature') }}<input v-model.number="tuning.temperature" name="temperature" type="number" step="0.1" min="0" max="2" required></label><label>{{ t('tokens') }}<input v-model.number="tuning.maxOutputTokens" name="maxOutputTokens" type="number" min="1" max="8192" required></label><label>{{ t('iterations') }}<input v-model.number="tuning.maxIterations" name="maxIterations" type="number" min="1" max="12" required></label><button type="submit" class="action-primary">{{ g('common.confirm') }}</button></form></el-dialog>
    <el-dialog v-model="sourceOpen" :title="t('sources')" width="min(560px, 92vw)" append-to-body><div class="source-list"><p v-if="!sourceItems.length">{{ t('noSources') }}</p><section v-for="s in sourceItems" :key="s.id"><strong>[{{ s.id }}] {{ s.title }}</strong><small>{{ s.kind }}</small><p v-if="s.excerpt">{{ s.excerpt }}</p><a v-if="webUrl(s.url)" :href="webUrl(s.url)" target="_blank" rel="noopener noreferrer">{{ s.url }}</a></section></div></el-dialog>
  </section>
</template>

<script setup>
import { computed, inject, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { useAgentStore } from '@/stores/agent'
import { describeModelError, useAgentProvidersStore } from '@/stores/agentProviders'
import { useChatStore } from '@/stores/chat'
import { useUserStore } from '@/stores/user'
import { useAgentWorkspaceStore } from '@/stores/agentWorkspace'
import AgentComposer from './AgentComposer.vue'
import AgentTrace from './AgentTrace.vue'
import AgentExecutionBoard from './AgentExecutionBoard.vue'
import AgentMarkdown from './AgentMarkdown.vue'
import AgentArtifactDrawer from './AgentArtifactDrawer.vue'
import AgentProviderSettings from './AgentProviderSettings.vue'
import ModelChannelSwitcher from './ModelChannelSwitcher.vue'
import KnowledgeBaseManager from './KnowledgeBaseManager.vue'
import { buildRunPresentation } from './runPresentation.js'
import { processMessages } from './processMessages.js'

const { t: g } = useI18n({ useScope:'global' })
const { t } = useI18n({ useScope:'local', messages: {
  en:{state:processMessages.en.state,sessions:'Show or hide sessions',server:'Server default',reload:'Reload conversation',tune:'Tune request',more:'More actions',fullscreen:'Toggle fullscreen',outputs:'Preview artifacts',copy:'Copy answer',retry:'Retry answer',sources:'Sources',noSources:'No sources were recorded for this answer.',temperature:'Temperature',tokens:'Maximum output tokens',iterations:'Maximum model rounds'},
  zh:{state:processMessages.zh.state,sessions:'展开或收起会话列表',server:'服务端默认模型',reload:'刷新会话',tune:'参数调优',more:'更多操作',fullscreen:'切换全屏',outputs:'预览产物',copy:'复制全文',retry:'重新推理',sources:'引用溯源',noSources:'这条回答没有记录可核查的来源。',temperature:'温度',tokens:'最大输出 Tokens',iterations:'最大调用轮数'}
} })
const agent = useAgentStore(), providers = useAgentProvidersStore(), workspace = useAgentWorkspaceStore(), chat = useChatStore(), user = useUserStore()
const isMobile = inject('isMobile', ref(false)), toggleList = inject('toggleList', () => {}), activeView = inject('activeView', ref('ai'))
const shell = ref(null), composer = ref(null), feed = ref(null), follow = ref(true)
const drawerOpen = ref(false), sourceOpen = ref(false), kbOpen = ref(false), providersOpen = ref(false), tuneOpen = ref(false), refreshing = ref(false)
const sourceItems = ref([]), selectedFile = ref(null), fileRun = ref(null)
const examples = ['summarize','weeklyReport','quickReply']
const options = reactive({ temperature:0.2, maxOutputTokens:1024, maxIterations:6 }), tuning = reactive({ ...options })
const lastReply = computed(() => agent.messages.findLast(m => m.role === 'assistant') || null)
const files = computed(() => agent.messages.flatMap(m => m.artifacts || []))
const activeChannel = computed(() => providers.chatProviders.find(x => x.id === agent.activeProviderId) || providers.defaultProvider || null)
const modelLabel = computed(() => { const p = activeChannel.value; return p ? [p.displayName || p.provider,p.defaultModel].filter(Boolean).join(' · ') : t('server') })
const sessionTitle = computed(() => agent.activeSession?.title || g('agent.title'))
const stateOfRun = run => buildRunPresentation(run.trace || [], { runId: run.runId, status: run.status, live: agent.streaming && run.status === 'streaming' }).summary.state
const stateLabel = computed(() => { const run = lastReply.value; return t('state.' + (run ? stateOfRun(run) : agent.streaming ? 'unknown' : 'idle')) })
const time = date => date && dayjs(date).isValid() ? dayjs(date).format('HH:mm:ss') : ''
// Declining a confirmation dialog is a decision, not a failure to report.
const fail = e => { if (e !== 'cancel' && e !== 'close') ElMessage.error(e?.response?.data?.message || e?.message || g('common.failed')) }
const fullscreenSupported = typeof document !== 'undefined' && typeof document.documentElement?.requestFullscreen === 'function'
function errorText(error) { const described = describeModelError(error); return described.key ? g(described.key) : described.fallback }
// Continuing a rate-limited turn waits for as long as the backend asked. The clock only
// re-enables the button; it never resumes anything itself.
const waitNow = ref(Date.now())
let waitTimer = 0
const waitSeconds = retryAt => Math.max(0, Math.ceil(((retryAt || 0) - waitNow.value) / 1000))
watch(() => Math.max(0, ...agent.messages.map(m => m.retryAt || 0)), until => {
  clearInterval(waitTimer); waitNow.value = Date.now()
  if (until <= waitNow.value) return
  waitTimer = setInterval(() => { waitNow.value = Date.now(); if (waitNow.value >= until) clearInterval(waitTimer) }, 1000)
}, { immediate: true })
function webUrl(url) { if (!url) return null; try { const p = new URL(url); return ['http:','https:'].includes(p.protocol) ? p.href : null } catch { return null } }
function startTuning() { Object.assign(tuning, options); tuneOpen.value = true }
function saveTuning() { Object.assign(options,tuning); tuneOpen.value = false }
function selectProvider(id) { if (id === 'settings') providersOpen.value = true; else if (providers.chatProviders.some(p => p.id === id)) agent.setActiveProviderId(id) }
async function refresh() { refreshing.value = true; try { await Promise.all([agent.refreshActiveSession(), workspace.load(agent.sessionId)]) } catch(e) { fail(e) } finally { refreshing.value = false } }
async function copyAnswer(msg) { try { await navigator.clipboard.writeText(msg.content); ElMessage.success(g('agent.copied')) } catch(e) { fail(e) } }
async function retry(msg) { try { await agent.retryMessage(msg.id) } catch(e) { fail(e) } }
async function resume(msg) { try { await agent.resumeMessage(msg.id) } catch(e) { fail(e) } }
function openSources(msg, selected) { const list = msg.sources || []; sourceItems.value = selected ? list.filter(s => s.id === selected.id) : list; sourceOpen.value = true }
function openArtifact(artifact) {
  if (!artifact) return
  const run = agent.messages.find(m => m.artifacts?.some(f => f.id === artifact.id))
  if (!run) return
  fileRun.value = run; selectedFile.value = run.artifacts.find(f => f.id === artifact.id); drawerOpen.value = true
}
function openLatest() { openArtifact(files.value.at(-1)) }
function shortcut(e) {
  if (e.defaultPrevented || activeView.value !== 'ai' || !(e.metaKey || e.ctrlKey) || e.key.toLowerCase() !== 'e' || e.altKey || !files.value.length || drawerOpen.value || sourceOpen.value || kbOpen.value || providersOpen.value || tuneOpen.value) return
  e.preventDefault(); openLatest()
}
async function more(cmd) {
  try {
    if (cmd === 'providers') providersOpen.value = true
    if (cmd === 'knowledge') kbOpen.value = true
    if (cmd === 'fullscreen') { if (document.fullscreenElement) await document.exitFullscreen(); else await document.documentElement.requestFullscreen() }
    if (cmd === 'clear') {
      const sid = agent.sessionId
      try { await ElMessageBox.confirm(g('agent.clearConfirm'),g('agent.clearMemory')) } catch { return }
      if (agent.sessionId !== sid) return // answered after switching: never clear a session the dialog was not opened for
      await agent.clearConversation(); await workspace.load(agent.sessionId); ElMessage.success(g('agent.cleared'))
    }
    if (cmd === 'export') {
      const text = agent.messages.map(m => `## ${m.role === 'user' ? g('agent.you') : g('agent.title')}\n\n${m.content}`).join('\n\n')
      const url = URL.createObjectURL(new Blob([`# ${sessionTitle.value}\n\n${text}\n`], {type:'text/markdown;charset=utf-8'}))
      const link = document.createElement('a'); link.href = url; link.download = `${sessionTitle.value.replace(/[\\/:*?"<>|]/g, '_')}.md`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
    }
  } catch(e) { fail(e) }
}
function checkScroll() { const el = feed.value; if (el) follow.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80 }
function bottom() { const el = feed.value; if (el) el.scrollTo({ top:el.scrollHeight, behavior:'auto' }) }
watch(() => agent.sessionId, sid => {
  drawerOpen.value = sourceOpen.value = false; selectedFile.value = fileRun.value = null; sourceItems.value = []; follow.value = true
  workspace.load(sid).catch(fail) // uploads and tasks belong to the session now shown
})
watch(() => agent.messages.length, async () => { await nextTick(); if (agent.hasMessages) bottom() })
watch(() => { const m = lastReply.value; return [m?.content?.length,m?.trace?.length,m?.trace?.at(-1)?.text?.length] }, async () => { if (follow.value) { await nextTick(); bottom() } })
onMounted(async () => {
  window.addEventListener('keydown', shortcut)
  try { await Promise.all([providers.fetchAll(), agent.hydrateActiveSession()]); /* Older pickers also stored embedding credential IDs. Never send those for chat. */ if (!providers.lastError && !providers.chatProviders.some(p => p.id === agent.activeProviderId)) agent.setActiveProviderId(providers.defaultProvider?.id ?? null); if (agent.sessionId) await workspace.load(agent.sessionId) } catch(e) { fail(e) }
})
onUnmounted(() => { clearInterval(waitTimer); window.removeEventListener('keydown', shortcut) })
</script>

<style scoped>
.workbench{--wb-base:#131316;--wb-panel:#1b1b1e;--wb-card:#101012;--wb-line:#303034;--wb-mono:'JetBrains Mono',ui-monospace,monospace;display:flex;flex-flow:column;flex:1;min-width:0;height:100%;min-height:0;background:var(--wb-base);color:#e4e4e7;overflow:hidden;font-family:'Geist Sans',system-ui,sans-serif}.controls{display:flex;align-items:center;justify-content:space-between;gap:8px;flex:none;min-height:44px;border-bottom:1px solid var(--wb-line);padding:3px 14px;background:#101012}.breadcrumb,.header-actions,.state-pill,.model-btn,.output-count,.reply-actions,.reply-files{display:flex;align-items:center;gap:6px}.breadcrumb{min-width:0}.breadcrumb h2,.root-name,.model-text{font:12px/1.5 var(--wb-mono);overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.root-name{color:#9e9ea6;flex:none}.state-pill,.output-count,.model-btn,.icon-btn{border-radius:5px}.state-pill,.output-count,.model-btn{font:11px/1.4 var(--wb-mono);background:#232326;border:1px solid #323236;padding:3px 6px}.state-pill{white-space:nowrap;font-size:10px;gap:4px}.state-pill i{display:inline-block;background:#aaaab2;width:5px;height:5px;border-radius:50%}.state-pill i.active{background:#fff}.icon-btn{display:grid;place-items:center;width:28px;height:28px;flex:none}.header-actions{flex:none}.model-text{max-width:150px;font-size:11px}.material-symbols-outlined{font-size:17px}.controls button:hover,.reply-actions button:hover,.suggestions button:hover,.reply-files button:hover{background:#303034}.workbench button:focus-visible{outline:2px solid #eee;outline-offset:2px}.workbench button:disabled{opacity:.4;cursor:not-allowed}.board-dock{position:relative;z-index:3;flex:none;min-width:0;padding:8px 16px 0}.board-dock>*{max-width:1120px;margin:0 auto}
.feed{flex:1;min-height:0;overflow:auto;overscroll-behavior:contain;overflow-anchor:none;scrollbar-width:thin;scrollbar-color:#404044 transparent;padding:16px}.reading-column{max-width:1120px;margin:0 auto;display:grid;gap:22px;min-width:0;padding-bottom:4px}.welcome h3{font-size:20px;font-weight:600}.welcome p,.source-list p{margin:7px 0;color:#bebec5;font-size:14px;line-height:1.6}.suggestions,.reply-files{display:flex;gap:6px;flex-wrap:wrap}.suggestions{margin-top:10px}.suggestions button,.reply-files button{font-size:12px;background:#1c1c1f;border:1px solid #363639;border-radius:16px;padding:4px 9px;overflow-wrap:anywhere}.user-message,.assistant-message{display:flex;align-items:flex-start;gap:10px;min-width:0}.user-message{justify-content:flex-end;padding-left:32px}.user-copy{min-width:0;text-align:right;max-width:84%}.user-copy p{text-align:start;background:#222225;border:1px solid #333337;padding:10px 14px;border-radius:14px 3px 14px 14px;white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px;line-height:1.6}.user-copy small,.reply-actions small,.source-list small{font:10.5px/1.5 var(--wb-mono);color:#a5a5ac}.avatar{flex:none;display:grid;place-items:center;border:1px solid #38383c;border-radius:50%;width:30px;height:30px;background:#28282c;overflow:hidden}.avatar img{width:100%;height:100%;object-fit:cover}.reply{display:grid;gap:9px;flex:1;min-width:0}.answer-card{max-width:768px;background:#141417;border:1px solid #29292d;padding:14px;border-radius:12px}.reply-actions,.reply-files{margin-top:10px}.reply-actions{flex-wrap:wrap;border-top:1px solid #2c2c30;padding-top:5px;gap:4px}.reply-actions button,.reply-files button{display:inline-flex;align-items:center;gap:3px}.reply-actions button{font-size:11px;padding:4px;border-radius:4px;color:#c4c4cd}.reply-actions small{margin-left:auto}.reply-actions .material-symbols-outlined,.reply-files .material-symbols-outlined{font-size:14px}.diagnostic{font-size:13px;padding:6px 0;overflow-wrap:anywhere}.source-list section{display:grid;gap:4px;padding:10px 0;border-bottom:1px solid #444}.source-list p{white-space:pre-wrap}.source-list a{overflow-wrap:anywhere;text-decoration:underline}.params{display:grid;gap:12px}.params label{display:grid;gap:4px}.params input{height:36px;padding:0 14px;border-radius:var(--field-radius);background:var(--field-bg);box-shadow:0 0 0 1px var(--field-ring) inset;color:var(--field-text);caret-color:#fff;font-size:13.5px;transition:box-shadow var(--field-ease)}.params input:hover{box-shadow:0 0 0 1px var(--field-ring-hover) inset}.params input:focus{box-shadow:0 0 0 1px var(--field-ring-focus) inset}.params .action-primary{height:36px;padding:0 16px;border-radius:999px}.action-primary{background:#eee;color:#18181b;padding:7px 11px;border-radius:5px}.reading-column>*{min-width:0}@media(max-width:1120px){.root-name{display:none}.model-text{max-width:105px}}@media(max-width:850px){.state-pill{display:none}.model-text{max-width:80px}.controls{padding-inline:7px}.header-actions{gap:3px}}@media(max-width:650px){.model-text,.root-name{display:none}.board-dock{padding:6px 10px 0}.feed{padding:10px}.reading-column{gap:16px}.user-message{padding-left:16px}.assistant-message{gap:5px}.assistant-message>.avatar{display:none}.answer-card{padding:10px}.breadcrumb h2{font-size:11px}}
@media(pointer:coarse){.icon-btn,.reply-actions button,.model-btn,.output-count{min-height:44px}.icon-btn{min-width:38px}.user-copy p{font-size:16px}}
.diagnostic small{display:block;margin-top:2px;font-family:var(--wb-mono);font-size:11px;opacity:.6}
</style>
