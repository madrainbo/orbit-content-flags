// Shared scoring core: pure functions, no DOM, no network.
// Loaded as a content script (attaches to globalThis) and by Node tests.
// Thresholds are documented in METHODOLOGY.md so they can be criticised.
(function (root) {
  'use strict';

  // Word stems matched as \b(stem)\w*. Kept short and explicit on purpose.
  const LEXICON = {
    outrage: [
      'outrag', 'furious', 'fury', 'infuriat', 'enrag', 'disgrac', 'shameful',
      'shameless', 'scandal', 'betray', 'corrupt', 'traitor', 'treason', 'vile',
      'evil', 'despicabl', 'disgust', 'appall', 'atrocious', 'atrocit', 'slam',
      'destroy', 'humiliat', 'hypocri', 'liar',
    ],
    fear: [
      'terrif', 'horrif', 'horror', 'nightmare', 'catastroph', 'disaster',
      'devastat', 'deadly', 'panic', 'threat', 'danger', 'crisis', 'collaps',
      'chaos', 'alarming', 'shocking',
    ],
    bait: [
      'unbelievabl', 'insane', 'jaw-dropping', 'mind-blowing', 'you won\'t believe',
      'what happened next',
    ],
  };

  const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const MATCHERS = Object.entries(LEXICON).map(([category, stems]) => ({
    category,
    re: new RegExp('\\b(?:' + stems.map(escapeRe).join('|') + ')[\\w\'-]*', 'gi'),
  }));

  const THRESHOLDS = {
    minTokens: 4,          // below this: insufficient-text
    densityCap: 0.12,      // charged terms per token at which the density part saturates
    capsCap: 0.3,          // share of 4+ letter words in ALL CAPS at which that part saturates
    exclaimCap: 1,         // exclamation marks per sentence at which that part saturates
    weights: { density: 70, caps: 15, exclaim: 15 },
    medium: 25,
    high: 55,
    minHitsForHigh: 2,     // a single charged word never yields "high"
  };

  function scoreEmotion(text) {
    const t = String(text || '');
    const tokens = t.match(/[A-Za-z][A-Za-z'-]*/g) || [];
    if (tokens.length < THRESHOLDS.minTokens) {
      return { tier: 'insufficient-text', score: 0, tokens: tokens.length, hits: [] };
    }
    const hits = [];
    for (const { category, re } of MATCHERS) {
      re.lastIndex = 0;
      let m;
      while ((m = re.exec(t)) !== null) hits.push({ term: m[0].toLowerCase(), category });
    }
    const longWords = tokens.filter((w) => w.length >= 4);
    const caps = longWords.filter((w) => w === w.toUpperCase()).length;
    const capsRatio = longWords.length ? caps / longWords.length : 0;
    const sentences = Math.max(1, t.split(/[.!?]+/).filter((s) => s.trim()).length);
    const exclaimRate = (t.match(/!/g) || []).length / sentences;
    const density = hits.length / tokens.length;

    const W = THRESHOLDS.weights;
    const score = Math.round(
      Math.min(density / THRESHOLDS.densityCap, 1) * W.density +
      Math.min(capsRatio / THRESHOLDS.capsCap, 1) * W.caps +
      Math.min(exclaimRate / THRESHOLDS.exclaimCap, 1) * W.exclaim
    );
    let tier = score >= THRESHOLDS.high ? 'high' : score >= THRESHOLDS.medium ? 'medium' : 'low';
    if (tier === 'high' && hits.length < THRESHOLDS.minHitsForHigh) tier = 'medium';
    return { tier, score, tokens: tokens.length, hits, capsRatio, exclaimRate };
  }

  // Pressure-selling phrases (subset of the "urgency" and "scarcity" dark-pattern
  // categories in Mathur et al. 2019, arXiv:1907.07032).
  const URGENCY = [
    { id: 'low-stock', re: /\bonly\s+\d+\s+(?:left|remaining)\b/i },
    { id: 'activity-notice', re: /\b\d+\s+(?:people|others|shoppers|customers)\s+(?:are\s+)?(?:viewing|looking|watching|bought)\b/i },
    { id: 'deadline', re: /\b(?:offer|sale|deal|discount|price)\s+ends\s+(?:in|soon|today|tonight)\b/i },
    { id: 'hurry', re: /\b(?:hurry|act now|don't miss out|last chance|selling fast)\b/i },
  ];

  function urgencySignals(text) {
    const t = String(text || '');
    const out = [];
    for (const { id, re } of URGENCY) {
      const m = t.match(re);
      if (m) out.push({ id, match: m[0] });
    }
    return out;
  }

  // Plain-language reason for a flag, e.g. "fear and outrage wording".
  const CATEGORY_NAMES = { outrage: 'outrage', fear: 'fear', bait: 'clickbait' };
  function describeCategories(hits) {
    const counts = Object.keys(CATEGORY_NAMES).map((c) => ({
      name: CATEGORY_NAMES[c],
      n: (hits || []).filter((h) => h.category === c).length,
    })).filter((c) => c.n > 0);
    if (!counts.length) return 'capitals and exclamation marks';
    counts.sort((a, b) => b.n - a.n); // stable: ties keep outrage, fear, clickbait order
    return counts.slice(0, 2).map((c) => c.name).join(' and ') + ' wording';
  }

  // Scam-style and adult wording, from the generated pattern tables.
  const P = (typeof module !== 'undefined' && module.exports) ? require('./patterns.js') : root.OCFPatterns;

  function matchPatterns(list, t) {
    let weight = 0;
    const matches = [];
    for (const { re, w } of list) {
      const m = t.match(re);
      if (m) { weight += w; matches.push(m[0].toLowerCase().trim()); }
    }
    return { weight, matches };
  }

  // Decides whether a passage should be flagged, and why. Adult wording wins
  // over scam-style wording, then high emotional charge, then engagement bait.
  function classify(text) {
    const t = String(text || '');
    const emotion = scoreEmotion(t);
    if (matchPatterns(P.ADULT, t).weight >= P.THRESHOLD) {
      return { kind: 'adult', reason: 'adult-content wording', emotion };
    }
    const scam = matchPatterns(P.SCAM, t);
    if (scam.weight >= P.THRESHOLD) {
      const quoted = scam.matches.slice(0, 2).map((m) => '\u201c' + m.slice(0, 40) + '\u201d').join(', ');
      return { kind: 'scam', reason: 'scam-style wording (' + quoted + ')', emotion };
    }
    if (emotion.tier === 'high') {
      return { kind: 'charge', reason: 'high emotional charge (' + describeCategories(emotion.hits) + ')', emotion };
    }
    const hook = matchPatterns(P.HOOK, t);
    if (hook.weight >= P.THRESHOLD) {
      const quoted = hook.matches.slice(0, 2).map((m) => '\u201c' + m.slice(0, 40) + '\u201d').join(', ');
      return { kind: 'hook', reason: 'engagement-bait wording (' + quoted + ')', emotion };
    }
    return { kind: null, reason: '', emotion };
  }

  // True when a short piece of text is, in full, an "AI-generated" label of
  // the kind platforms display. Deliberately exact: prose that merely
  // mentions AI-generated content does not match.
  function isAiLabel(text) {
    const t = String(text || '').trim();
    return t.length <= 60 && P.AI_LABEL.test(t);
  }

  const api = { scoreEmotion, urgencySignals, describeCategories, classify, isAiLabel, THRESHOLDS, LEXICON };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.OCFScoring = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
