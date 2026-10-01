#!/usr/bin/env node
/* Exercises the real bundled ADK Markdown UI. All chat/model responses are intercepted fixtures. */
const assert = require('node:assert/strict');
const {pathToFileURL} = require('node:url');
const path = require('node:path');
const fs = require('node:fs/promises');
const {readFileSync} = require('node:fs');
const base = process.env.FACTORY_TEST_URL || 'http://localhost:8000';
// Exact valid diagram from the reported failure: multiline HTML breaks and a subgraph.
const architecture = readFileSync(path.join(__dirname, 'fixtures/shortener-architecture.mmd'), 'utf8');
const sequence = `sequenceDiagram
    autonumber
    actor C as Client
    participant F as Filters
    participant S as ShortenLink
    participant P as UrlPolicy and LinkCodes
    participant L as CreationRateLimiter
    participant R as LinkRepository (cached)
    participant D as PostgreSQL
    C->>F: POST /api/shorten
    F->>F: request id, body at most 64 KB
    F->>S: shorten(url, alias, peer address)
    S->>P: validate URL and alias
    alt invalid
        S-->>C: 400 invalid_request (quota not used)
    end
    S->>L: admit(peer address)
    alt over quota
        S-->>C: 429 rate_limited + Retry-After
    end
    S->>R: create(code, target)
    R->>D: INSERT link and its stats row (one statement)
    alt code already taken
        D-->>S: unique violation
        S-->>C: 409 alias_conflict (alias) or retry with a new code
    end
    R->>R: remember link in cache
    S-->>C: 201 {code, shortUrl}`;
const fence = text => '```mermaid\n' + text + '\n```';
const answers = [
  fence(sequence) + '\n\n**Shortener architecture**\n\n' + fence(architecture)
    + '\n\n```java\nSystem.out.println("ordinary code");\n```',
  fence('sequenceDiagram\n A->>B: hello\n alt invalid\n B-->>A: 400\n end\''),
  fence('flowchart LR\n A["<img src=x onerror=alert(1)>"] --> B[Safe]\n click B "javascript:alert(1)"'),
  fence('%%{init: {"securityLevel":"loose"}}%%\nflowchart LR\n A-->B'),
  fence(sequence),
];
(async () => {
  const modulePath = process.env.FACTORY_BROWSER_MODULE;
  const {default: puppeteer} = await import(modulePath ? pathToFileURL(path.resolve(modulePath)).href : 'puppeteer-core');
  const browser = await puppeteer.launch({executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless:true});
  const page = await browser.newPage();
  const errors = [], remote = [];
  let calls = 0, dialogs = 0;
  const events = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', async dialog => { dialogs++; await dialog.dismiss(); });
  await page.setViewport({width:1440, height:1000});
  await page.setRequestInterception(true);
  page.on('request', request => {
    const url = new URL(request.url());
    const json = body => request.respond({status:200, contentType:'application/json', body:JSON.stringify(body)});
    // The Java ADK server does not implement these optional Python UI endpoints.
    if (url.pathname === '/version') return json({version:'1.10.1'});
    if (url.pathname.startsWith('/dev/build_graph/')) return json({root_agent:{name:'software_factory'}});
    if (url.origin !== base && url.protocol.startsWith('http')) {
      remote.push(url.href);
      return request.abort(); // Offline fonts are harmless; diagram contents must stay local.
    }
    if (url.pathname === '/run_sse') {
      const text = answers[calls++];
      assert(text, 'Unexpected model request');
      const event = {id:'diagram-' + calls, invocationId:'invocation-' + calls, author:'software_factory', timestamp:Date.now()/1000, content:{role:'model', parts:[{text}]}};
      events.push(event);
      return request.respond({status:200, contentType:'text/event-stream', body:'data: ' + JSON.stringify(event) + '\n\n'});
    }
    if (/\/eval_(sets|results)$/.test(url.pathname) || url.pathname.startsWith('/debug/trace/')) return json([]);
    if (/\/sessions(?:\/chat-diagram-test)?$/.test(url.pathname)) {
      const session = {id:'chat-diagram-test', appName:'software_factory', userId:'user', state:{}, events, lastUpdateTime:Date.now()/1000};
      return json(request.method() === 'GET' && url.pathname.endsWith('/sessions') ? [session] : session);
    }
    if (request.method() !== 'GET') return request.abort(); // No real state changes or paid requests.
    return request.continue();
  });
  async function send() {
    await page.waitForFunction(() => !document.querySelector('textarea').disabled);
    await page.type('textarea', 'Show the architecture in a diagram');
    await page.keyboard.press('Enter');
  }
  const loaded = count => page.waitForFunction(n => [...document.querySelectorAll('.factory-diagram img')].filter(i => i.complete && i.naturalWidth > 0).length === n, {}, count);
  try {
    for (const entry of ['/dev-ui/', '/dev-ui/index.html']) {
      const response = await page.goto(base + entry + '?app=software_factory', {waitUntil:'networkidle0'});
      assert.equal(response.status(), 200);
      assert(await page.$('script[src="/chat/diagrams.js"]'));
    }
    await send();
    await loaded(2);
    const labels = await page.$$eval('.factory-diagram img', async images => {
      const svg = await (await fetch(images[1].src)).text();
      const xml = new DOMParser().parseFromString(svg, 'image/svg+xml');
      if (xml.querySelector('parsererror, foreignObject')) throw new Error('Image must be valid SVG with text labels');
      return [...xml.querySelectorAll('text')].map(node => node.textContent).join(' ');
    });
    for (const label of ['Filters', 'request id', 'ShortenLink', 'PostgreSQL', 'port 5433']) {
      assert(labels.includes(label), 'Architecture label missing: ' + label);
    }
    assert.equal(await page.$$eval('pre.factory-diagram-source-hidden', x => x.length), 2);
    assert(await page.$eval('code.language-java', x => x.getBoundingClientRect().height > 0));
    assert(await page.$eval('.factory-diagram img', x => x.width > 300), 'Diagram must be readable at intrinsic size');
    await page.click('.factory-diagram button');
    assert.equal(await page.$eval('.factory-diagram button', x => x.getAttribute('aria-expanded')), 'true');
    assert(await page.$eval('code.language-mermaid', x => x.getBoundingClientRect().height > 0));
    await send();
    await page.waitForSelector('.factory-diagram-error');
    assert(await page.$eval('.factory-diagram-error', x => !x.nextElementSibling.classList.contains('factory-diagram-source-hidden')));
    await send();
    await page.waitForFunction(() => {
      const panels = document.querySelectorAll('.factory-diagram');
      const image = panels[3]?.querySelector('img');
      return panels[3]?.classList.contains('factory-diagram-error') || image?.complete && image.naturalWidth > 0;
    });
    const safeImages = await page.$$eval('.factory-diagram img', nodes => nodes.length);
    const rejectedDiagrams = await page.$$eval('.factory-diagram-error', nodes => nodes.length);
    await send();
    await page.waitForFunction(n => document.querySelectorAll('.factory-diagram-error').length === n + 1, {}, rejectedDiagrams);
    assert.equal(dialogs, 0);
    assert.equal(await page.$$eval('markdown script, markdown iframe, markdown img[src="x"]', x => x.length), 0);
    await send();
    await loaded(safeImages + 1); // An invalid block must not poison later renders.
    // Simulate a streaming update to a node already rendered; ensure replacement and source toggle recover.
    await page.$eval('code.language-mermaid', (node, text) => { node.textContent = text; }, sequence + '\n S->>C: Follow-up');
    await page.waitForFunction(() => document.querySelector('code.language-mermaid').closest('pre').classList.contains('factory-diagram-source-hidden'));
    await loaded(safeImages + 1);
    await page.reload({waitUntil:'networkidle0'});
    // Session history travels through ADK's own Markdown component on reload.
    await loaded(safeImages + 1);
    await page.setViewport({width:390, height:844});
    assert(await page.$eval('.factory-diagram-viewport', x => getComputedStyle(x).overflowX === 'auto'));
    await page.setViewport({width:1440, height:1000});
    await fs.mkdir('.runs/browser', {recursive:true});
    await page.screenshot({path:'.runs/browser/adk-diagrams.png', fullPage:true});
    assert.equal(calls, answers.length);
    assert.deepEqual(errors, []);
    assert(remote.every(url => /fonts\.(googleapis|gstatic)\.com/.test(url)), 'No external diagram requests');
    console.log('PASS: ADK sequence/flow diagrams, source toggle, ordinary code, invalid syntax, unsafe directives, SVG safety, dynamic updates, history reload, mobile scrolling. No paid calls.');
  } catch (error) {
    console.error(await page.$$eval('.factory-diagram', nodes => nodes.map(n => n.outerHTML)));
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
