# Orbit Content Flags (Chrome extension, v0.1)

Flags emotionally charged text and draining design patterns as you browse, and warns or blocks on domains you list. Same stance as orbit-check: **signals, not verdicts**, with thresholds published in [METHODOLOGY.md](METHODOLOGY.md).

Everything is scored on the device. Page content is never sent anywhere; the extension makes no network requests. Only your settings and lists sync, through the browser's own account sync.

## What it does

- **Emotional charge:** labels headings and paragraphs that score "high" (optionally "medium"), with the reasons in the tooltip. Click a label to dispute and remove it.
- **Draining design:** a small page panel lists autoplay video, infinite scroll, pressure wording ("only 3 left") and countdown timers.
- **Domain lists:** warn-before-opening, block, and always-allow lists. Allow beats block, block beats warn; subdomains are included.
- **Opt-in blur:** high-charge text can be blurred until hovered. Off by default: flag before filter.

## Install (unpacked)

1. Open `chrome://extensions` and turn on Developer mode.
2. Choose "Load unpacked" and select this folder.
3. Click the extension icon to open settings.

Requires Chrome or Edge 121+.

## Test

```bash
node --test test/core.test.js   # scoring and domain rules, no dependencies
node test/e2e.js                # real Chromium with the extension loaded; needs Playwright
```

## Limits in v0.1

- English only; the emotion scorer is a word-list heuristic that has not been validated against labelled data.
- 300 domains per list (browser sync storage allows 8 KB per item).
- No fake-content signals yet: provenance labels, fact-check matching and licensed domain reputation are the next phase.
- Chrome and Edge only; Safari and Android are later phases.
