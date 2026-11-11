// Label readers: pure functions that read labels a publisher or tool has
// already declared. They report what is declared; they do not infer anything.
(function (root) {
  'use strict';

  // IPTC Digital Source Type terms (cv.iptc.org/newscodes/digitalsourcetype).
  // Only terms that declare synthetic or partly synthetic media produce a label.
  const SOURCE_TYPES = {
    trainedAlgorithmicMedia: { origin: 'ai-generated', label: 'Declared AI-generated' },
    compositeWithTrainedAlgorithmicMedia: { origin: 'ai-edited', label: 'Declared partly AI-generated' },
    compositeSynthetic: { origin: 'ai-edited', label: 'Declared composite with synthetic elements' },
    algorithmicMedia: { origin: 'algorithmic', label: 'Declared algorithmically generated' },
  };

  // `bytes` is the start of an image file decoded as latin1. XMP packets and
  // C2PA manifests both carry the source type as a plain URI string, so a
  // string search covers JPEG, PNG and WebP without a per-format parser.
  function readImageLabels(bytes) {
    const s = String(bytes || '');
    const m = s.match(/digitalsourcetype\/([A-Za-z]{3,60})/);
    const term = m ? m[1] : null;
    const known = term && Object.prototype.hasOwnProperty.call(SOURCE_TYPES, term) ? SOURCE_TYPES[term] : null;
    return {
      sourceType: term,
      origin: known ? known.origin : null,
      label: known ? known.label : null,
      // A C2PA manifest store is a JUMBF box labelled "c2pa". Presence only:
      // the signature is not validated here.
      hasContentCredentials: s.includes('jumb') && s.includes('c2pa'),
    };
  }

  function typesOf(node) {
    const t = node && node['@type'];
    return Array.isArray(t) ? t : t ? [t] : [];
  }

  // Flattens JSON-LD documents (arrays, @graph, nested objects) into nodes.
  function walk(doc, out, depth) {
    if (!doc || typeof doc !== 'object' || depth > 6 || out.length > 500) return;
    if (Array.isArray(doc)) { doc.forEach((d) => walk(d, out, depth + 1)); return; }
    out.push(doc);
    for (const v of Object.values(doc)) if (v && typeof v === 'object') walk(v, out, depth + 1);
  }

  const text = (v) => (typeof v === 'string' ? v.trim().slice(0, 300) : '');
  const nameOf = (v) => (v && typeof v === 'object' ? text(v.name) : text(v));

  // schema.org ClaimReview: a fact-checker's own markup on its article.
  function extractClaimReviews(docs) {
    const nodes = [];
    walk(docs, nodes, 0);
    const out = [];
    for (const n of nodes) {
      if (!typesOf(n).includes('ClaimReview')) continue;
      const rating = n.reviewRating || {};
      const claim = text(n.claimReviewed);
      if (!claim) continue;
      out.push({
        claim,
        rating: text(rating.alternateName) || text(rating.name) || '',
        publisher: nameOf(n.author) || nameOf(n.publisher) || '',
      });
    }
    return out;
  }

  const INTENT_TYPES = {
    OpinionNewsArticle: 'Marked as opinion',
    SatiricalArticle: 'Marked as satire',
    AdvertiserContentArticle: 'Marked as advertiser content',
  };

  // Article types the publisher has declared in schema.org markup.
  function extractIntent(docs) {
    const nodes = [];
    walk(docs, nodes, 0);
    const found = new Set();
    for (const n of nodes) {
      for (const t of typesOf(n)) {
        if (Object.prototype.hasOwnProperty.call(INTENT_TYPES, t)) found.add(INTENT_TYPES[t]);
      }
    }
    return [...found];
  }

  const api = { readImageLabels, extractClaimReviews, extractIntent, SOURCE_TYPES, INTENT_TYPES };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.OCFLabels = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
