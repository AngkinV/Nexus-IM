<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import api from '@/services/api'
const props=defineProps({sessionId:{type:String,default:''}})
const user=useUserStore()
const {t}=useI18n({useScope:'local',messages:{
  zh:{title:'会话记忆',help:'候选只来自真实用户输入。确认后作为偏好使用；不同读取范围与会话代数隔离，不混用旧授权内容。',empty:'暂无长期记忆候选。短期上下文与滚动摘要仍由数据库恢复。',source:'来源执行',saved:'保存时间',pending:'未确认候选',confirmed:'已确认',confirm:'确认记忆',forget:'遗忘',reload:'刷新',limit:'这里只显示最近 256 条有效记录。',forgetConfirm:'遗忘这条记忆？会清除衍生摘要并撤销使用旧内容的运行，原会话历史仍保留。',cancel:'取消',failed:'读取或保存失败，请重试。'},
  en:{title:'Session memory',help:'Candidates come only from real user input. Confirmed preferences are isolated by reading scope and session generation.',empty:'No long-term candidates. Recent context and rolling summaries still recover from the database.',source:'Source run',saved:'Saved at',pending:'Unconfirmed candidate',confirmed:'Confirmed',confirm:'Confirm memory',forget:'Forget',reload:'Refresh',limit:'Showing the latest 256 active records at most.',forgetConfirm:'Forget this memory? Derived summaries are cleared and runs using the old content are revoked. Original history stays available.',cancel:'Cancel',failed:'Could not load or save memory. Retry.'}
}})
const rows=ref([]),busy=ref(false),error=ref('');let generation=0
const capture=()=>({generation,owner:user.currentUser?.id,token:localStorage.getItem('token'),sid:props.sessionId})
const current=s=>s.generation===generation&&s.sid===props.sessionId&&s.owner===user.currentUser?.id&&s.token===localStorage.getItem('token')
const config=s=>({_settingsOwner:s.owner,_settingsSession:s.token})
async function load(){
  const s=capture();if(!s.owner||!s.sid)return
  busy.value=true;error.value=''
  try{const r=await api.get(`/agent/sessions/${encodeURIComponent(s.sid)}/memories`,config(s));if(current(s))rows.value=Array.isArray(r.data)?r.data:[]}
  catch(e){if(current(s))error.value=t('failed')}
  finally{if(current(s))busy.value=false}
}
watch(()=>[props.sessionId,user.currentUser?.id],()=>{generation++;rows.value=[];busy.value=false;error.value='';load()},{immediate:true})
onBeforeUnmount(()=>generation++)
async function confirm(row){
  if(busy.value)return
  const s=capture();busy.value=true;error.value=''
  try{const r=await api.post(`/agent/sessions/${encodeURIComponent(s.sid)}/memories/${row.id}/confirm`,{},config(s));if(current(s))rows.value=rows.value.map(m=>m.id===row.id?r.data:m)}
  catch(e){if(current(s))error.value=t('failed')}
  finally{if(current(s))busy.value=false}
}
async function forget(row){
  if(busy.value)return
  const s=capture()
  try{await ElMessageBox.confirm(t('forgetConfirm'),t('title'),{confirmButtonText:t('forget'),cancelButtonText:t('cancel'),customClass:'agent-memory-confirm'})}catch{return}
  if(!current(s))return
  busy.value=true;error.value=''
  try{await api.delete(`/agent/memory/${row.id}`,config(s));if(current(s))rows.value=rows.value.filter(m=>m.id!==row.id)}
  catch(e){if(current(s))error.value=t('failed')}
  finally{if(current(s))busy.value=false}
}
</script>
<template>
  <section class="memory-panel" :aria-busy="busy">
    <header><p>{{t('help')}}</p><el-button :disabled="busy" @click="load">{{t('reload')}}</el-button></header>
    <p v-if="error" role="alert">{{error}}</p><p v-else-if="!rows.length" class="muted">{{t('empty')}}</p>
    <article v-for="row in rows" :key="row.id"><p class="memory-copy">{{row.content}}</p><small>{{row.confirmed?t('confirmed'):t('pending')}}<span v-if="row.sourceRunId"> · {{t('source')}} {{row.sourceRunId}}</span><span v-if="row.createdAt"> · {{t('saved')}} {{row.createdAt}}</span></small><div class="actions"><el-button v-if="!row.confirmed" :disabled="busy" @click="confirm(row)">{{t('confirm')}}</el-button><el-button :disabled="busy" @click="forget(row)">{{t('forget')}}</el-button></div></article>
    <small v-if="rows.length>=256">{{t('limit')}}</small>
  </section>
</template>
<style scoped>
.memory-panel{min-width:0}header,.actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}header p{flex:1;min-width:180px;font-size:13px;line-height:1.6}small,.muted{font-size:12px;color:var(--el-text-color-secondary);overflow-wrap:anywhere;line-height:1.6}.memory-copy{white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px;line-height:1.6}article{border-top:1px solid var(--el-border-color);padding:12px 0}.actions{margin-top:8px}.actions :deep(.el-button+.el-button){margin-left:0}
</style>
<style>
.agent-memory-confirm,.el-overlay:has(.agent-memory-confirm),.el-overlay-message-box:has(.agent-memory-confirm){z-index:23000 !important}
</style>
