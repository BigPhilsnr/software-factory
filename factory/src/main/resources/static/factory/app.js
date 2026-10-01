'use strict';
const $ = id => document.getElementById(id);
const terminal = new Set(['COMPLETED','FAILED','SAFE_STOPPED','NOT_APPROVED']);
let token = '', selected = new URLSearchParams(location.search).get('run'), current = null, reviewHash = null, fetching = false;
let refreshTimer;
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function notice(text) { $('notice').textContent = text; $('notice').hidden = !text; }
async function api(path, body) {
  const response = await fetch('/factory/api/' + path, body === undefined ? {} : {method:'POST',headers:{'Content-Type':'application/json','X-Factory-Token':token},body:JSON.stringify(body)});
  const data = await response.json().catch(() => ({error:'Server returned an invalid response'}));
  if (!response.ok) throw new Error(data.error || 'Request failed');
  return data;
}
async function perform(fn) { notice(''); try { await fn(); } catch(e) { notice(e.message); } }
async function choose(id) {
  selected = id; current = null; reviewHash = null;
  $('run').hidden = true;
  history.replaceState(null, '', '/factory/?run=' + encodeURIComponent(id));
  $('artifact-text').hidden = true; $('feedback').value = ''; $('answer').value = '';
  await refresh();
}
async function refresh() {
  if (fetching) return;
  clearTimeout(refreshTimer);
  fetching = true;
  try {
    const runs = await api('runs');
    $('runs').innerHTML = runs.map(r => `<button class="run-item ${r.id===selected?'selected':''}" data-run="${esc(r.id)}">${esc(r.scenario)} <span>Saved: ${esc(r.status)} · ${esc(r.mode)}</span><span>${esc(r.id.slice(0,8))}</span></button>`).join('') || '<p>No runs yet.</p>';
    if (selected) {
      const requested = selected, data = await api('runs/' + encodeURIComponent(requested));
      if (requested === selected) render(data);
    }
    $('connection').textContent = 'Connected · local control plane';
  } catch(e) { notice(e.message); $('connection').textContent = 'Connection unavailable'; }
  finally {
    fetching = false;
    if (!document.hidden) refreshTimer = setTimeout(refresh, current?.busy ? 5000 : 15000);
  }
}
function artifactLabel(name) {
  return name.replace(/\.txt$/, '').replace(/-v(\d+)$/, ' · version $1').replaceAll('-', ' ');
}
function updateOptions(id, entries, placeholder, label = value => value) {
  const field = $(id), prior = field.value;
  const html = (placeholder ? `<option value="">${placeholder}</option>` : '') + entries.map(v=>`<option value="${esc(v)}">${esc(label(v))}</option>`).join('');
  if (field.innerHTML !== html) { field.innerHTML = html; if(entries.includes(prior)) field.value = prior; }
}
function render(data) {
  current = data; const s = data.state, ended = terminal.has(s.status);
  $('empty').hidden = true; $('run').hidden = false;
  $('run-title').textContent = s.scenario; $('run-mode').textContent = s.mode.toUpperCase() + ' RUN';
  $('run-status').textContent = s.status; $('run-status').className = 'badge ' + s.status.toLowerCase();
  $('run-id').textContent = s.id; $('run-requirement').textContent = data.requirement;
  $('calls').textContent = `Model calls ${s.modelCalls} / ${s.maxModelCalls}`;
  $('audit').textContent = (data.auditValid ? 'Audit verified' : 'Audit verification failed') + (data.auditCheckedAt ? ' · ' + new Date(data.auditCheckedAt).toLocaleTimeString() : '');
  $('audit').title = 'Dashboard audit results are cached for at most 30 seconds. Workflow actions always verify the audit independently.';
  $('busy').textContent = data.busy ? 'Worker active on this server · refresh every 5 seconds' : 'No active worker on this server';
  $('advance').disabled = ended || data.busy || !!s.pendingApprovalTask || !!s.pendingClarificationTask;
  const stop = ['SAFE_STOPPED','FAILED'].includes(s.status) ? data.events.find(e=>['POLICY_SAFE_STOP','TASK_FAILED','RUN_FAILED'].includes(e.type)) : null;
  $('action-hint').textContent = ended ? (stop ? `Stopped: ${stop.detail}. This run has ended; inspect its evidence before starting a new request.` : 'Run ended. Evidence remains available.') : s.pendingApprovalTask ? 'Review the exact proposal below.' : s.pendingClarificationTask ? 'Answer the question below.' : data.busy ? 'Generation and validation continue in the background.' : 'Live runs make paid model calls. Fixture runs use recorded artifacts.';
  if (!ended && !data.busy && data.retryReason) $('action-hint').textContent = `Retry available: ${data.retryReason}\nResolve the failure, then select Start / resume. A repeated failure may exhaust the retry limit.`;
  else if (s.status === 'RUNNING' && !data.busy) $('action-hint').textContent = 'Saved RUNNING status, but no worker is active on this server. Start / resume recovers unfinished work when its database lease is available.';
  if(data.error) notice(data.error);
  const m=data.metrics;
  $('reliability').textContent = m ? `Elapsed ${(m.elapsedMillis/1000).toFixed(1)}s · Retries ${m.retryExecutions} (${m.retryOffers} offered) · Rollbacks ${m.rollbacks} · Replans ${m.replans} · Parallel joins ${m.parallelJoins} · Mean recovery ${m.meanRecoveryMillis===null?'no samples':(m.meanRecoveryMillis/1000).toFixed(1)+'s'}` : '';
  $('tasks').innerHTML = data.tasks.map(t=>`<div class="task"><div><strong>${esc(t.id)}</strong><small>${esc(t.stage)} · after ${esc(t.dependsOn.join(", ") || "run start")}</small></div><span class="badge ${esc(s.tasks[t.id].toLowerCase())}">${esc(s.tasks[t.id])}</span></div>`).join('');
  $('clarification').hidden = !s.pendingClarificationTask;
  $('question').textContent = data.tasks.find(t=>t.id===s.pendingClarificationTask)?.prompt || '';
  const questions = (data.clarificationContext || []).map(a => a.text).join('\n\n');
  $('clarification-context').hidden = !questions;
  $('clarification-context').textContent = questions;
  $('review').hidden = !data.review;
  if(data.review) {
    $('review-title').textContent = 'Review: ' + data.review.task;
    $('review-description').textContent = data.review.task === 'release' ? 'Final approval completes this run. It does not merge or deploy the candidate.' : 'Approve this exact patch to apply it to the isolated candidate and continue validation.';
    $('review-hash').value = data.review.hash;
    $('review-evidence').textContent = (data.validationEvidence || []).map(a => `${a.task}\n${a.text}`).join('\n\n') || 'No completed validation for this candidate yet. Patch approval allows application to the isolated workspace; validation follows before release review.';
    if(reviewHash !== data.review.hash) { $('patch').textContent = data.review.patch; $('reviewed').checked = false; $('approve').disabled = true; reviewHash = data.review.hash; }
  } else { reviewHash = null; }
  const revisable = data.tasks.filter(t=>['ARTIFACT','PATCH'].includes(t.kind));
  const revisionKey = `${s.id}:${data.review?.hash || ''}`;
  $('revision-section').hidden = ended || revisable.length === 0;
  updateOptions('revision-task', revisable.map(t=>t.id));
  if ($('revision-task').dataset.review !== revisionKey) {
    const preferred = revisable.find(t => t.id === data.review?.task) || revisable.findLast(t => t.kind === 'PATCH') || revisable[0];
    if (preferred) $('revision-task').value = preferred.id;
    $('revision-task').dataset.review = revisionKey;
  }
  $('revision-form').querySelector('button').disabled = data.busy;
  updateOptions('artifact', data.artifacts, 'Select an artifact', artifactLabel);
  $('events').innerHTML = data.events.map(e=>`<div class="event"><b>${esc(e.type)}</b><small>${esc(e.at)}</small><p>${esc(e.detail)}</p></div>`).join('');
}
function confirmLiveCreation() {
  return confirm('Create a live run? Starting or resuming it sends context to the configured model provider and incurs API charges. Creation alone makes no model calls.');
}
async function action(body) {
  if (!selected || current?.state.id !== selected) return;
  if (current.state.mode === 'live' && ['advance','approve','revise','clarify'].includes(body.action)) {
    const remaining = Math.max(0, current.state.maxModelCalls - current.state.modelCalls);
    if (!confirm(`Continue this live run? It may make up to ${remaining} remaining paid provider calls. Actual cost depends on model and token usage; the call limit is not a dollar budget.`)) {
      $('approve').disabled = !$('reviewed').checked;
      return;
    }
  }
  await api('runs/'+selected+'/actions',body);
  await refresh();
}
$('runs').addEventListener('click', e => {const b=e.target.closest('[data-run]');if(b)perform(()=>choose(b.dataset.run));});
$('refresh').onclick = () => perform(refresh);
$('feature-form').onsubmit = e => {e.preventDefault(); perform(async()=>{if(!confirmLiveCreation())return;const s=await api('runs',{kind:'feature',requirement:$('requirement').value});await choose(s.id);});};
$('scenario-form').onsubmit = e => {e.preventDefault();perform(async()=>{if($('mode').value==='live'&&!confirmLiveCreation())return;const s=await api('runs',{kind:'scenario',scenario:$('scenario').value,mode:$('mode').value});await choose(s.id);});};
$('advance').onclick = () => perform(()=>action({action:'advance'}));
$('reviewed').onchange = () => {$('approve').disabled=!$('reviewed').checked;};
$('approve').onclick = () => perform(async()=>{if(!$('reviewed').checked || !reviewHash)return;const hash=reviewHash;$('approve').disabled=true;await action({action:'approve',hash});});
$('reject').onclick = () => perform(async()=>{
  if (!reviewHash || !confirm('Reject this run permanently? It will end as NOT_APPROVED and cannot resume. Use Request changes to revise the candidate instead.')) return;
  await action({action:'reject',hash:reviewHash});
});
$('answer-form').onsubmit = e => {e.preventDefault();perform(()=>action({action:'clarify',answer:$('answer').value}));};
$('revision-form').onsubmit = e => {e.preventDefault();perform(()=>action({action:'revise',task:$('revision-task').value,feedback:$('feedback').value}));};
$('artifact').onchange = () => perform(async()=>{const name=$('artifact').value;if(!name){$('artifact-text').hidden=true;return;}const a=await api('runs/'+selected+'/artifacts/'+encodeURIComponent(name));$('artifact-text').textContent=a.text;$('artifact-text').hidden=false;});
document.addEventListener('visibilitychange', () => {
  clearTimeout(refreshTimer);
  if (!document.hidden) perform(refresh);
});
perform(async()=>{const c=await api('config');token=c.token;$('feature-form').querySelector('button').disabled=!c.liveReady; if(!c.liveReady)notice('Add ANTHROPIC_API_KEY to .env and restart for live features. Fixture demonstrations are available.');await refresh();});
