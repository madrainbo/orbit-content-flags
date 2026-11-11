importScripts('core/domains.js');
const { buildRules, inList, normalizeDomain } = self.OCFDomains;

const DEFAULTS = {
  lists_block: [], lists_warn: [], lists_allow: [],
  settings: { enabled: true, showMedium: false, blurHigh: false },
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

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg && msg.type === 'proceed') {
    proceed(msg.url).then(sendResponse, () => sendResponse({ ok: false }));
    return true;
  }
});
