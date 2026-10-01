#!/usr/bin/env node
/* Real Chrome fixture checks. Requires puppeteer-core and a local Chrome executable.
 * Live dialogs are CANCELLED: this script never authorizes paid calls.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const base = process.env.FACTORY_TEST_URL || 'http://localhost:8000';
const output = path.resolve('.runs/browser', new Date().toISOString().replaceAll(':', '-'));
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
  const modulePath = process.env.FACTORY_BROWSER_MODULE;
  const {default: puppeteer} = await import(modulePath ? pathToFileURL(path.resolve(modulePath)).href : 'puppeteer-core');
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true,
  });
  const page = await browser.newPage();
  const errors = [], checks = [], runIds = [];
  let acceptDialog = false, dialogs = [], posts = 0;
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => { if (response.status() >= 400) errors.push(`${response.status()} ${response.url()}`); });
  page.on('request', request => { if (request.method() === 'POST') posts++; });
  page.on('dialog', async dialog => {
    dialogs.push(dialog.message());
    if (acceptDialog) await dialog.accept(); else await dialog.dismiss();
  });
  await fs.mkdir(output, {recursive: true});
  async function until(predicate, description) {
    const deadline = Date.now() + 240000;
    while (Date.now() < deadline) {
      await page.click('#refresh');
      await delay(500);
      if (await page.evaluate(predicate)) return;
      await delay(1000);
    }
    throw new Error('Timed out: ' + description);
  }
  async function create(scenario) {
    const prior = await page.$eval('#run-id', e => e.textContent);
    await page.select('#scenario', scenario);
    await page.select('#mode', 'fixture');
    await page.click('#scenario-form button');
    await page.waitForFunction(previous => document.querySelector('#run-id').textContent !== previous && document.querySelector('#run-id').textContent, {}, prior);
    runIds.push(await page.$eval('#run-id', e => e.textContent));
  }
  async function waitReview(task) {
    await until(() => !document.querySelector('#review').hidden, 'review');
    await page.waitForFunction(expected => document.querySelector('#review-title').textContent === 'Review: ' + expected, {}, task);
  }
  async function approve() {
    assert(await page.$eval('#approve', e => e.disabled));
    await page.click('#reviewed');
    await page.click('#approve');
    await page.waitForFunction(() => document.querySelector('#review').hidden);
  }
  try {
    await page.setViewport({width:1280,height:900});
    await page.goto(base + '/factory/', {waitUntil:'networkidle0'});
    assert.deepEqual(await page.$$eval('#scenario option', options => options.map(o => o.value)), ['bugfix','brownfield','ambiguous','greenfield']);
    assert.equal((await page.goto(base + '/favicon.ico')).status(), 200);
    await page.goto(base + '/factory/', {waitUntil:'networkidle0'});
    checks.push('All required scenarios selectable; favicon available');
    const beforePosts = posts;
    await page.select('#mode', 'live');
    await page.click('#scenario-form button');
    await delay(300);
    assert(dialogs.at(-1).includes('API charges'));
    assert.equal(posts, beforePosts, 'Cancelling live creation must not create a run');
    checks.push('Live creation cancellation sends no POST or paid call');
    // Exercise every dropdown option without injecting any DOM markup.
    for (const scenario of ['greenfield','brownfield']) await create(scenario);
    await create('bugfix');
    await page.click('#advance');
    await waitReview('release');
    const priorStatus = await page.$eval('#run-status', e => e.textContent);
    await page.click('#reject');
    await delay(300);
    assert(dialogs.at(-1).includes('permanently'));
    assert.equal(await page.$eval('#run-status', e => e.textContent), priorStatus);
    assert.equal(await page.$eval('#revision-task', e => e.value), 'fix');
    assert(await page.$eval('#review-evidence', e => e.textContent.includes('green')));
    assert(await page.$eval('#review-hash', e => e.labels.length === 1 && e.value.length === 64));
    assert(await page.$$eval('#artifact option', options => options.some(o => o.textContent.includes('version'))));
    await page.screenshot({path:path.join(output,'release-review.png'),fullPage:true});
    checks.push('Reject cancellation preserves run; release revision task, accessible hash and validation evidence visible');
    await page.type('#feedback', 'Synthetic browser test: regenerate the fix for final review.');
    await page.click('#revision-form button');
    await page.waitForFunction(() => document.querySelector('#review').hidden);
    await waitReview('release');
    await approve();
    await until(() => document.querySelector('#run-status').textContent === 'COMPLETED', 'completed bugfix');
    checks.push('Bugfix: approval, release revision, renewed release approval, completion');
    await create('ambiguous');
    await page.click('#advance');
    await until(() => !document.querySelector('#clarification').hidden, 'clarification');
    assert(await page.$eval('#clarification-context', e => !e.hidden && e.textContent.length > 50));
    await page.type('#answer', 'Fixture test: one instance, immutable links, 60 second cache, 100 requests/second and 200ms p95; best-effort analytics.');
    await page.click('#answer-form button');
    await waitReview('apply');
    acceptDialog = true;
    await page.click('#reject');
    await until(() => document.querySelector('#run-status').textContent === 'NOT_APPROVED', 'confirmed rejection');
    checks.push('Actual clarification artifact visible; answer resumes; confirmed rejection ends run');
    await page.setViewport({width:390,height:844});
    await page.screenshot({path:path.join(output,'mobile.png'),fullPage:true});
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    checks.push('390px mobile layout has no horizontal overflow');
    for (const id of runIds) {
      const state = await page.evaluate(async run => (await (await fetch('/factory/api/runs/' + run)).json()).state, id);
      assert.equal(state.mode, 'fixture');
      assert.equal(state.modelCalls, 0);
    }
    assert.deepEqual(errors, []);
    await fs.writeFile(path.join(output,'results.json'), JSON.stringify({passed:true,checks,runIds,errors},null,2)+'\n');
    console.log(JSON.stringify({passed:true,checks,output},null,2));
  } catch (error) {
    await page.screenshot({path:path.join(output,'failure.png'),fullPage:true}).catch(()=>{});
    await fs.writeFile(path.join(output,'results.json'),JSON.stringify({passed:false,checks,runIds,errors,error:error.stack},null,2)+'\n');
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
