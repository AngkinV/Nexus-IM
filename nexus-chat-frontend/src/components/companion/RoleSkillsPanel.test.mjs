import { after, afterEach, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { build } from 'esbuild'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createRenderer, h, reactive, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'

const here=dirname(fileURLToPath(import.meta.url)), require=createRequire(import.meta.url)
let Panel, app, state, root, calls, handlers
const previous=Object.getOwnPropertyDescriptor(globalThis,'localStorage')
before(async()=>{
 const bundle=await build({entryPoints:[resolve(here,'RoleSkillsPanel.vue')],bundle:true,write:false,format:'cjs',platform:'node',packages:'external',logLevel:'silent',plugins:[{name:'role-skills-host',setup(b){
  b.onLoad({filter:/RoleSkillsPanel\.vue$/},async({path})=>({contents:compileScript(parse(await readFile(path,'utf8'),{filename:path}).descriptor,{id:'skill-fixture',inlineTemplate:true}).content,resolveDir:here}))
  b.onResolve({filter:/^(?:@\/stores\/user|@\/services\/(?:api|companionSkills)|element-plus)$/},({path})=>({path,namespace:'boundary'}))
  b.onLoad({filter:/.*/,namespace:'boundary'},({path})=>({contents:path==='element-plus'?'export const ElMessageBox={confirm:async()=>true}':path.endsWith('/user')?'export function useUserStore(){return globalThis.skillsFixture.user}':path.endsWith('/api')?'export const companionAPI={getRoles:async()=>({data:[]})}':'export const companionSkills=new Proxy({}, {get:(_,name)=>(...args)=>globalThis.skillsFixture.call(name,args)})'}))
 }}]})
 const mod={exports:{}};Function('module','exports','require',bundle.outputFiles[0].text)(mod,mod.exports,require);Panel=mod.exports.default
})
const node=(type,text='')=>({type,text,props:{},children:[]})
const detach=el=>{if(el.parent)el.parent.children=el.parent.children.filter(c=>c!==el)}
const renderer=createRenderer({createElement:t=>node(t),createText:t=>node('text',t),createComment:()=>node('comment'),setText:(el,t)=>el.text=t,setElementText:(el,t)=>{el.text=t;el.children=[]},parentNode:el=>el.parent,nextSibling:el=>el.parent?.children[el.parent.children.indexOf(el)+1],patchProp:(el,k,_old,v)=>el.props[k]=v,insert:(el,parent,anchor)=>{detach(el);el.parent=parent;parent.children.splice(anchor?parent.children.indexOf(anchor):parent.children.length,0,el)},remove:detach,insertStaticContent:(text,parent,anchor)=>{const el=node('static',text);el.parent=parent;parent.children.push(el);return[el,el]}})
const all=el=>[el,...el.children.flatMap(all)]
const text=el=>[el.text,...el.children.map(text)].join('')
const tick=async()=>{await nextTick();await new Promise(r=>setImmediate(r));await nextTick()}
const document='---\nname: Listening\ndescription: Listen well\nnexus:\n  type: personality\n---\nListen before replying.\n'
const view=status=>({id:6,name:'Listening',description:'Listen well',latestRevision:1,versions:[{revision:1,status,hash:'h1',name:'Listening',type:'personality',packageVersion:'1'}]})
function control(label){return all(root).find(el=>el.type==='button'&&text(el)===label)}
function field(label){return all(root).find(el=>el.type==='input'&&el.props['aria-label']===label)}
async function mount(overrides={}){
 calls=[];handlers={list:async()=>[],bindings:async()=>[],create:async()=>view('draft'),publish:async()=>view('published'),bind:async()=>[{id:1,skillId:6,revision:1,name:'Listening',enabled:true,version:0}],trial:async()=>({ok:true,reply:'real contract fixture',contextReceipt:{trial:true,skills:[]}}),...overrides}
 state=reactive({user:{currentUser:{id:11}},props:{roleId:7}})
 const data=new Map([['token','fixture-session-a'],['user','{"id":11}']]);Object.defineProperty(globalThis,'localStorage',{configurable:true,value:{getItem:k=>data.get(k)||null,setItem:(k,v)=>data.set(k,v)}})
 globalThis.skillsFixture={user:state.user,call:(name,args)=>{calls.push({name,args});assert.equal(args.at(-1).owner,state.user.currentUser.id,'A request must use the current owner');return handlers[name]?.(...args)}}
 root=node('root');app=renderer.createApp({render:()=>h(Panel,state.props)});app.use(createI18n({legacy:false,locale:'en',missingWarn:false,fallbackWarn:false,messages:{en:{}}}))
 const basic=(tag)=>({inheritAttrs:false,props:['modelValue','label'],setup:(props,{slots,attrs})=>()=>h(tag,attrs,[props.label||'',slots.default?.()])})
 for(const[name,tag]of[['ElButton','button'],['ElForm','form'],['ElFormItem','label'],['ElInput','input'],['ElSelect','select'],['ElOption','option'],['ElCheckbox','checkbox'],['ElSwitch','switch'],['ElUpload','upload']])app.component(name,basic(tag))
 app.mount(root);await tick()
}
afterEach(()=>{app?.unmount();app=null;delete globalThis.skillsFixture})
after(()=>{if(previous)Object.defineProperty(globalThis,'localStorage',previous);else delete globalThis.localStorage})

test('real controls keep draft unpublished, publish with hash and bind exact revision',async()=>{
 await mount();field('SKILL.md or JSON text package').props['onUpdate:modelValue'](document);await tick();control('Save draft').props.onClick();await tick()
 assert.equal(control('Apply this revision').props.disabled,true)
 control('Confirm publish').props.onClick();await tick();assert.equal(control('Apply this revision').props.disabled,false)
 control('Apply this revision').props.onClick();await tick()
 const publish=calls.find(c=>c.name==='publish');assert.deepEqual(publish.args.slice(0,3),[6,1,'h1'])
 const bind=calls.find(c=>c.name==='bind');assert.equal(bind.args[0],7);assert.equal(bind.args[1].skillId,6);assert.equal(bind.args[1].revision,1)
 assert.ok(text(root).includes('Listening · r1'))
})
test('trial before publication reports isolation without binding or publishing',async()=>{
 await mount();field('SKILL.md or JSON text package').props['onUpdate:modelValue'](document);control('Save draft').props.onClick();await tick()
 field('Trial message').props['onUpdate:modelValue']('hello');await tick();control('Isolated trial').props.onClick();await tick()
 assert.ok(text(root).includes('Trial result · not stored in role memory'))
 assert.equal(calls.filter(c=>c.name==='trial').length,1);assert.equal(calls.filter(c=>c.name==='bind'||c.name==='publish').length,0)
})
test('late draft receipt cannot populate another role or reenable its controls',async()=>{
 let resolve;const pending=new Promise(r=>resolve=r);await mount({create:()=>pending})
 field('SKILL.md or JSON text package').props['onUpdate:modelValue'](document);control('Save draft').props.onClick();await tick()
 state.props.roleId=99;await tick();resolve(view('published'));await tick()
 assert.equal(control('Apply this revision'),undefined);assert.equal(control('Confirm publish'),undefined)
})
test('late binding load cannot leak old account data into the new one',async()=>{
 let resolve;const pending=new Promise(r=>resolve=r);let count=0
 await mount({bindings:()=>++count===1?pending:Promise.resolve([])})
 state.user.currentUser={id:22};localStorage.setItem('token','fixture-session-b');localStorage.setItem('user','{"id":22}');await tick()
 resolve([{id:12,skillId:7,revision:1,name:'old-account-private',enabled:true,version:0}]);await tick()
 assert.equal(text(root).includes('old-account-private'),false)
})
