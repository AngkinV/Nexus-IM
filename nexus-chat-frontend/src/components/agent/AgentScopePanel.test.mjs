import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import en from '../../locales/en.json' with { type: 'json' }
import zh from '../../locales/zh.json' with { type: 'json' }

// What can be checked without a browser: the words exist in both languages, the
// button is where it was asked for, and the panel keeps the sizes and greys it was
// designed with. How the panel behaves is driven in AgentChatView.test.mjs.
const source = name => readFileSync(new URL(name, import.meta.url), 'utf8')
const panel = source('./AgentScopePanel.vue'), composer = source('./AgentComposer.vue')
const leaves = (node, prefix = '') => Object.entries(node).flatMap(([key, value]) => typeof value === 'string' ? [prefix + key] : leaves(value, `${prefix}${key}.`))
const style = panel.slice(panel.indexOf('<style scoped>'))

test('every scope message exists in both languages, is used, and every key the code asks for exists', () => {
  assert.deepEqual(leaves(zh.agentScope).sort(), leaves(en.agentScope).sort())
  for (const [name, messages] of [['en', en.agentScope], ['zh', zh.agentScope]]) {
    for (const key of leaves(messages)) {
      const text = key.split('.').reduce((node, part) => node[part], messages)
      assert.ok(text.trim(), `${name} ${key} is not empty`)
      // "|" splits plurals and "@" links messages in vue-i18n; neither belongs in these sentences.
      assert.doesNotMatch(text, /[|@]/, `${name} ${key}`)
    }
  }
  const code = [panel, composer, source('./AgentChatView.vue'), source('../../stores/agent.js')].join('\n')
  // Keys built from a value: each family is listed with the values the code can produce.
  const families = { compact: ['none', 'pending', 'confirmed', 'running', 'loading'], state: ['none', 'pending', 'confirmed', 'running', 'loading'], status: ['none', 'pending', 'confirmed', 'running', 'loading'],
    preset: ['day', 'week', 'all', 'custom'], error: ['zone', 'invalid', 'nonexistent', 'ambiguous', 'order', 'empty'] }
  for (const [family, members] of Object.entries(families)) {
    assert.ok(code.includes(`agentScope.${family}.\${`), `${family} keys are built in code`)
    assert.deepEqual(Object.keys(en.agentScope[family]).sort(), [...members].sort(), family)
  }
  const time = source('./scopeTime.mjs')
  for (const code of families.error) assert.ok(time.includes(`ScopeTimeError('${code}'`), `scopeTime raises ${code}`)
  assert.deepEqual([...time.matchAll(/ScopeTimeError\('(\w+)'/g)].map(m => m[1]).filter(code => !families.error.includes(code)), [], 'no error without a message')
  assert.ok(code.includes('agentScope.${group.type}') && code.includes('agentScope.${item.type}') && code.includes("'agentScope.' + target.type"))
  const asked = new Set([...code.matchAll(/agentScope\.([A-Za-z]+(?:\.[A-Za-z]+)*)/g)].map(m => m[1]))
  const known = new Set(leaves(en.agentScope))
  const dynamic = key => Object.keys(families).some(family => key === family || key.startsWith(`${family}.`)) || ['contact', 'group'].includes(key)
  assert.deepEqual([...asked].filter(key => !known.has(key) && !dynamic(key)), [], 'no key is asked for that the locales lack')
  // Compatibility keys stay present even where this new layout no longer renders
  // them. The two permission reminders are still visible in their relevant sections.
  const kept = ['candidate', 'confirmed', 'nextSelection', 'override', 'receiptObjects', 'unconfirmed']
  for (const key of kept) assert.ok(en.agentScope[key] && zh.agentScope[key], `${key} remains available in both languages`)
  assert.deepEqual([...known].filter(key => !asked.has(key) && !dynamic(key)).sort(), ['confirmed', 'nextSelection', 'receiptObjects', 'unconfirmed'], 'only the four legacy presentation messages are unused')
  assert.match(panel, /t\('agentScope\.candidate'\)/)
  assert.match(panel, /t\('agentScope\.override'\)/)
  assert.equal(en.agentScope.nextSelection, 'Next request: local selection and confirmation (not a grant)')
  assert.equal(zh.agentScope.unconfirmed, '尚未确认')
})

test('the button is one toolbar entry directly before the knowledge base selector; the old block is gone', () => {
  assert.match(composer, /<AgentScopePanel ref="scopePanel" :submitting="submitting" \/>\s*<KnowledgeBaseSelector /)
  assert.match(composer, /<div class="scope-context-tools">\s*<AgentScopePanel[^>]*\/?>\s*<KnowledgeBaseSelector/)
  assert.match(composer, /\.scope-context-tools\{display:inline-flex;align-items:center;gap:2px;min-width:0;max-width:100%\}/)
  assert.equal((composer.match(/<AgentScopePanel /g) || []).length, 1)
  assert.ok(composer.indexOf('<div class="control-line"><div class="tools">') < composer.indexOf('<AgentScopePanel '))
  assert.doesNotMatch(composer, /el-collapse|scope-controls|scope-groups/)
  // Sending still waits for the per-request confirmation, and says where to give it.
  assert.match(composer, /const scopePending = computed\(\(\) => workspace\.scopeSelection\.targets\.length > 0 && !workspace\.scopeConfirmed\)/)
  assert.match(composer, /class="send"[^>]*:disabled="!draft\.trim\(\) \|\| busy \|\| uploading \|\| scopePending"/)
  assert.match(composer, /composing\.value \|\| scopePending\.value\) return/)
  assert.match(composer, /agentScope\.confirmFirst[\s\S]{0,120}@click="scopePanel\.open\(\)"/)
})

test('the panel keeps the store semantics: confirm, save and load are the store calls, nothing is confirmed or saved on the side', () => {
  assert.equal((panel.match(/workspace\.confirmScope\(\)/g) || []).length, 1)
  assert.match(panel, /class="scope-primary"[^>]*@click="workspace\.confirmScope\(\)"/)
  assert.equal((panel.match(/workspace\.saveScope\(/g) || []).length, 1)
  assert.match(panel, /await workspace\.saveScope\(agent\.sessionId\)/)
  assert.match(panel, /const sid = await agent\.ensureSession\(\)/)
  assert.match(panel, /if \(mine !== epoch \|\| sid !== agent\.sessionId\) return\s+await workspace\.loadScope\(sid\)/)
  // Closing only closes.
  assert.match(panel, /function close\(refocus = false\) \{ open\.value = false; if \(refocus\) trigger\.value\.focus\(\) \}/)
  // A load, a save and opening a group each report only into the epoch they started in.
  assert.equal((panel.match(/const mine = epoch\n/g) || []).length, 3)
  assert.equal((panel.match(/mine === epoch/g) || []).length, 6)
  assert.match(panel, /if \(mine !== epoch\) return/)
  // One observer for the life of the panel, released with the other listeners on close and on unmount.
  assert.equal((panel.match(/new ResizeObserver\(measure\)/g) || []).length, 1)
  assert.match(panel, /function release\(\) \{[^}]*sizes\.disconnect\(\) \}/)
  assert.match(panel, /if \(!shown\) \{ release\(\); return \}/); assert.match(panel, /onBeforeUnmount\(release\)/)
  assert.doesNotMatch(panel, /takeScope|invalidateScope|bindingVersion\s*=[^=]|scopeConfirmed\s*=\s*true/)
  // Targets are sent as type and ID only.
  assert.match(panel, /\{ type: item\.type, id: item\.id \}/)
  // No request of its own: everything goes through the existing stores.
  assert.doesNotMatch(panel, /axios|fetch\(|agentAPI|defineStore|useRouter/)
})

test('a dialog anchored above the button on a desktop, a bottom sheet at 480px and under, with 44px targets on touch', () => {
  assert.match(panel, /<Teleport to="body" :disabled="!narrow">/, 'a narrow sheet escapes ancestor transforms and clipping')
  assert.match(panel, /:style="place" @keydown="onKey"/, 'teleported controls still handle Escape and focus trapping')
  assert.match(panel, /!anchor\.value\.contains\(event\.target\) && !panel\.value\?\.contains\(event\.target\)/, 'an inside press on a teleported panel does not count as outside')
  assert.match(panel, /aria-haspopup="dialog" :aria-expanded="open" aria-controls="agent-scope-panel"/)
  assert.match(panel, /id="agent-scope-panel"[^>]*role="dialog"[^>]*aria-labelledby="agent-scope-title" aria-describedby="agent-scope-about"/)
  assert.match(panel, /Math\.min\(420, bounds\.width - 16\)/)
  // The summary is what is limited to 220px and cut with an ellipsis. The button is not
  // capped as a whole (that would cut the state, the one thing it is there to show); it
  // only shrinks when the toolbar itself is narrower, and the name never gives way first.
  assert.match(style, /\.scope-state\{min-width:0;max-width:220px;overflow:hidden;text-overflow:ellipsis/)
  assert.match(style, /\.scope-trigger\{[^}]*min-width:0;[^}]*white-space:nowrap/)
  assert.doesNotMatch(style, /\.scope-trigger\{[^}]*max-width/)
  assert.match(style, /\.scope-anchor\{[^}]*min-width:0/)
  assert.match(style, /\.scope-name\{flex:none\}/)
  assert.match(style, /\.scope-panel\{position:absolute;[^}]*left:var\(--scope-shift\);bottom:calc\(100% \+ 8px\);[^}]*width:var\(--scope-width\);max-height:min\(720px,var\(--scope-max\)\);[^}]*border-radius:16px/)
  // The title and the actions stay put; only the middle scrolls.
  assert.match(style, /\.scope-head\{[^}]*flex:none;padding:16px 16px 12px/)
  assert.match(style, /\.scope-body\{[^}]*gap:12px;flex:1;min-height:0;[^}]*overflow-y:auto/)
  assert.match(style, /\.scope-foot\{[^}]*flex:none/)
  assert.match(style, /\.scope-presets\{display:flex;flex-wrap:wrap;gap:6px\}/, 'desktop presets share intrinsic width instead of splitting a label across lines')
  assert.match(style, /\.scope-chip\{[^}]*white-space:nowrap/)
  assert.match(style, /@media\(max-height:600px\)\{\s*\.scope-panel\{overflow-y:auto;overscroll-behavior:contain\}\s*\.scope-body\{flex:none;overflow-y:visible\}/, 'short windows can reach the entire form rather than clipping its fixed header and footer')
  const narrow = style.slice(style.indexOf('@media(max-width:480px){'))
  assert.match(narrow, /\.scope-panel\{position:fixed;left:8px;right:8px;bottom:max\(8px,env\(safe-area-inset-bottom\)\);width:auto;max-height:85dvh\}/)
  assert.match(narrow, /\.scope-name\.wide\{display:none\}\.scope-name\.narrow\{display:inline\}/, 'a shorter name, the state stays')
  assert.match(narrow, /\.scope-field\{grid-template-columns:minmax\(0,1fr\)/, 'a date-time field takes the full width of a narrow sheet')
  assert.match(style, /@media\(max-width:480px\),\(pointer:coarse\)\{\s*\.scope-trigger,\.scope-btn,\.scope-primary,\.scope-chip,\.scope-link,\.scope-item label,\.scope-part summary\{min-height:44px\}/)
  assert.match(style, /prefers-reduced-motion:reduce/)
})

test('the panel is drawn in the workbench greys only', () => {
  const colours = [...style.matchAll(/#([0-9a-f]{3}|[0-9a-f]{6})\b/gi)].map(m => m[1])
  assert.ok(colours.length > 20)
  for (const hex of colours) {
    const [r, g, b] = (hex.length === 3 ? [...hex].map(c => c + c) : hex.match(/../g)).map(pair => parseInt(pair, 16))
    assert.ok(Math.max(r, g, b) - Math.min(r, g, b) <= 8, `#${hex} is a grey`)
  }
  assert.doesNotMatch(style, /amber|emerald|backdrop-filter|@keyframes|animation/)
  // Fields take the shared capsule classes instead of restyling inputs here.
  assert.equal((panel.match(/class="nx-field"/g) || []).length, 3)
  assert.doesNotMatch(style, /\.nx-field/)
})
