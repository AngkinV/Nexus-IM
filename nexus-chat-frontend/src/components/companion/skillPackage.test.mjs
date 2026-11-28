import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { skillBundle, isSkillArtifact, contextSkillNames } from './skillPackage.js'

const md='---\nname: Listen\ndescription: Listen gently\nnexus:\n  type: personality\n---\nListen before responding.\n'
test('single SKILL.md and JSON package produce the same interoperable bundle',()=>{
  const one=skillBundle(md)
  assert.equal(one.files['SKILL.md'],md)
  assert.deepEqual(skillBundle(JSON.stringify(one)),one)
  assert.equal(isSkillArtifact({kind:'skill',content:JSON.stringify(one)}),true)
  assert.equal(isSkillArtifact({kind:'markdown',content:md}),true)
  assert.equal(isSkillArtifact({kind:'json',content:'{"unrelated":true}'}),false)
})
test('empty, malformed and oversized packages do not become skill actions',()=>{
  assert.throws(()=>skillBundle('[]'))
  assert.throws(()=>skillBundle('{"files":{}}'))
  assert.throws(()=>skillBundle('a'.repeat(262145)))
})
test('only backend loaded receipts claim actual use',()=>{
  assert.deepEqual(contextSkillNames({skills:[
    {name:'first',revision:1,status:'loaded'}, {name:'budget',revision:2,status:'budget_exceeded'},
    {name:'not chosen',revision:4,status:'not_triggered'}
  ]}),['first · r1'])
  assert.deepEqual(contextSkillNames(null),[])
})
test('role settings and workbench reuse the same editor and real source endpoint',async()=>{
  const settings=await readFile(new URL('./RoleSettingsPanel.vue',import.meta.url),'utf8')
  const drawer=await readFile(new URL('../agent/AgentArtifactDrawer.vue',import.meta.url),'utf8')
  const api=await readFile(new URL('../../services/companionSkills.js',import.meta.url),'utf8')
  assert.match(settings,/<RoleSkillsPanel :role-id="activeRole.id"/)
  assert.match(drawer,/<RoleSkillsPanel[^>]+:artifact-id=/)
  assert.match(api,/\/from-artifact/)
  assert.match(api,/_settingsOwner/)
  assert.match(api,/_settingsSession/)
})
test('drafts cannot apply and published state follows lowercase backend contract',async()=>{
  const panel=await readFile(new URL('./RoleSkillsPanel.vue',import.meta.url),'utf8')
  assert.match(panel,/status\?\.toLowerCase\(\) === 'published'/)
  assert.match(panel,/if\(!published.value \|\| !effectiveRole.value\) return/)
  assert.match(panel,/if\(current\(s\)\) bindings.value=rows/)
  assert.match(panel,/expectedVersion:existing\?\.version/)
  assert.match(panel,/customClass:'companion-skill-confirm'/)
})
