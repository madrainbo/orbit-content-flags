// Domain-list helpers: pure functions shared by the service worker, the
// options page, the warning page and Node tests.
(function (root) {
  'use strict';

  const DOMAIN_RE = /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;
  // chrome.storage.sync allows 8,192 bytes per item; each list is one item.
  const MAX_PER_LIST = 300;

  // "https://www.Example.com/path" -> "example.com"; returns null if not a domain.
  function normalizeDomain(input) {
    let s = String(input || '').trim().toLowerCase();
    if (!s) return null;
    s = s.replace(/^[a-z][a-z0-9+.-]*:\/\//, '').replace(/^[^@/]*@/, '');
    s = s.split(/[/?#]/)[0].replace(/:\d+$/, '').replace(/^www\./, '').replace(/\.$/, '');
    return DOMAIN_RE.test(s) ? s : null;
  }

  function parseList(text) {
    const valid = [];
    const invalid = [];
    const seen = new Set();
    for (const line of String(text || '').split(/[\n,]+/)) {
      if (!line.trim()) continue;
      const d = normalizeDomain(line);
      if (!d) invalid.push(line.trim());
      else if (!seen.has(d)) { seen.add(d); valid.push(d); }
    }
    return { valid, invalid };
  }

  // True if host is the listed domain or a subdomain of it.
  function hostMatches(host, domain) {
    const h = String(host || '').toLowerCase();
    return h === domain || h.endsWith('.' + domain);
  }

  function inList(host, list) {
    return (list || []).some((d) => hostMatches(host, d));
  }

  // Builds declarativeNetRequest rules. Precedence: allow > block > warn.
  // Block and warn both redirect top-level navigations to the extension's
  // interstitial, carrying the original URL in the fragment.
  function buildRules(lists, warnPageUrl) {
    const allow = (lists && lists.allow) || [];
    const block = (lists && lists.block) || [];
    const warn = ((lists && lists.warn) || []).filter((d) => !block.includes(d));
    const rules = [];
    let id = 1;
    if (allow.length) {
      rules.push({
        id: id++,
        priority: 2,
        action: { type: 'allow' },
        condition: { requestDomains: allow, resourceTypes: ['main_frame'] },
      });
    }
    const add = (domain, mode) => rules.push({
      id: id++,
      priority: 1,
      action: {
        type: 'redirect',
        redirect: { regexSubstitution: warnPageUrl + '?mode=' + mode + '#\\0' },
      },
      condition: {
        regexFilter: '^https?://([a-z0-9-]+\\.)*' + domain.replace(/\./g, '\\.') + '(:[0-9]+)?(/.*)?$',
        resourceTypes: ['main_frame'],
      },
    });
    block.forEach((d) => add(d, 'block'));
    warn.forEach((d) => add(d, 'warn'));
    return rules;
  }

  const api = { normalizeDomain, parseList, hostMatches, inList, buildRules, MAX_PER_LIST };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.OCFDomains = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
