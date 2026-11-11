# Orbit Content Flags (Chrome extension, v0.2)

Flags emotionally charged text, declared AI and fact-check labels, and draining design patterns as you browse, and warns or blocks on domains you list. Same stance as orbit-check: **signals, not verdicts**, with thresholds published in [METHODOLOGY.md](METHODOLOGY.md).

Everything is scored on the device and page content is never sent anywhere. The only requests the extension makes are re-reads of images already on the page, from their own sites and without cookies, to look for declared labels; this can be turned off. Only your settings and lists sync, through the browser's own account sync.

## What it does

- **Emotional charge:** labels headings and paragraphs that score "high" (optionally "medium"), with the reasons in the tooltip. Click a label to dispute and remove it.
- **Declared labels:** reads labels that already exist rather than guessing. Images whose metadata declares them AI-generated (IPTC Digital Source Type) are outlined; fact-check markup (schema.org ClaimReview), publisher-declared opinion, satire and advertiser content, and `rel="sponsored"` links are listed in the page panel.
- **Draining design:** the page panel also lists autoplay video, infinite scroll, pressure wording ("only 3 left") and countdown timers.
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

## Limits in v0.2

- English only; the emotion scorer is a word-list heuristic that has not been validated against labelled data.
- 300 domains per list (browser sync storage allows 8 KB per item).
- Declared labels are unverified: image metadata can be stripped or forged, and a missing label proves nothing.
- Content Credentials (C2PA) are detected but their signatures are not validated yet.
- Image labels are read from the first 256 KB of up to 20 images per page, so metadata stored late in a large file is missed.
- No fact-check lookup against external databases and no licensed domain reputation yet.
- Chrome and Edge only. The Safari build needs a Mac with Xcode and has not been started; Android is a later phase.
