'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { scoreEmotion, urgencySignals, describeCategories } = require('../core/scoring.js');
const { normalizeDomain, parseList, inList, buildRules } = require('../core/domains.js');
const { readImageLabels, extractClaimReviews, extractIntent, parseClaimSearch } = require('../core/labels.js');

test('neutral text scores low', () => {
  const r = scoreEmotion('The city council met on Tuesday to discuss the annual budget and road repairs.');
  assert.equal(r.tier, 'low');
  assert.equal(r.hits.length, 0);
});

test('charged headline scores high', () => {
  const r = scoreEmotion('SHOCKING betrayal: corrupt officials DESTROY town in terrifying disaster!!!');
  assert.equal(r.tier, 'high');
  assert.ok(r.score >= 55);
});

test('a single charged word never reaches high', () => {
  const r = scoreEmotion('Flood disaster hits coastal town today');
  assert.equal(r.hits.length, 1);
  assert.equal(r.tier, 'medium');
});

test('very short text is insufficient', () => {
  assert.equal(scoreEmotion('Total disaster').tier, 'insufficient-text');
});

test('stems do not match unrelated words', () => {
  const r = scoreEmotion('She wore a hat and chatted about the evening weather with friends.');
  assert.equal(r.hits.length, 0);
});

test('urgency phrases are detected', () => {
  const ids = urgencySignals('Hurry! Only 3 left in stock. 12 people are viewing this.').map((s) => s.id);
  assert.deepEqual(ids.sort(), ['activity-notice', 'hurry', 'low-stock']);
  assert.deepEqual(urgencySignals('Delivery takes three to five working days.'), []);
});

test('normalizeDomain strips scheme, www, port and path', () => {
  assert.equal(normalizeDomain('https://www.Example.com:8080/a?b#c'), 'example.com');
  assert.equal(normalizeDomain('  news.example.co.uk  '), 'news.example.co.uk');
  assert.equal(normalizeDomain('localhost'), null);
  assert.equal(normalizeDomain('exa mple.com'), null);
  assert.equal(normalizeDomain('javascript:alert(1)'), null);
});

test('parseList dedupes and reports invalid lines', () => {
  const r = parseList('example.com\nwww.example.com\nnot a domain\n\nfoo.org');
  assert.deepEqual(r.valid, ['example.com', 'foo.org']);
  assert.deepEqual(r.invalid, ['not a domain']);
});

test('inList matches subdomains but not lookalikes', () => {
  assert.ok(inList('m.example.com', ['example.com']));
  assert.ok(!inList('notexample.com', ['example.com']));
});

test('buildRules applies allow > block > warn', () => {
  const rules = buildRules(
    { allow: ['ok.com'], block: ['bad.com'], warn: ['bad.com', 'meh.com'] },
    'chrome-extension://abc/warn.html'
  );
  assert.equal(rules.length, 3);
  assert.equal(rules[0].action.type, 'allow');
  assert.ok(rules[0].priority > rules[1].priority);
  assert.match(rules[1].action.redirect.regexSubstitution, /mode=block#\\0$/);
  assert.match(rules[2].action.redirect.regexSubstitution, /mode=warn#\\0$/);
  assert.equal(new Set(rules.map((r) => r.id)).size, 3);

  const re = new RegExp(rules[2].condition.regexFilter);
  assert.ok(re.test('https://meh.com/'));
  assert.ok(re.test('http://sub.meh.com:8080/path?q=1'));
  assert.ok(!re.test('https://notmeh.com/'));
  assert.ok(!re.test('https://meh.com.evil.org/'));
});

test('readImageLabels finds a declared AI source type in XMP', () => {
  const xmp = 'junk\x00<x:xmpmeta><rdf:Description Iptc4xmpExt:DigitalSourceType=' +
    '"http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"/></x:xmpmeta>';
  const r = readImageLabels(xmp);
  assert.equal(r.sourceType, 'trainedAlgorithmicMedia');
  assert.equal(r.origin, 'ai-generated');
  assert.equal(r.hasContentCredentials, false);
});

test('readImageLabels does not label camera captures or plain files', () => {
  const cam = readImageLabels('http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture');
  assert.equal(cam.sourceType, 'digitalCapture');
  assert.equal(cam.label, null);
  assert.deepEqual(readImageLabels('\x89PNG plain bytes'),
    { sourceType: null, origin: null, label: null, hasContentCredentials: false });
});

test('readImageLabels detects a C2PA manifest store', () => {
  assert.equal(readImageLabels('....jumb....jumdc2pa\x00....').hasContentCredentials, true);
  assert.equal(readImageLabels('a c2pa mention only').hasContentCredentials, false);
});

test('extractClaimReviews reads nested and @graph markup', () => {
  const docs = [{
    '@context': 'https://schema.org',
    '@graph': [
      { '@type': 'WebPage', name: 'x' },
      {
        '@type': ['ClaimReview'],
        claimReviewed: 'The moon is made of cheese',
        reviewRating: { '@type': 'Rating', alternateName: 'False' },
        author: { '@type': 'Organization', name: 'Example Checks' },
      },
    ],
  }, { '@type': 'ClaimReview' }, null, 'text'];
  assert.deepEqual(extractClaimReviews(docs), [
    { claim: 'The moon is made of cheese', rating: 'False', publisher: 'Example Checks' },
  ]);
});

test('extractIntent reports only declared article types', () => {
  assert.deepEqual(extractIntent([{ '@type': 'OpinionNewsArticle' }, { '@type': 'NewsArticle' }]), ['Marked as opinion']);
  assert.deepEqual(extractIntent([{ '@type': 'NewsArticle', name: 'SatiricalArticle' }]), []);
});

test('parseClaimSearch flattens reviews and drops unsafe links', () => {
  const json = { claims: [
    { text: 'Claim A', claimReview: [
      { publisher: { name: 'Checker', site: 'checker.org' }, url: 'https://checker.org/a', textualRating: 'False' },
      { publisher: { site: 'other.org' }, url: 'javascript:alert(1)', textualRating: 'Misleading' },
    ] },
    { text: 'Claim B' },
  ] };
  assert.deepEqual(parseClaimSearch(json), [
    { claim: 'Claim A', rating: 'False', publisher: 'Checker', url: 'https://checker.org/a' },
    { claim: 'Claim A', rating: 'Misleading', publisher: 'other.org', url: '' },
  ]);
  assert.deepEqual(parseClaimSearch({}), []);
  assert.deepEqual(parseClaimSearch(null), []);
});

test('shared scoring vectors are current', () => {
  const { FILE, render } = require('./vectors.js');
  assert.equal(require('node:fs').readFileSync(FILE, 'utf8'), render(),
    'run: node test/vectors.js --write');
});

test('describeCategories names the top two categories', () => {
  const h = (category) => ({ term: 'x', category });
  assert.equal(describeCategories([h('fear'), h('fear'), h('outrage'), h('bait')]), 'fear and outrage wording');
  assert.equal(describeCategories([h('bait')]), 'clickbait wording');
  assert.equal(describeCategories([h('fear'), h('outrage')]), 'outrage and fear wording');
  assert.equal(describeCategories([]), 'capitals and exclamation marks');
});
