// Shared scoring vectors: the JavaScript scorer is the reference, and the
// Kotlin port (android/core) is tested against the same file.
// Regenerate with: node test/vectors.js --write
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { scoreEmotion, urgencySignals, describeCategories, classify, isAiLabel } = require('../core/scoring.js');

const FILE = path.resolve(__dirname, '../../shared/scoring-vectors.tsv');
const TEXTS = [
  'The city council met on Tuesday to discuss the annual budget and road repairs.',
  'SHOCKING betrayal: corrupt officials DESTROY town in terrifying disaster!!!',
  'Flood disaster hits coastal town today',
  'Total disaster',
  'She wore a hat and chatted about the evening weather with friends.',
  'Hurry! Only 3 left in stock. 12 people are viewing this.',
  'Delivery takes three to five working days.',
  "You won't believe what happened next. It is INSANE and jaw-dropping!",
  'Experts warn the crisis could collapse into chaos, a nightmare for families. Panic is spreading.',
  'BREAKING NEWS FROM THE CAPITAL TODAY',
  'Sale ends tonight! Act now, this deal is selling fast.',
  'The traitor\'s shameless hypocrisy disgusted voters, who called the scandal an outrage.',
  'Wow! Great! Nice! Really good work on this one!',
  'Congratulations! You have been selected. Click here to claim your prize.',
  'Your account has been suspended. Verify your identity now to avoid closure.',
  'Earn $500 per day working from home, no experience needed. DM me to start.',
  'Double your bitcoin today: send 0.1 BTC and get 0.2 back. Crypto giveaway!',
  'Your parcel is on hold. Pay a small redelivery fee to release it.',
  'Unusual sign-in detected. Final notice: urgent action required.',
  'Pay with gift cards only. Buy three gift cards and send the codes.',
  'Guaranteed returns of 40% a month. A risk-free investment opportunity, act now.',
  'Congratulations to the class of 2026 on their graduation day.',
  'Please verify your email address to finish creating your account.',
  'Hot singles in your area want to chat. 18+ only, uncensored live cams.',
  'Watch free porn videos and XXX clips, NSFW.',
  'The gallery added a nude study by a Renaissance painter to its collection.',
  'Sex education classes begin next term for year nine pupils.',
  'Follow for more! Link in bio 👆 like and share',
  'STOP SCROLLING. Doctors hate this one weird trick',
  'Comment "GUIDE" and I will send you the link',
  'Share before it gets deleted. They don\'t want you to know this.',
  'I made $4,200 in 3 days with this forex signals group. DM me to start 💰',
  'Free robux giveaway, link in bio to claim',
  'My sugar daddy pays my rent 💸 inbox me',
  'New spicy content just dropped 🔞 link in bio',
  'Recover your lost crypto with our certified experts, 100% guaranteed',
  'Beach day with the family, link in bio for the full album',
  'Watch until the end to see how the cake turned out',
  'AI-generated',
  'Creator labeled as AI-generated',
  'Altered or synthetic content',
  'AI info',
  'Experts say AI-generated images are getting harder to spot.',
  'A long and careful report on river levels found one alarming reading among several hundred ordinary measurements taken over the spring months by local volunteers.',
];

function render() {
  return TEXTS.map((t) => {
    const r = scoreEmotion(t);
    const c = classify(t);
    return [r.tier, r.score, r.hits.length, urgencySignals(t).map((s) => s.id).join(','),
      describeCategories(r.hits), c.kind || '-', c.reason || '-', isAiLabel(t) ? 'ai' : '-', t].join('\t');
  }).join('\n') + '\n';
}

if (require.main === module && process.argv.includes('--write')) {
  fs.mkdirSync(path.dirname(FILE), { recursive: true });
  fs.writeFileSync(FILE, render());
  console.log('wrote ' + FILE);
}
module.exports = { FILE, render };
