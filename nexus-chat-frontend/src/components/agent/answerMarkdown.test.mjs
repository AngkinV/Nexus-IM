import { test } from 'node:test'
import assert from 'node:assert/strict'
import { renderAnswerMarkdown } from './answerMarkdown.js'

test('renders lists and code and preserves literal code content for copying', () => {
  const html = renderAnswerMarkdown('## Result\n\n- one\n- two\n\n```js\nconsole.log("<tag>")\n```', [], 'Copy code')
  assert.match(html, /<h2>Result<\/h2>/)
  assert.match(html, /<li>two<\/li>/)
  assert.match(html, /data-copy-code/)
  assert.match(html, /<code[^>]*>console.log\(&quot;&lt;tag&gt;&quot;\)\n<\/code>/)
})

test('recognizes only exposed citations in prose, never inside code, existing links, or a missing ID', () => {
  const html = renderAnswerMarkdown('[A1] [K2] `[A1]` [link [A1]](https://example.org)\n\n```txt\n[A1]\n```', [{ id: 'A1', title: 'brief' }], 'Copy')
  assert.equal(html.split('data-source-id="A1"').length - 1, 1)
  assert.ok(html.includes('[K2]'))
  assert.ok(html.includes('<code>[A1]</code>'))
  assert.ok(html.includes('link [A1]'))
})

test('an escaped citation is shown literally, never as a clickable source', () => {
  const html = renderAnswerMarkdown('\\[A1\\] is literal, [A1] is a citation', [{ id: 'A1', title: 'brief' }], 'Copy')
  assert.equal(html.split('data-source-id="A1"').length - 1, 1)
  assert.match(html, /\[A1\] is literal/)
})

test('model HTML is inert, generated remote images do not track readers, and links cannot run code', () => {
  const html = renderAnswerMarkdown('<img src=x onerror=alert(1)>\n\n[unsafe](javascript:alert) ![pixel](https://example.org/tracking.png)\n\n[site](https://example.org)', [], 'Copy')
  assert.doesNotMatch(html, /<img\b|href="javascript:|<script\b/)
  assert.match(html, /&lt;img/)
  assert.match(html, /rel="noopener noreferrer/)
})

test('an escaped opening bracket alone also stays literal text', () => {
  const html = renderAnswerMarkdown('\\[A1] is literal, [A1] is a citation', [{ id: 'A1', title: 'brief' }], 'Copy')
  assert.equal(html.split('data-source-id="A1"').length - 1, 1)
  assert.match(html, /\[A1\] is literal/)
})
