importScripts('core/domains.js', 'core/labels.js');
const { buildRules, inList, normalizeDomain } = self.OCFDomains;

const DEFAULTS = {
  lists_block: [], lists_warn: [], lists_allow: [],
  settings: { enabled: true, showMedium: false, blurHigh: false, readImageLabels: true },
};

async function readLists() {
  const s = await chrome.storage.sync.get(DEFAULTS);
  return {
    lists: { block: s.lists_block, warn: s.lists_warn, allow: s.lists_allow },
    settings: { ...DEFAULTS.settings, ...s.settings },
  };
}

async function syncRules() {
  const { lists, settings } = await readLists();
  const addRules = settings.enabled ? buildRules(lists, chrome.runtime.getURL('warn.html')) : [];
  const old = await chrome.declarativeNetRequest.getDynamicRules();
  await chrome.declarativeNetRequest.updateDynamicRules({
    removeRuleIds: old.map((r) => r.id),
    addRules,
  });
}

chrome.runtime.onInstalled.addListener(syncRules);
chrome.runtime.onStartup.addListener(syncRules);
chrome.storage.onChanged.addListener((_changes, area) => {
  if (area === 'sync') syncRules();
});
chrome.action.onClicked.addListener(() => chrome.runtime.openOptionsPage());

// "Continue anyway" from the warning page: allow that domain until the browser
// closes. Only honoured for domains on the warn list and not on the block list,
// so a web page that opens warn.html itself cannot unblock anything.
async function proceed(rawUrl) {
  let host;
  try {
    const u = new URL(rawUrl);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return { ok: false };
    host = u.hostname;
  } catch { return { ok: false }; }
  const { lists } = await readLists();
  if (inList(host, lists.block) || !inList(host, lists.warn)) return { ok: false };
  const domain = lists.warn.find((d) => inList(host, [d]));
  const existing = await chrome.declarativeNetRequest.getSessionRules();
  const id = existing.reduce((m, r) => Math.max(m, r.id), 0) + 1;
  await chrome.declarativeNetRequest.updateSessionRules({
    addRules: [{
      id,
      priority: 3,
      action: { type: 'allow' },
      condition: { requestDomains: [normalizeDomain(domain)], resourceTypes: ['main_frame'] },
    }],
  });
  return { ok: true };
}

// Reads declared labels from the first 256 KB of an image already shown on a
// page. Sent without cookies; the bytes are inspected here and discarded.
const IMAGE_BYTES = 262144;
const imageCache = new Map();
async function imageLabels(rawUrl) {
  let u;
  try { u = new URL(rawUrl); } catch { return { ok: false }; }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return { ok: false };
  if (imageCache.has(u.href)) return imageCache.get(u.href);
  const res = await fetch(u.href, {
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
    headers: { Range: 'bytes=0-' + (IMAGE_BYTES - 1) },
  });
  if (!res.ok || !res.body) return { ok: false };
  const reader = res.body.getReader();
  const chunks = [];
  let size = 0;
  while (size < IMAGE_BYTES) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    size += value.length;
  }
  reader.cancel().catch(() => {});
  const bytes = new Uint8Array(Math.min(size, IMAGE_BYTES));
  let off = 0;
  for (const c of chunks) {
    const part = c.subarray(0, bytes.length - off);
    bytes.set(part, off);
    off += part.length;
    if (off >= bytes.length) break;
  }
  const out = { ok: true, ...self.OCFLabels.readImageLabels(new TextDecoder('latin1').decode(bytes)) };
  if (imageCache.size > 500) imageCache.clear();
  imageCache.set(u.href, out);
  return out;
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg || sender.id !== chrome.runtime.id) return;
  if (msg.type === 'proceed') {
    proceed(msg.url).then(sendResponse, () => sendResponse({ ok: false }));
    return true;
  }
  if (msg.type === 'imageLabels') {
    imageLabels(msg.url).then(sendResponse, () => sendResponse({ ok: false }));
    return true;
  }
});
