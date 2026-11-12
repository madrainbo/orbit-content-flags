// Content script: scores visible text on-device, reads labels the page already
// declares, and reports "draining design" signals. Page content is never sent
// anywhere; the only requests are re-reads of images already on the page.
(async function () {
  'use strict';
  const { urgencySignals, describeCategories, classify, isAiLabel } = globalThis.OCFScoring;
  const { extractClaimReviews, extractIntent } = globalThis.OCFLabels;
  const stored = await chrome.storage.sync.get({ settings: {} });
  const settings = { enabled: true, showMedium: false, readImageLabels: true, ...stored.settings };
  // What to do with flagged content: 'hide' (until Show is pressed), 'blur' or 'label'.
  if (!['hide', 'blur', 'label'].includes(settings.highAction)) {
    settings.highAction = settings.blurHigh ? 'blur' : 'hide';
  }
  if (!settings.enabled) return;

  const MAX_BLOCKS = 600;
  const MAX_IMAGES = 20;
  const MAX_AI_LABELS = 20;
  const SELECTOR = 'h1, h2, h3, p, [role="heading"]';
  const GROUPS = ['Origin', 'Fact-checks', 'Intent', 'Scam and adult', 'Emotional charge', 'Design'];
  const seen = new WeakSet();
  const state = {
    counts: { charge: 0, scam: 0, adult: 0, medium: 0 },
    hidden: 0, scanned: 0, images: 0, aiLabels: 0, signals: new Map(),
  };
  let host = null;
  let dismissed = false;

  // group: one of GROUPS. el (optional): element to scroll to from the panel.
  function addSignal(group, id, label, why, el, url) {
    if (state.signals.has(id)) return;
    state.signals.set(id, { group, label, why, el, url });
    renderPanel();
  }

  // Results of a claim lookup the user asked for from the context menu.
  chrome.runtime.onMessage.addListener((msg) => {
    if (!msg || msg.type !== 'claimResults') return;
    for (const id of [...state.signals.keys()]) if (id.startsWith('lookup-')) state.signals.delete(id);
    dismissed = false;
    const q = '\u201c' + msg.query.slice(0, 80) + (msg.query.length > 80 ? '\u2026' : '') + '\u201d';
    if (msg.error) {
      addSignal('Fact-checks', 'lookup-error', 'Lookup for ' + q + ':', msg.error);
    } else if (!msg.results.length) {
      addSignal('Fact-checks', 'lookup-none', 'No published fact-check found for ' + q + '.',
        'That does not mean the claim is true.');
    } else {
      msg.results.forEach((r, i) => {
        const who = r.publisher ? ' by ' + r.publisher : '';
        addSignal('Fact-checks', 'lookup-' + i, 'Looked up: \u201c' + r.claim.slice(0, 140) + '\u201d',
          (r.rating ? 'Rated \u201c' + r.rating + '\u201d' : 'Reviewed') + who + '.', null, r.url);
      });
    }
    renderPanel();
  });

  // ---------- per-passage flags: adult, scam-style, emotional charge ----------

  // Replaces el with a one-line notice and a Show button. Nothing is removed
  // from the page: pressing Show puts the element back exactly as it was.
  function cover(el, reason, onShow) {
    const box = document.createElement('div');
    box.className = 'ocf-cover';
    const text = document.createElement('span');
    text.textContent = reason;
    const show = document.createElement('button');
    show.type = 'button';
    show.textContent = 'Show';
    show.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      box.remove();
      el.classList.remove('ocf-hidden');
      state.hidden -= 1;
      if (onShow) onShow();
      renderPanel();
    });
    box.append(text, show);
    el.before(box);
    el.classList.add('ocf-hidden');
    state.hidden += 1;
  }

  const CHIP_TEXT = { charge: 'high charge', scam: 'scam-style', adult: 'adult', medium: 'charged' };

  // f: { kind, reason } where kind is charge, scam, adult or medium.
  function flag(el, f) {
    const level = f.kind === 'medium' ? 'medium' : 'high';
    const chip = document.createElement('span');
    chip.className = 'ocf-chip ocf-' + level;
    chip.textContent = CHIP_TEXT[f.kind];
    chip.title = f.reason.charAt(0).toUpperCase() + f.reason.slice(1) +
      '. Word-list heuristic, not a verdict. Click to dispute and remove.';
    chip.setAttribute('role', 'button');
    chip.tabIndex = 0;
    const action = level === 'high' ? settings.highAction : 'label';
    const dispute = async (e) => {
      e.preventDefault();
      e.stopPropagation();
      chip.remove();
      el.classList.remove('ocf-blur');
      state.counts[f.kind] -= 1;
      renderPanel();
      const { disputes = 0 } = await chrome.storage.local.get('disputes');
      chrome.storage.local.set({ disputes: disputes + 1 });
    };
    chip.addEventListener('click', dispute);
    chip.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') dispute(e); });
    state.counts[f.kind] += 1;
    if (action === 'hide') {
      cover(el, 'Hidden: ' + f.reason + '.', () => el.appendChild(chip));
    } else {
      if (action === 'blur') el.classList.add('ocf-blur');
      el.appendChild(chip);
    }
  }

  function scan(root) {
    const nodes = root.matches && root.matches(SELECTOR) ? [root] : [];
    if (root.querySelectorAll) nodes.push(...root.querySelectorAll(SELECTOR));
    for (const el of nodes) {
      if (state.scanned >= MAX_BLOCKS) break;
      if (seen.has(el) || el.closest('.ocf-chip, .ocf-cover') || el.querySelector('.ocf-cover')) continue;
      seen.add(el);
      const text = (el.innerText || '').trim().slice(0, 2000);
      if (text.length < 20) continue;
      state.scanned += 1;
      const c = classify(text);
      if (c.kind) {
        flag(el, c);
      } else if (c.emotion.tier === 'medium' && settings.showMedium) {
        flag(el, {
          kind: 'medium',
          reason: 'emotional charge ' + c.emotion.score + '/100 (' + describeCategories(c.emotion.hits) + ')',
        });
      }
    }
    scanAiLabels(root);
    if (settings.readImageLabels) scanImages(root);
  }

  // ---------- declared labels: "AI-generated" badges shown by the site ----------

  // Finds short text that is, in full, an AI label (for example "AI info").
  // If it sits inside a post, the post is treated as labelled AI-generated.
  function scanAiLabels(root) {
    if (state.aiLabels >= MAX_AI_LABELS || !root.isConnected) return;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    let node;
    while ((node = walker.nextNode()) && state.aiLabels < MAX_AI_LABELS) {
      const value = node.nodeValue;
      if (value.length < 5 || value.length > 80 || !isAiLabel(value)) continue;
      const el = node.parentElement;
      if (!el || seen.has(el) || el.closest('.ocf-chip, .ocf-cover, script, style, #ocf-panel-host')) continue;
      seen.add(el);
      state.aiLabels += 1;
      const post = el.closest('article, [role="article"]');
      addSignal('Origin', 'ailabel-' + state.aiLabels,
        'Labelled “' + value.trim() + '” by the site.', '', post || el);
      if (post && settings.highAction === 'hide' && !post.classList.contains('ocf-hidden')) {
        cover(post, 'Hidden: post labelled AI-generated.');
      }
    }
  }

  // ---------- declared labels: images ----------

  async function checkImage(img) {
    const url = img.currentSrc || img.src;
    if (!/^https?:/i.test(url)) return;
    let res;
    try { res = await chrome.runtime.sendMessage({ type: 'imageLabels', url }); } catch { return; }
    if (!res || !res.ok) return;
    if (res.label) {
      img.classList.add('ocf-img-flag');
      img.title = res.label + ' (image metadata, unverified)' + (img.title ? ' · ' + img.title : '');
      if (settings.highAction === 'hide') cover(img, 'Hidden: ' + res.label.toLowerCase() + ' image.');
      addSignal('Origin', 'img-' + url, res.label + ' image.',
        'Read from the file’s metadata; not verified.', img);
    } else if (res.hasContentCredentials) {
      addSignal('Origin', 'c2pa-' + url, 'Image carries Content Credentials.',
        'Signature not checked by this version.', img);
    }
  }

  function scanImages(root) {
    const imgs = root.tagName === 'IMG' ? [root] : root.querySelectorAll ? root.querySelectorAll('img') : [];
    for (const img of imgs) {
      if (state.images >= MAX_IMAGES) return;
      if (seen.has(img)) continue;
      const ready = () => {
        if (seen.has(img) || state.images >= MAX_IMAGES) return;
        if (img.naturalWidth < 200 || img.naturalHeight < 200) return;
        seen.add(img);
        state.images += 1;
        checkImage(img);
      };
      if (img.complete) ready(); else img.addEventListener('load', ready, { once: true });
    }
  }

  // ---------- declared labels: page markup ----------

  function readMarkup() {
    const docs = [];
    for (const s of document.querySelectorAll('script[type="application/ld+json"]')) {
      try { docs.push(JSON.parse(s.textContent)); } catch { /* malformed markup: skip */ }
    }
    extractClaimReviews(docs).slice(0, 5).forEach((r, i) => {
      const who = r.publisher ? ' by ' + r.publisher : '';
      const verdict = r.rating ? 'Rated “' + r.rating + '”' + who + '.' : 'Reviewed' + who + '.';
      addSignal('Fact-checks', 'claim-' + i, '“' + r.claim.slice(0, 140) + '”', verdict);
    });
    extractIntent(docs).forEach((label) => {
      addSignal('Intent', 'intent-' + label, label + '.', 'Declared by the publisher.');
    });
    const sponsored = document.querySelectorAll('a[rel~="sponsored"]');
    if (sponsored.length) {
      addSignal('Intent', 'sponsored-links',
        sponsored.length + ' sponsored link' + (sponsored.length === 1 ? '' : 's') + '.',
        'Marked rel="sponsored" by the publisher.', sponsored[0]);
    }
  }

  // ---------- draining design ----------

  function checkAutoplay() {
    for (const v of document.querySelectorAll('video')) {
      const playingUnasked = !v.paused && !(navigator.userActivation && navigator.userActivation.hasBeenActive);
      if (v.autoplay || playingUnasked) {
        addSignal('Design', 'autoplay', 'Autoplay video.', 'A video starts without you pressing play.', v);
        return;
      }
    }
  }

  function checkUrgency() {
    const text = (document.body.innerText || '').slice(0, 200000);
    for (const s of urgencySignals(text)) {
      addSignal('Design', 'urgency-' + s.id, 'Pressure wording.', 'Found “' + s.match + '”.');
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
        addSignal('Design', 'countdown', 'Countdown timer.', 'A running timer appears next to offer wording.');
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
        addSignal('Design', 'infinite-scroll', 'Infinite scroll.', 'The page keeps loading more as you reach the end.');
      }
    }
  }

  // ---------- page panel (one badge for every label type) ----------

  function renderPanel() {
    if (dismissed) return;
    const n = state.counts;
    const total = n.charge + n.scam + n.adult + n.medium + state.signals.size;
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
      'border-radius:8px;box-shadow:0 2px 10px rgba(0,0,0,.18);padding:8px 10px;max-width:300px;' +
      'max-height:60vh;overflow:auto}' +
      '@media (prefers-color-scheme:dark){.box{background:#202124;color:#ececec;border-color:#4a4d51}}' +
      '.head{display:flex;justify-content:space-between;gap:10px;font-weight:600}' +
      'h3{font:600 11px/1.4 system-ui,sans-serif;text-transform:uppercase;letter-spacing:.04em;' +
      'opacity:.7;margin:8px 0 2px}' +
      'ul{margin:0;padding-left:16px}li{margin:2px 0;overflow-wrap:anywhere}small{opacity:.75}' +
      'button{all:unset;cursor:pointer;padding:0 4px}' +
      'button.close{font-size:15px;line-height:1}button.show{text-decoration:underline;font-size:12px}' +
      'a{color:inherit;font-size:12px}' +
      'button:focus-visible,a:focus-visible{outline:2px solid currentColor;border-radius:3px}';
    const box = document.createElement('div');
    box.className = 'box';
    const head = document.createElement('div');
    head.className = 'head';
    const title = document.createElement('span');
    title.textContent = 'Content flags on this page';
    const close = document.createElement('button');
    close.className = 'close';
    close.textContent = '×';
    close.setAttribute('aria-label', 'Dismiss content flags');
    close.addEventListener('click', () => { dismissed = true; host.remove(); host = null; });
    head.append(title, close);
    box.appendChild(head);
    if (state.hidden > 0) {
      const all = document.createElement('button');
      all.className = 'show';
      all.textContent = 'Show all hidden (' + state.hidden + ')';
      all.addEventListener('click', () => {
        document.querySelectorAll('.ocf-cover > button').forEach((b) => b.click());
      });
      box.appendChild(all);
    }

    const byGroup = new Map(GROUPS.map((g) => [g, []]));
    const count = (group, k, name) => {
      if (n[k]) byGroup.get(group).push({ label: n[k] + ' ' + name + ' passage' + (n[k] === 1 ? '' : 's') + '.' });
    };
    count('Scam and adult', 'scam', 'scam-style');
    count('Scam and adult', 'adult', 'adult-content');
    count('Emotional charge', 'charge', 'high-charge');
    count('Emotional charge', 'medium', 'medium-charge');
    for (const s of state.signals.values()) byGroup.get(s.group).push(s);

    for (const [group, items] of byGroup) {
      if (!items.length) continue;
      const h = document.createElement('h3');
      h.textContent = group;
      const list = document.createElement('ul');
      for (const it of items) {
        const li = document.createElement('li');
        li.textContent = it.label;
        if (it.why) {
          const s = document.createElement('small');
          s.textContent = ' ' + it.why;
          li.appendChild(s);
        }
        if (it.url) {
          const a = document.createElement('a');
          a.href = it.url;
          a.target = '_blank';
          a.rel = 'noopener noreferrer';
          a.textContent = 'Open';
          li.append(' ', a);
        }
        if (it.el && it.el.isConnected) {
          const b = document.createElement('button');
          b.className = 'show';
          b.textContent = 'Show';
          b.addEventListener('click', () => it.el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
          li.append(' ', b);
        }
        list.appendChild(li);
      }
      box.append(h, list);
    }
    root.append(style, box);
  }

  // ---------- run ----------

  scan(document.body);
  readMarkup();
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
        if (n.nodeType === 1 && n.id !== 'ocf-panel-host' && !n.classList.contains('ocf-chip') &&
            !n.classList.contains('ocf-cover')) pending.push(n);
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
