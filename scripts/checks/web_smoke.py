#!/usr/bin/env python3
"""Exercise local operator APIs against real fixture runs. No paid model calls."""
import json
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError

BASE = 'http://localhost:8000'
TOKEN = None

def request(path, body=None, status=200, origin=None, include_token=True):
    headers = {'Content-Type': 'application/json'}
    if include_token and TOKEN: headers['X-Factory-Token'] = TOKEN
    if origin: headers['Origin'] = origin
    req = Request(BASE + path, headers=headers, data=None if body is None else json.dumps(body).encode())
    try:
        with urlopen(req, timeout=20) as response:
            code, raw = response.status, response.read()
    except HTTPError as error:
        code, raw = error.code, error.read()
    assert code == status, (path, code, raw[:500])
    if code == 200: return json.loads(raw)

def action(run, **body): return request(f'/factory/api/runs/{run}/actions', body)
def detail(run): return request(f'/factory/api/runs/{run}')
def idle(run):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        data = detail(run)
        if not data['busy']: return data
        time.sleep(1)
    raise AssertionError('Run did not become idle')

def main():
    global TOKEN
    TOKEN = request('/factory/api/config')['token']
    request('/factory/api/runs', {'kind':'scenario','scenario':'bugfix'}, status=403, include_token=False)
    request('/factory/api/runs', {'kind':'scenario','scenario':'bugfix'}, status=403, origin='https://example.org')
    state = request('/factory/api/runs', {'kind':'scenario','scenario':'ambiguous','mode':'fixture'})
    run = state['id']
    action(run, action='advance')
    data = idle(run)
    assert data['state']['pendingClarificationTask']
    assert data['clarificationContext'] and data['clarificationContext'][0]['text']
    assert data['auditCheckedAt']
    action(run, action='clarify', answer='Single instance; immutable links; 60 second cache; preserve redirects.')
    data = idle(run)
    assert data['review']['task'] == 'apply'
    first_hash = data['review']['hash']
    request(f'/factory/api/runs/{run}/actions', {'action':'approve','hash':'0'*64}, status=409)
    assert detail(run)['state']['pendingApprovalHash'] == first_hash
    action(run, action='revise', task='apply', feedback='Fixture test: regenerate the proposal for review.')
    data = idle(run)
    assert data['state']['reviewFeedback']['apply'].startswith('Fixture test:')
    assert len([a for a in data['artifacts'] if a.startswith('apply-v')]) >= 2
    action(run, action='approve', hash=data['review']['hash'])
    data = idle(run)
    assert data['review']['task'] == 'release', data['state']
    assert data['validationEvidence'] and all(item['text'] for item in data['validationEvidence'])
    request(f'/factory/api/runs/{run}/actions', {'action':'reject','hash':'0'*64}, status=409)
    action(run, action='reject', hash=data['review']['hash'])
    data = detail(run)
    assert data['state']['status'] == 'NOT_APPROVED' and data['auditValid']
    request(f'/factory/api/runs/{run}/artifacts/.env', status=409)
    print('PASS local protection, clarification, stale hash, revision, patch approval, tests, rejection and audit:', run)

if __name__ == '__main__': main()
