// End-to-end check in real Chromium: loads the unpacked extension, serves two
// local pages, and exercises flags, the warn interstitial and the block list.
// Run: node test/e2e.js   (needs Playwright with Chromium)
'use strict';
const http = require('node:http');
const os = require('node:os');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

const EXT = path.resolve(__dirname, '..');
const PAGE = `<!doctype html><title>t</title>
<h1>SHOCKING betrayal: corrupt officials DESTROY town in terrifying disaster!!!</h1>
<p>The city council met on Tuesday to discuss the annual budget and road repairs.</p>
<p>Hurry! Only 3 left in stock.</p>
<video autoplay muted></video>`;

(async () => {
  const server = http.createServer((_req, res) => {
    res.writeHead(200, { 'content-type': 'text/html' });
    res.end(PAGE);
  }).listen(0, '127.0.0.1');
  await new Promise((r) => server.once('listening', r));
  const port = server.address().port;
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'ocf-e2e-'));
  const ctx = await chromium.launchPersistentContext(profile, {
    channel: 'chromium',
    headless: true,
    args: [
      `--disable-extensions-except=${EXT}`,
      `--load-extension=${EXT}`,
      '--host-resolver-rules=MAP *.test 127.0.0.1',
    ],
  });
  try {
    const sw = ctx.serviceWorkers()[0] || await ctx.waitForEvent('serviceworker');
    const extId = new URL(sw.url()).host;

    // 1. Flags on a plain page.
    const page = await ctx.newPage();
    await page.goto(`http://plain.test:${port}/`);
    await page.waitForSelector('.ocf-chip.ocf-high');
    assert.equal(await page.locator('.ocf-chip').count(), 1, 'only the charged heading is flagged');
    const panel = await page.locator('#ocf-panel-host').evaluate((h) => h.shadowRoot.textContent);
    assert.match(panel, /1 high-charge passage/);
    assert.match(panel, /Pressure wording/);
    assert.match(panel, /Autoplay video/);

    // 2. Dispute removes the chip and is counted.
    await page.locator('.ocf-chip').click();
    assert.equal(await page.locator('.ocf-chip').count(), 0);

    // 3. Save lists through the options page.
    const opts = await ctx.newPage();
    await opts.goto(`chrome-extension://${extId}/options.html`);
    await opts.waitForFunction(() => /disputed 1 flag/.test(document.getElementById('disputes').textContent));
    await opts.fill('#warn', 'warned.test');
    await opts.fill('#block', 'https://www.blocked.test/x');
    await opts.click('#save');
    await opts.waitForFunction(() => document.getElementById('status').textContent === 'Saved.');
    assert.equal(await opts.inputValue('#block'), 'blocked.test');
    await sw.evaluate(async () => {
      for (let i = 0; i < 50; i++) {
        if ((await chrome.declarativeNetRequest.getDynamicRules()).length === 2) return;
        await new Promise((r) => setTimeout(r, 100));
      }
      throw new Error('rules not installed');
    });

    // 4. Warned site: interstitial, then continue.
    const warnUrl = `http://sub.warned.test:${port}/a?b=1`;
    await page.goto(warnUrl);
    assert.ok(page.url().startsWith(`chrome-extension://${extId}/warn.html?mode=warn#`), page.url());
    assert.match(await page.textContent('#title'), /sub\.warned\.test is on your warn list/);
    await page.click('#proceed');
    await page.waitForURL(warnUrl);

    // 5. Blocked site: interstitial with no way through.
    await page.goto(`http://blocked.test:${port}/`);
    assert.ok(page.url().includes('warn.html?mode=block#'), page.url());
    assert.ok(await page.locator('#proceed').isHidden());
    const res = await page.evaluate((u) => chrome.runtime.sendMessage({ type: 'proceed', url: u }),
      `http://blocked.test:${port}/`);
    assert.equal(res.ok, false, 'block list cannot be bypassed via the proceed message');

    // 6. Unlisted site is untouched.
    await page.goto(`http://plain.test:${port}/`);
    assert.equal(page.url(), `http://plain.test:${port}/`);

    console.log('e2e: all checks passed');
  } finally {
    await ctx.close();
    server.close();
    fs.rmSync(profile, { recursive: true, force: true });
  }
})().catch((e) => { console.error(e); process.exit(1); });
