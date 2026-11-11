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
const zlib = require('node:zlib');

// Builds a valid 240x240 grey PNG, optionally with an XMP text chunk.
function png(xmp) {
  const chunk = (type, data) => {
    const body = Buffer.concat([Buffer.from(type, 'latin1'), data]);
    const out = Buffer.alloc(body.length + 8);
    out.writeUInt32BE(data.length, 0);
    body.copy(out, 4);
    out.writeUInt32BE(zlib.crc32(body) >>> 0, body.length + 4);
    return out;
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(240, 0); ihdr.writeUInt32BE(240, 4);
  ihdr[8] = 8; // bit depth; colour type 0 (greyscale) and the rest stay 0
  const parts = [Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr)];
  if (xmp) parts.push(chunk('iTXt', Buffer.from('XML:com.adobe.xmp\0\0\0\0\0' + xmp, 'latin1')));
  parts.push(chunk('IDAT', zlib.deflateSync(Buffer.alloc(240 * 241, 0x80))), chunk('IEND', Buffer.alloc(0)));
  return Buffer.concat(parts);
}
const AI_PNG = png('<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:Description Iptc4xmpExt:DigitalSourceType=' +
  '"http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"/></x:xmpmeta>');
const PLAIN_PNG = png(null);

const PAGE = `<!doctype html><title>t</title>
<script type="application/ld+json">{"@context":"https://schema.org","@type":"ClaimReview",
"claimReviewed":"The moon is made of cheese","reviewRating":{"@type":"Rating","alternateName":"False"},
"author":{"@type":"Organization","name":"Example Checks"}}</script>
<script type="application/ld+json">{broken json</script>
<a rel="nofollow sponsored" href="#">Buy now</a>
<img src="/ai.png" id="ai"><img src="/plain.png" id="plain">
<h1>SHOCKING betrayal: corrupt officials DESTROY town in terrifying disaster!!!</h1>
<p>The city council met on Tuesday to discuss the annual budget and road repairs.</p>
<p>Hurry! Only 3 left in stock.</p>
<video autoplay muted></video>`;

(async () => {
  const server = http.createServer((req, res) => {
    if (req.url === '/ai.png' || req.url === '/plain.png') {
      res.writeHead(200, { 'content-type': 'image/png' });
      return res.end(req.url === '/ai.png' ? AI_PNG : PLAIN_PNG);
    }
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

    // 1b. Declared labels: AI image metadata, fact-check markup, sponsored links.
    await page.waitForSelector('#ai.ocf-img-flag');
    assert.equal(await page.locator('.ocf-img-flag').count(), 1, 'the plain image is not flagged');
    const labels = await page.locator('#ocf-panel-host').evaluate((h) => h.shadowRoot.textContent);
    assert.match(labels, /Declared AI-generated image/);
    assert.match(labels, /The moon is made of cheese.*Rated .False. by Example Checks/);
    assert.match(labels, /1 sponsored link\./);

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

    // 7. Claim lookup is absent until a key is saved, then reports results.
    //    The fact-check service is stubbed; no real request is made.
    const menuBefore = await sw.evaluate(() => new Promise((r) =>
      chrome.contextMenus.update('ocf-check-claim', {}, () => r(!chrome.runtime.lastError))));
    assert.equal(menuBefore, false, 'no menu item without a key');
    await opts.fill('#factCheckKey', 'test-key');
    await opts.click('#save');
    const lookup = await sw.evaluate(async () => {
      for (let i = 0; i < 50; i++) {
        const ok = await new Promise((r) =>
          chrome.contextMenus.update('ocf-check-claim', {}, () => r(!chrome.runtime.lastError)));
        if (ok) break;
        if (i === 49) throw new Error('menu item not created');
        await new Promise((r) => setTimeout(r, 100));
      }
      let requested = '';
      self.fetch = async (u) => {
        requested = String(u);
        return new Response(JSON.stringify({ claims: [{ text: 'The moon is cheese', claimReview: [
          { publisher: { name: 'Stub Checker' }, url: 'https://stub.example/x', textualRating: 'Pants on fire' },
        ] }] }));
      };
      const [tab] = await chrome.tabs.query({ url: 'http://plain.test/*' });
      await checkClaim(tab.id, '  moon   is cheese ');
      return requested;
    });
    assert.match(lookup, /^https:\/\/factchecktools\.googleapis\.com\/v1alpha1\/claims:search\?pageSize=5&query=moon%20is%20cheese&key=test-key$/);
    await page.waitForFunction(() => /Looked up/.test(document.getElementById('ocf-panel-host').shadowRoot.textContent));
    const looked = await page.locator('#ocf-panel-host').evaluate((h) => h.shadowRoot.textContent);
    assert.match(looked, /Looked up: .The moon is cheese.*Rated .Pants on fire. by Stub Checker/);

    console.log('e2e: all checks passed');
  } finally {
    await ctx.close();
    server.close();
    fs.rmSync(profile, { recursive: true, force: true });
  }
})().catch((e) => { console.error(e); process.exit(1); });
