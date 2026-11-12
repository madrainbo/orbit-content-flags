// Generates the JavaScript and Kotlin pattern tables from shared/patterns.json
// so the extension and the Android app cannot drift apart.
// Run: node test/gen-patterns.js --write
'use strict';
const fs = require('node:fs');
const path = require('node:path');

const SRC = path.resolve(__dirname, '../../shared/patterns.json');
const JS_OUT = path.resolve(__dirname, '../core/patterns.js');
const KT_OUT = path.resolve(__dirname, '../../android/core/src/main/kotlin/dev/orbitflags/core/Patterns.kt');

function render() {
  const p = JSON.parse(fs.readFileSync(SRC, 'utf8'));
  for (const list of [p.scam, p.adult]) for (const e of list) new RegExp(e.re, 'i'); // must compile
  new RegExp(p.aiLabel, 'i');

  const jsList = (list) => list.map((e) => `    [${JSON.stringify(e.re)}, ${e.w}],`).join('\n');
  const js = `// GENERATED from shared/patterns.json by test/gen-patterns.js. Do not edit.
(function (root) {
  'use strict';
  const compile = (list) => list.map(([re, w]) => ({ re: new RegExp(re, 'i'), w }));
  const api = {
    THRESHOLD: ${p.threshold},
    SCAM: compile([
${jsList(p.scam)}
    ]),
    ADULT: compile([
${jsList(p.adult)}
    ]),
    AI_LABEL: new RegExp(${JSON.stringify(p.aiLabel)}, 'i'),
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.OCFPatterns = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
`;
  // Kotlin raw strings: only "$" needs care.
  const kt = (s) => '"""' + s.replace(/\$/g, () => "${'$'}") + '"""';
  const ktList = (list) => list.map((e) => `        P(${kt(e.re)}, ${e.w}),`).join('\n');
  const kotlin = `// GENERATED from shared/patterns.json by content-filter-extension/test/gen-patterns.js. Do not edit.
package dev.orbitflags.core

object Patterns {
    class Entry(val re: Regex, val w: Int)

    private fun P(re: String, w: Int) = Entry(Regex(re, RegexOption.IGNORE_CASE), w)

    const val THRESHOLD = ${p.threshold}

    val SCAM = listOf(
${ktList(p.scam)}
    )

    val ADULT = listOf(
${ktList(p.adult)}
    )

    val AI_LABEL = Regex(${kt(p.aiLabel)}, RegexOption.IGNORE_CASE)
}
`;
  return { js, kotlin };
}

if (require.main === module && process.argv.includes('--write')) {
  const { js, kotlin } = render();
  fs.writeFileSync(JS_OUT, js);
  fs.writeFileSync(KT_OUT, kotlin);
  console.log('wrote patterns.js and Patterns.kt');
}
module.exports = { render, JS_OUT, KT_OUT };
