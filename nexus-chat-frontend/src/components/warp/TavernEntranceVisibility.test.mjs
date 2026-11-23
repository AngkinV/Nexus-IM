import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import postcss from 'postcss'
import { compileStyle, parse } from '@vue/compiler-sfc'

// The stage stays mounted for the whole session, and its canvases can hold a finished
// picture while nothing is playing (the scene pre-renders in the background). An idle
// stage therefore has to be invisible by its own styles. These tests read that from the
// CSS the component really ships: its style blocks compiled by the SFC compiler
// (scoped), then parsed back with postcss.
const filename = fileURLToPath(new URL('./TavernEntrance.vue', import.meta.url))
const sfc = parse(readFileSync(filename, 'utf8'), { filename })
const SCOPE = 'data-v-stage'
const compiled = sfc.descriptor.styles.map((block) =>
  compileStyle({ source: block.content, filename, id: SCOPE, scoped: block.scoped })
)
assert.deepEqual([...sfc.errors, ...compiled.flatMap((result) => result.errors)], [])

// One record per declaration and selector. `subject` is the compound after the last
// combinator, i.e. the element the rule styles; attribute selectors (the scope id among
// them) and pseudo arguments are dropped from it first, so nothing inside them reads as
// a combinator or as a class of that element.
const declarations = []
postcss.parse(compiled.map((result) => result.code).join('\n')).walkRules((rule) => {
  for (const scoped of rule.selectors) {
    rule.each((node) => {
      if (node.type !== 'decl') return
      declarations.push({
        selector: scoped.replaceAll(`[${SCOPE}]`, ''),
        subject: scoped.replace(/\[[^\]]*\]|\([^)]*\)/g, '').split(/[\s>+~]+/).at(-1),
        nested: rule.parent.type !== 'root',
        prop: node.prop,
        value: node.value,
        important: Boolean(node.important)
      })
    })
  }
})

const STAGE = '.tavern-entrance'
const OPEN = '.tavern-entrance.is-open'
const onStage = declarations.filter((decl) => /\.tavern-entrance(?![\w-])/.test(decl.subject))

// The root's style in one state, from the plain top-level selectors that state matches,
// given in rising specificity: normal declarations first, then !important ones, and the
// later entry wins.
const cascade = (...selectors) =>
  Object.fromEntries(
    [false, true].flatMap((important) =>
      selectors.flatMap((selector) =>
        onStage
          .filter((decl) => !decl.nested && decl.selector === selector && decl.important === important)
          .map((decl) => [decl.prop, decl.value])
      )
    )
  )
const idle = cascade(STAGE)
const open = cascade(STAGE, OPEN)
const showing = (style) => ({ visibility: style.visibility, opacity: style.opacity, pointerEvents: style['pointer-events'] })

test('an idle stage is hidden, fully transparent and takes no clicks', () => {
  // Both visibility and opacity are needed. The scene switches the paper canvas on with
  // an inline `visibility: visible`, and a visible child shows through a hidden parent;
  // opacity on the root covers the whole subtree whatever the canvases carry inline.
  assert.deepEqual(showing(idle), { visibility: 'hidden', opacity: '0', pointerEvents: 'none' })
})

test('an open stage is visible, opaque and interactive', () => {
  assert.deepEqual(showing(open), { visibility: 'visible', opacity: '1', pointerEvents: 'auto' })
})

test('the stage hides the moment it goes idle', () => {
  // A transition is read from the style being switched to, so one on the idle root would
  // keep the layer on screen after the entrance is over, and an animation there would
  // override the hidden values outright. Fades belong to the canvases, which the scene
  // drives through inline opacity.
  assert.deepEqual(
    Object.keys(idle).filter((prop) => /^(?:transition|animation)/.test(prop) && idle[prop] !== 'none'),
    []
  )
})

test('no other rule decides how the stage is shown', () => {
  // `cascade` reads the two plain selectors only. A rule for the root in any other form
  // (an attribute, a pseudo-class, a media query) may style it, but not these properties.
  assert.deepEqual(
    onStage
      .filter((decl) => decl.nested || ![STAGE, OPEN].includes(decl.selector))
      .filter((decl) => /^(?:visibility|opacity|pointer-events|transition|animation)/.test(decl.prop))
      .map((decl) => `${decl.selector} { ${decl.prop}: ${decl.value} }`),
    []
  )
})

test('neither the stage nor a canvas is taken out of layout', () => {
  // The canvases stay laid out for as long as the stage is mounted: the scene prepares
  // and sizes them in the background, so hiding goes through visibility and opacity,
  // never `display: none`.
  assert.deepEqual(
    declarations
      .filter((decl) => /\.(?:tavern-entrance|te-canvas|te-paper)(?![\w-])/.test(decl.subject))
      .filter((decl) => decl.prop === 'display' && decl.value === 'none')
      .map((decl) => decl.selector),
    []
  )
})

test('the template toggles is-open on the stage and keeps it and its canvases mounted', () => {
  const ELEMENT = 1, ATTRIBUTE = 6, DIRECTIVE = 7 // compiler-core NodeTypes
  const elements = []
  const collect = (node) => {
    if (node.type === ELEMENT) elements.push(node)
    node.children?.forEach(collect)
  }
  collect(sfc.descriptor.template.ast)
  const root = elements.find((el) =>
    el.props.some((p) => p.type === ATTRIBUTE && p.name === 'class' && p.value.content.split(/\s+/).includes('tavern-entrance'))
  )
  assert.match(
    root.props.find((p) => p.type === DIRECTIVE && p.name === 'bind' && p.arg?.content === 'class').exp.content,
    /is-open/
  )
  // `v-show` is `display: none` by another door, and `v-if` would drop the canvases the
  // scene has already prepared.
  assert.deepEqual(
    [root, ...elements.filter((el) => el.tag === 'canvas')].flatMap((el) =>
      el.props
        .filter((p) => p.type === DIRECTIVE && ['if', 'else-if', 'else', 'show'].includes(p.name))
        .map((p) => `<${el.tag} v-${p.name}>`)
    ),
    []
  )
})
