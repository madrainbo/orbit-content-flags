// Content script: scores visible text on-device and reports page-level
// "draining design" signals. Nothing read here leaves the page.
(async function () {
  'use strict';
  const { scoreEmotion, urgencySignals } = globalThis.OCFScoring;
  const stored = await chrome.storage.sync.get({ settings: {} });
  const settings = { enabled: true, showMedium: false, blurHigh: false, ...stored.settings };
  if (!settings.enabled) return;

  const MAX_BLOCKS = 600;
  const SELECTOR = 'h1, h2, h3, p, [role="heading"]';
  const seen = new WeakSet();
  const state = { high: 0, medium: 0, scanned: 0, signals: new Map() };

  // ---------- emotional charge ----------

  function describe(result) {
    const terms = [...new Set(result.hits.map((h) => h.term))].slice(0, 6);
    const parts = [];
    if (terms.length) parts.push('charged words: ' + terms.join(', '));
    if (result.capsRatio >= 0.15) parts.push('heavy capitals');
    if (result.exclaimRate >= 0.5) parts.push('exclamation marks');
    return parts.join('; ');
  }

  function flag(el, result) {
    const chip = document.createElement('span');
    chip.className = 'ocf-chip ocf-' + result.tier;
    chip.textContent = result.tier === 'high' ? 'high charge' : 'charged';
    chip.title = 'Emotional charge ' + result.score + '/100 (' + describe(result) +
      '). Word-list heuristic, not a verdict. Click to dispute and remove.';
    chip.setAttribute('role', 'button');
    chip.tabIndex = 0;
    const blurred = settings.blurHigh && result.tier === 'high';
    if (blurred) el.classList.add('ocf-blur');
    const dispute = async (e) => {
      e.preventDefault();
      e.stopPropagation();
      chip.remove();
      el.classList.remove('ocf-blur');
      state[result.tier] -= 1;
      renderPanel();
      const { disputes = 0 } = await chrome.storage.local.get('disputes');
      chrome.storage.local.set({ disputes: disputes + 1 });
    };
    chip.addEventListener('click', dispute);
    chip.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') dispute(e); });
    el.appendChild(chip);
  }

  function scan(root) {
    const nodes = root.matches && root.matches(SELECTOR) ? [root] : [];
    if (root.querySelectorAll) nodes.push(...root.querySelectorAll(SELECTOR));
    for (const el of nodes) {
      if (state.scanned >= MAX_BLOCKS) return;
      if (seen.has(el) || el.closest('.ocf-chip')) continue;
      seen.add(el);
      const text = (el.innerText || '').trim().slice(0, 2000);
      if (text.length < 20) continue;
      state.scanned += 1;
      const result = scoreEmotion(text);
      if (result.tier === 'high' || (result.tier === 'medium' && settings.showMedium)) {
        state[result.tier] += 1;
        flag(el, result);
      }
    }
  }

  // ---------- draining design ----------

  function addSignal(id, label, why) {
    if (state.signals.has(id)) return;
    state.signals.set(id, { label, why });
    renderPanel();
  }

  function checkAutoplay() {
    for (const v of document.querySelectorAll('video')) {
      const playingUnasked = !v.paused && !(navigator.userActivation && navigator.userActivation.hasBeenActive);
      if (v.autoplay || playingUnasked) {
        addSignal('autoplay', 'Autoplay video', 'A video starts without you pressing play.');
        return;
      }
    }
  }

  function checkUrgency() {
    const text = (document.body.innerText || '').slice(0, 200000);
    for (const s of urgencySignals(text)) {
      addSignal('urgency-' + s.id, 'Pressure wording', 'Found “' + s.match + '”.');
    }
    return text;
  }

  const TIMER_RE = /\b\d{1,2}:\d{2}:\d{2}\b/g;
  function checkCountdown(firstText) {
    const before = (firstText.match(TIMER_RE) || []).join('|');
    if (!before) return;
    setTimeout(() => {
      const nowText = (document.body.innerText || '').slice(0, 200000);
      const after = (nowText.match(TIMER_RE) || []).join('|');
      if (after && after !== before && /\b(?:offer|sale|deal|discount|ends|left|remaining)\b/i.test(nowText)) {
        addSignal('countdown', 'Countdown timer', 'A running timer appears next to offer wording.');
      }
    }, 2500);
  }

  // Infinite scroll: the page grows three or more times while you are near the bottom.
  let lastHeight = document.documentElement.scrollHeight;
  let growths = 0;
  function onScroll() {
    const doc = document.documentElement;
    const nearBottom = window.scrollY + window.innerHeight >= lastHeight - window.innerHeight * 1.5;
    if (doc.scrollHeight > lastHeight + 200) {
      if (nearBottom) growths += 1;
      lastHeight = doc.scrollHeight;
      if (growths >= 3) {
        addSignal('infinite-scroll', 'Infinite scroll', 'The page keeps loading more as you reach the end.');
      }
    }
  }

  // ---------- page panel ----------

  let host = null;
  let dismissed = false;
  function renderPanel() {
    if (dismissed) return;
    const total = state.high + state.medium + state.signals.size;
    if (!total) { if (host) host.remove(); host = null; return; }
    if (!host) {
      host = document.createElement('div');
      host.id = 'ocf-panel-host';
      host.style.cssText = 'all:initial;position:fixed;right:12px;bottom:12px;z-index:2147483647;';
      host.attachShadow({ mode: 'open' });
      document.documentElement.appendChild(host);
    }
    const root = host.shadowRoot;
    root.textContent = '';
    const style = document.createElement('style');
    style.textContent =
      ':host{color-scheme:light dark}' +
      '.box{font:13px/1.45 system-ui,sans-serif;background:#fff;color:#1a1a1a;border:1px solid #c9cdd2;' +
      'border-radius:8px;box-shadow:0 2px 10px rgba(0,0,0,.18);padding:8px 10px;max-width:280px}' +
      '@media (prefers-color-scheme:dark){.box{background:#202124;color:#ececec;border-color:#4a4d51}}' +
      '.head{display:flex;justify-content:space-between;gap:10px;font-weight:600}' +
      'ul{margin:6px 0 0;padding-left:16px}li{margin:2px 0}small{opacity:.75}' +
      'button{all:unset;cursor:pointer;padding:0 4px;font-size:15px;line-height:1}' +
      'button:focus-visible{outline:2px solid currentColor;border-radius:3px}';
    const box = document.createElement('div');
    box.className = 'box';
    const head = document.createElement('div');
    head.className = 'head';
    const title = document.createElement('span');
    title.textContent = 'Content flags on this page';
    const close = document.createElement('button');
    close.textContent = '×';
    close.setAttribute('aria-label', 'Dismiss content flags');
    close.addEventListener('click', () => { dismissed = true; host.remove(); host = null; });
    head.append(title, close);
    const list = document.createElement('ul');
    const item = (label, why) => {
      const li = document.createElement('li');
      li.textContent = label;
      if (why) {
        const s = document.createElement('small');
        s.textContent = ' ' + why;
        li.appendChild(s);
      }
      list.appendChild(li);
    };
    if (state.high) item(state.high + ' high-charge passage' + (state.high === 1 ? '' : 's'));
    if (state.medium) item(state.medium + ' medium-charge passage' + (state.medium === 1 ? '' : 's'));
    for (const s of state.signals.values()) item(s.label + '.', s.why);
    box.append(head, list);
    root.append(style, box);
  }

  // ---------- run ----------

  scan(document.body);
  renderPanel();
  const firstText = checkUrgency();
  checkCountdown(firstText);
  checkAutoplay();
  setTimeout(checkAutoplay, 3000);
  window.addEventListener('scroll', onScroll, { passive: true });

  let pending = [];
  let timer = null;
  new MutationObserver((mutations) => {
    for (const m of mutations) {
      for (const n of m.addedNodes) {
        if (n.nodeType === 1 && n.id !== 'ocf-panel-host' && !n.classList.contains('ocf-chip')) pending.push(n);
      }
    }
    if (pending.length && !timer) {
      timer = setTimeout(() => {
        const batch = pending; pending = []; timer = null;
        batch.forEach((n) => { if (n.isConnected) scan(n); });
        renderPanel();
      }, 400);
    }
  }).observe(document.body, { childList: true, subtree: true });
})();
