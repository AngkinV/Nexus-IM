<template>
  <div class="answer-markdown" @click="onClick" v-html="html"></div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { renderAnswerMarkdown } from './answerMarkdown.js'

const props = defineProps({ content: { type: String, default: '' }, sources: { type: Array, default: () => [] } })
const emit = defineEmits(['source'])
const { t } = useI18n()
const html = computed(() => renderAnswerMarkdown(props.content, props.sources, t('agent.workbench.copyCode')))
async function onClick(event) {
  const button = event.target.closest('button')
  if (!button) return
  if (button.dataset.sourceId) {
    const source = props.sources.find(item => item.id === button.dataset.sourceId)
    if (source) emit('source', source)
  } else if (button.dataset.copyCode) {
    const code = button.closest('.code-block')?.querySelector('pre code')
    if (!code) return
    try { await navigator.clipboard.writeText(code.textContent); ElMessage.success(t('agent.copied')) }
    catch { ElMessage.error(t('common.failed')) }
  }
}
</script>

<style scoped>
.answer-markdown{color:inherit;line-height:1.65;overflow-wrap:anywhere;min-width:0;font-size:14px}.answer-markdown :deep(> :first-child){margin-top:0}.answer-markdown :deep(> :last-child){margin-bottom:0}.answer-markdown :deep(p),.answer-markdown :deep(ul),.answer-markdown :deep(ol),.answer-markdown :deep(blockquote){margin:.65em 0}.answer-markdown :deep(h1),.answer-markdown :deep(h2),.answer-markdown :deep(h3),.answer-markdown :deep(h4){font-weight:600;line-height:1.4;letter-spacing:-.015em;margin:1em 0 .45em}.answer-markdown :deep(h1){font-size:1.35em}.answer-markdown :deep(h2){font-size:1.2em}.answer-markdown :deep(h3){font-size:1.1em}.answer-markdown :deep(ul){list-style:disc;padding-left:1.5em}.answer-markdown :deep(ol){list-style:decimal;padding-left:1.5em}.answer-markdown :deep(blockquote){border-left:2px solid #56565a;padding:0 12px;color:#b9b9c0}.answer-markdown :deep(a){text-decoration:underline;text-underline-offset:.18em}.answer-markdown :deep(code){font: .9em/1.55 var(--wb-mono,ui-monospace,monospace);background:#28282b;border-radius:4px;padding:.1em .3em}.answer-markdown :deep(.code-block){border:1px solid #343438;border-radius:8px;overflow:hidden;margin:.8em 0;background:#101012}.answer-markdown :deep(.code-head){display:flex;align-items:center;justify-content:space-between;padding:3px 9px;background:#1b1b1e;color:#babac2;font:11px/1.5 ui-monospace,monospace}.answer-markdown :deep(.code-head button){padding:5px}.answer-markdown :deep(pre){overflow-x:auto;padding:10px;white-space:pre}.answer-markdown :deep(pre code){background:none;padding:0;color:#e4e4e7}.answer-markdown :deep(.citation){color:#fafafa;font-size:.85em;font-weight:600;text-decoration:underline;text-underline-offset:.2em}.answer-markdown :deep(table){display:block;max-width:100%;overflow:auto;border-collapse:collapse;margin:.8em 0}.answer-markdown :deep(th),.answer-markdown :deep(td){border:1px solid #363639;padding:4px 7px}.answer-markdown :deep(button:focus-visible),.answer-markdown :deep(a:focus-visible){outline:2px solid #eee;outline-offset:2px}
</style>
