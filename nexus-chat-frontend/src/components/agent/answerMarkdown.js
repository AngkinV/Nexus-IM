import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const md = new MarkdownIt({ html: false, linkify: false, breaks: true })
const escape = md.utils.escapeHtml
md.validateLink = url => /^https?:\/\//i.test(url)
// A backslash before a citation is a deliberate escape, e.g. writing the literal "[A1]".
// Capture it before markdown-it folds the backslash into a plain text token, so it never
// becomes a clickable citation.
md.inline.ruler.before('escape', 'citation_escape', (state, silent) => {
  if (state.src.charCodeAt(state.pos) !== 0x5C) return false
  // Either bracket may carry the backslash; both forms mean the literal text.
  const m = /^\\\[([AKWT]\d+)\]?(?:\\\])?/.exec(state.src.slice(state.pos))
  if (!m) return false
  if (!silent) { const token = state.push('citation_escaped', 'text', 0); token.content = `[${m[1]}]` }
  state.pos += m[0].length
  return true
})
md.renderer.rules.citation_escaped = (tokens, i) => escape(tokens[i].content)
md.renderer.rules.image = (tokens, i) => escape(tokens[i].content)
md.renderer.rules.link_open = (tokens, i, options, env, renderer) => {
  env.linkDepth++
  tokens[i].attrSet('rel', 'noopener noreferrer nofollow')
  tokens[i].attrSet('target', '_blank')
  return renderer.renderToken(tokens, i, options)
}
md.renderer.rules.fence = (tokens, i, options, env) => {
  const token = tokens[i]
  const language = escape(token.info.trim().split(/\s+/)[0] || 'text')
  return `<div class="code-block"><div class="code-head"><span>${language}</span><button type="button" data-copy-code="true">${escape(env.copyLabel)}</button></div><pre><code>${escape(token.content)}</code></pre></div>\n`
}
md.renderer.rules.text = (tokens, i, options, env) => {
  const text = tokens[i].content
  if (!env.sources.size || env.linkDepth) return escape(text)
  return escape(text).replace(/\[([AKWT]\d+)\]/g, (literal, id) => env.sources.has(id)
    ? `<button class="citation" type="button" data-source-id="${id}">${literal}</button>` : literal)
}
md.renderer.rules.link_close = (tokens, i, options, env, renderer) => { env.linkDepth--; return renderer.renderToken(tokens, i, options) }

export function renderAnswerMarkdown(content, sources = [], copyLabel = 'Copy code') {
  const html = md.render(content || '', { sources: new Set(sources.map(s => s.id)), copyLabel, linkDepth: 0 })
  if (typeof window === 'undefined') return html
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ['a', 'p', 'br', 'blockquote', 'ol', 'ul', 'li', 'hr', 'strong', 'em', 's', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'pre', 'code', 'button', 'div', 'span'],
    ALLOWED_ATTR: ['class', 'type', 'href', 'rel', 'target', 'start', 'data-source-id', 'data-copy-code'], ALLOW_DATA_ATTR: false
  })
}
