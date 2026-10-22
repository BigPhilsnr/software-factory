#!/usr/bin/env node
/* Exercise the operator recovery control without a server or paid model calls. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../factory/src/main/resources/static/factory/app.js'), 'utf8');
const id = 'f8118c68-f8d6-460b-a47b-59a153ed7c92';
const elements = new Map();
function element(name) {
  if (!elements.has(name)) elements.set(name, {hidden:false, disabled:false, value:'', checked:false, dataset:{},
    textContent:'', innerHTML:'', contains:()=>false, addEventListener(){},
    querySelector:selector=>element(name+selector), querySelectorAll:()=>[]});
  return elements.get(name);
}
const detail = {state:{id, status:'SAFE_STOPPED', scenario:'greenfield', mode:'live', modelCalls:66, tasks:{apply:'FAILED'}},
  busy:false, error:'', requirement:'Build a shortener', auditValid:true, events:[{type:'POLICY_SAFE_STOP',detail:'apply:invalid test tag'}],
  tasks:[{id:'apply',kind:'PATCH',stage:'IMPLEMENTATION',dependsOn:[]}], artifacts:[], draftRecoveryTask:'apply'};
const sent = [];
const context = {URLSearchParams, location:new URL('http://localhost:8001/factory/?run='+id),
  history:{replaceState(){}}, window:{getSelection:()=>null},
  document:{getElementById:element, hidden:false, addEventListener(){}},
  setTimeout:()=>0, clearTimeout(){}, queueMicrotask,
  confirm(){throw new Error('Recovery must not enter the paid-resume confirmation path');},
  async fetch(url, options) {
    if (options?.method === 'POST') {
      sent.push({url,options});
      detail.state.status='PAUSED'; detail.state.tasks.apply='PENDING'; detail.draftRecoveryTask=null;
      return {ok:true,json:async()=>({accepted:true})};
    }
    const data = url.endsWith('/config') ? {token:'local-token',liveReady:true,validator:{ready:true}}
      : url.endsWith('/runs') ? [detail.state] : detail;
    return {ok:true,json:async()=>data};
  }
};
vm.createContext(context);
vm.runInContext(source,context);
(async()=>{
  await new Promise(setImmediate);
  assert.equal(element('recover-draft').hidden,false);
  assert.equal(element('advance').disabled,true);
  assert.match(element('action-hint').textContent,/Recover saved draft/);
  await element('recover-draft').onclick();
  assert.equal(sent.length,1);
  assert.deepEqual(JSON.parse(sent[0].options.body),{action:'recover-draft'});
  assert.equal(sent[0].options.headers['X-Factory-Token'],'local-token');
  assert.equal(element('recover-draft').hidden,true);
  assert.equal(element('advance').disabled,false);
  detail.state.status='SAFE_STOPPED'; detail.events=[{type:'POLICY_SAFE_STOP',detail:'Audit chain integrity failed'}];
  context.testDetail=detail;
  vm.runInContext('render(testDetail)',context);
  assert.equal(element('recover-draft').hidden,true);
  assert.equal(element('advance').disabled,true);
  console.log('Operator recovery controls passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
