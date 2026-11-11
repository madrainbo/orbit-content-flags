'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { scoreEmotion, urgencySignals } = require('../core/scoring.js');
const { normalizeDomain, parseList, inList, buildRules } = require('../core/domains.js');

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
