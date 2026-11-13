// Prints the current rule-based verdict for each passage given as a JSON array on stdin.
const { classify } = require('../../content-filter-extension/core/scoring.js');
const texts = JSON.parse(require('node:fs').readFileSync(0, 'utf8'));
const name = { hook: 'bait' };
console.log(JSON.stringify(texts.map((t) => { const k = classify(t).kind; return k ? (name[k] || k) : 'benign'; })));
