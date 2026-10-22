#!/usr/bin/env node
/* Transport regression: no server, model calls, or workflow mutations. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../factory/src/main/resources/static/chat/workflow.js'), 'utf8');
const sent = [];
let notice, removed = false;
class XHR {
  open(method, url) { this.method = method; this.url = url; this.headers = {}; }
  setRequestHeader(key, value) { this.headers[key] = value; }
  addEventListener(name, callback) { this.callback = callback; }
  send(body) { this.body = body; }
}
const context = {
  URL, Request, Headers, XMLHttpRequest: XHR,
  location: new URL('http://localhost:8001/dev-ui/?app=software_factory'),
  document: {
    querySelector: () => ({content: 'page-capability', remove() { removed = true; }}),
    getElementById: () => notice,
    createElement: () => ({setAttribute() {}}),
    body: {prepend(element) { notice = element; }},
  },
  window: {async fetch(input, init) { sent.push({input, init}); return {status: 403}; }},
};
vm.runInNewContext(source, context);
(async () => {
  assert(removed, 'Capability should not remain in the DOM');
  await context.window.fetch('/run_sse', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: 'command'});
  assert.equal(sent.at(-1).init.headers.get('X-Factory-Token'), 'page-capability');
  assert.equal(sent.at(-1).init.headers.get('Content-Type'), 'application/json');
  assert.equal(sent.at(-1).init.body, 'command');
  assert.match(notice.textContent, /Reload this page/);
  const request = new Request('http://localhost:8001/run', {method: 'POST', headers: {'X-Test': 'preserved'}, body: 'body'});
  await context.window.fetch(request);
  assert.equal(sent.at(-1).init.headers.get('X-Test'), 'preserved');
  assert.equal(sent.at(-1).init.headers.get('X-Factory-Token'), 'page-capability');
  assert.equal(await sent.at(-1).input.text(), 'body');
  for (const [url, method] of [['https://other.example/run', 'POST'], ['/unrelated', 'POST'], ['/run', 'GET']]) {
    await context.window.fetch(url, {method});
    assert.equal(sent.at(-1).init.headers, undefined, 'Never leak capability outside chat POSTs');
    const xhr = new XHR();
    xhr.open(method, url);
    xhr.send('body');
    assert.equal(xhr.headers['X-Factory-Token'], undefined);
  }
  const xhr = new XHR();
  xhr.open('POST', '/run_sse');
  xhr.send('command');
  assert.equal(xhr.headers['X-Factory-Token'], 'page-capability');
  assert.equal(xhr.body, 'command');
  xhr.status = 500;
  xhr.callback();
  assert.match(notice.textContent, /HTTP 500/);
  xhr.open('POST', 'https://other.example/run');
  xhr.send('body');
  assert.equal(xhr.headers['X-Factory-Token'], undefined, 'Reused XHR must not leak capability');
  console.log('PASS: fetch/XHR authentication, preserved requests, origin/path/method isolation, visible errors');
})().catch(error => { console.error(error); process.exitCode = 1; });
