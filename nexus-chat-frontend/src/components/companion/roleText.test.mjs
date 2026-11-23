import test from 'node:test'
import assert from 'node:assert/strict'
import { matchesQuery, previewOf, segmentsOf, spokenLine } from './roleText.js'

const zh = { you: '你：', greeting: '开场白：', empty: '暂无消息' }
const row = (role) => {
  const preview = previewOf(role, zh)
  return [preview.text, preview.title]
}

test('a line is cut into speech and narration without losing a character', () => {
  const text = '（她抬起头）你来了。\n*笑了笑* 坐吧 (waves) 今晚很安静。'
  const segments = segmentsOf(text)
  assert.deepEqual(segments, [
    { narration: true, text: '（她抬起头）' },
    { narration: false, text: '你来了。\n' },
    { narration: true, text: '*笑了笑*' },
    { narration: false, text: ' 坐吧 ' },
    { narration: true, text: '(waves)' },
    { narration: false, text: ' 今晚很安静。' }
  ])
  // The conversation view renders every segment, so together they are the message.
  assert.equal(segments.map((seg) => seg.text).join(''), text)
  assert.deepEqual(segmentsOf('just talking'), [{ narration: false, text: 'just talking' }])
  assert.deepEqual(segmentsOf(null), [])
  // An unclosed bracket or a star across a line break is not narration.
  assert.deepEqual(segmentsOf('（没合上 *a\nb*').map((seg) => seg.narration), [false])
})

test('a role line shows only what is said aloud; the tooltip keeps the whole message', () => {
  const mixed = '（她抬起头）你来了。\n*笑了笑* 坐吧 (waves) 今晚很安静。'
  assert.deepEqual(row({ lastMessage: mixed, lastMessageSender: 'role' }), ['你来了。 坐吧 今晚很安静。', mixed])
  assert.equal(spokenLine('  Hello   there  '), 'Hello there')
})

test('a line that is narration throughout is kept rather than emptied', () => {
  const silent = '（她只是点了点头，\n没有说话。）'
  assert.deepEqual(row({ lastMessage: silent, lastMessageSender: 'role' }), ['（她只是点了点头， 没有说话。）', silent])
  // Removing the narration would leave punctuation only: that is no line either.
  assert.deepEqual(row({ lastMessage: '*shrugs*……', lastMessageSender: 'role' }), ['*shrugs*……', '*shrugs*……'])
  assert.deepEqual(row({ lastMessage: '(nods).', lastMessageSender: 'role' }), ['(nods).', '(nods).'])
})

test('the user\'s brackets and asterisks are never treated as narration', () => {
  const maths = 'f(x) = (a+b)*c*d，（真的）帮我算一下'
  assert.deepEqual(row({ lastMessage: maths, lastMessageSender: 'user' }), [`你：${maths}`, `你：${maths}`])
  // One line in the row, the original line breaks in the tooltip.
  assert.deepEqual(row({ lastMessage: 'first (1)\n  second *2*', lastMessageSender: 'user' }),
    ['你：first (1) second *2*', '你：first (1)\n  second *2*'])
})

test('a line with no known sender, or a system line, is shown as written and unlabelled', () => {
  const text = '(maybe) *who* said this'
  for (const lastMessageSender of [undefined, null, '', 'system', 'somebody-new']) {
    assert.deepEqual(row({ lastMessage: text, lastMessageSender }), [text, text], String(lastMessageSender))
  }
  assert.deepEqual(row({ lastMessage: text }), [text, text])
})

test('with nothing said, the greeting stands in and is labelled as one', () => {
  const greeting = '（擦着杯子）欢迎，\n坐吧。'
  assert.deepEqual(row({ greeting }), ['开场白：欢迎， 坐吧。', `开场白：${greeting}`])
  // A blank last message is not a message.
  for (const lastMessage of [null, '', '  \n\t ']) {
    assert.deepEqual(row({ lastMessage, lastMessageSender: 'role', greeting }), ['开场白：欢迎， 坐吧。', `开场白：${greeting}`])
  }
  // Something said always wins over the greeting.
  assert.deepEqual(row({ lastMessage: 'hi', lastMessageSender: 'user', greeting }), ['你：hi', '你：hi'])
})

test('without a message or a greeting the row says so and invents nothing', () => {
  assert.deepEqual(row({}), ['暂无消息', null])
  assert.deepEqual(row({ lastMessage: '   ', greeting: '  ' }), ['暂无消息', null])
  // What she is doing and how she sounds are not messages.
  assert.deepEqual(row({ presence: '在擦杯子', tone: '温柔', lastMessageSender: 'role' }), ['暂无消息', null])
})

test('a long line is never shortened in text: the row truncates by layout, the tooltip is complete', () => {
  const long = `${'很长的一句话，'.repeat(80)}（然后她停了下来）说完了。`
  const preview = previewOf({ lastMessage: long, lastMessageSender: 'role' }, zh)
  assert.equal(preview.title, long)
  assert.equal(preview.full, long)
  assert.equal(preview.text, `${'很长的一句话，'.repeat(80)} 说完了。`)
})

test('the labels are whatever the caller localised', () => {
  const en = { you: 'You: ', greeting: 'Opening line: ', empty: 'No messages yet' }
  assert.equal(previewOf({ lastMessage: '2 * (3 + 4)', lastMessageSender: 'user' }, en).text, 'You: 2 * (3 + 4)')
  assert.equal(previewOf({ greeting: '*waves* Sit anywhere.' }, en).text, 'Opening line: Sit anywhere.')
  assert.equal(previewOf({}, en).text, 'No messages yet')
})

test('search reads the name, the original line and the line as shown', () => {
  const preview = previewOf({ lastMessage: '（擦着杯子）今晚\n想喝点什么？ *笑* 老样子吗', lastMessageSender: 'role' }, zh)
  const found = (query) => matchesQuery(query, 'Mira', preview.full, preview.line)
  assert.equal(found('mira'), true)
  assert.equal(found('  MIR '), true)
  assert.equal(found('擦着杯子'), true, 'words inside narration are searchable')
  assert.equal(found('今晚 想喝'), true, 'a line break reads as a space')
  assert.equal(found('什么？ 老样子'), true, 'the line as the row shows it')
  assert.equal(found('开场白'), false, 'a label is not content')
  assert.equal(found('不存在'), false)
  assert.equal(found(''), true)
  assert.equal(found('   '), true)
  assert.equal(matchesQuery('x', '', null, undefined), false)
})
