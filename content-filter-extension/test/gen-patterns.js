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
  for (const list of [p.scam, p.adult, p.hook]) for (const e of list) new RegExp(e.re, 'i'); // must compile
  new RegExp(p.aiLabel, 'i');
  const stageIds = Object.keys(p.stages).filter((k) => !k.startsWith('_'));
  for (const id of stageIds) for (const re of p.stages[id].re) new RegExp(re, 'i');

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
    HOOK: compile([
${jsList(p.hook)}
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

    val HOOK = listOf(
${ktList(p.hook)}
    )

    val AI_LABEL = Regex(${kt(p.aiLabel)}, RegexOption.IGNORE_CASE)

    /** A stage of a long-running scam; [money] marks stages that involve money. */
    class Stage(val id: String, val label: String, val money: Boolean, val patterns: List<Regex>)

    private fun S(id: String, label: String, money: Boolean, vararg re: String) =
        Stage(id, label, money, re.map { Regex(it, RegexOption.IGNORE_CASE) })

    val STAGES = listOf(
${stageIds.map((id) => `        S(${JSON.stringify(id)}, ${JSON.stringify(p.stages[id].label)}, ${p.stages[id].money},
${p.stages[id].re.map((r) => '            ' + kt(r) + ',').join('\n')}
        ),`).join('\n')}
    )
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
