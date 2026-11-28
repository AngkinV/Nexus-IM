<script setup>
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AgentProviderSettings from '../agent/AgentProviderSettings.vue'
import { contextSkillNames } from './skillPackage'
const props=defineProps({receipt:{type:Object,default:null},showSkills:{type:Boolean,default:true}})
const modelOpen=ref(false)
const {t}=useI18n({useScope:'local',messages:{
  zh:{skills:'实际加载：{names}',none:'本轮没有加载技能',unused:'本轮未请求模型',unconfigured:'未配置向量模型，已使用非向量记忆',failed:'向量调用失败，已降级',rebuilding:'向量重建中，未匹配的记忆采用关键词召回',ready:'向量记忆可用',configured:'向量模型已配置，本轮未调用',keyword:'关键词记忆',not_requested:'本条未检索记忆',summary:'已使用滚动摘要',count:'本轮装配 {used} / {total} 条记忆',settings:'配置向量模型'},
  en:{skills:'Actually loaded: {names}',none:'No skills loaded for this turn',unused:'No model request in this turn',unconfigured:'No embedding model; non-vector memory used',failed:'Embedding call failed; using fallback',rebuilding:'Rebuilding embeddings; unmatched memories use keyword recall',ready:'Vector memory available',configured:'Embedding configured; not called this turn',keyword:'Keyword recall',not_requested:'No memory lookup for this message',summary:'Rolling summary included',count:'Context includes {used} / {total} memories',settings:'Configure embedding model'}
}})
const names=computed(()=>contextSkillNames(props.receipt))
const state=computed(()=>props.receipt?.memoryStatus)
const title=computed(()=>props.receipt?.memoryError || '')
</script>
<template>
  <div v-if="receipt" class="context-note" role="status" :title="title">
    <span v-if="receipt.modelRequestAttempted === false">{{t('unused')}}</span>
    <template v-else><span v-if="showSkills">{{names.length?t('skills',{names:names.join('、')}):t('none')}}</span><span v-if="['unconfigured','failed','rebuilding','ready','configured','keyword','not_requested'].includes(state)">{{t(state)}}</span><span v-if="receipt.summaryUsed">{{t('summary')}}</span><span v-if="typeof receipt.memoryTotal==='number'">{{t('count',{used:receipt.memoryIds?.length || 0,total:receipt.memoryTotal})}}</span></template>
    <el-button v-if="state==='unconfigured' || state==='failed'" link size="small" @click="modelOpen=true">{{t('settings')}}</el-button>
    <AgentProviderSettings v-model:visible="modelOpen" initial-purpose="embedding" />
  </div>
</template>
<style scoped>
.context-note{display:flex;align-items:center;flex-wrap:wrap;gap:5px 10px;min-width:0;font-size:12px;line-height:1.6;color:var(--el-text-color-secondary);padding:5px 0;overflow-wrap:anywhere}
.context-note :deep(.el-button){font-size:12px;min-height:32px;white-space:normal;text-align:start}
@media(max-width:640px){.context-note :deep(.el-button){min-height:44px}}
</style>
