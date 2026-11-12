# Orbit Content Flags (Chrome extension, v0.4)

Flags emotionally charged text, declared AI and fact-check labels, and draining design patterns as you browse, and warns or blocks on domains you list. Same stance as orbit-check: **signals, not verdicts**, with thresholds published in [METHODOLOGY.md](METHODOLOGY.md).

Everything is scored on the device and page content is never sent anywhere. By default the only requests the extension makes are re-reads of images already on the page, from their own sites and without cookies, to look for declared labels; this can be turned off. The optional claim lookup sends text you select to Google, and only when you ask. Only your settings and lists sync, through the browser's own account sync.

## What it does

- **Emotional charge:** headings and paragraphs that score "high" are hidden behind a one-line notice that says why (for example "fear and outrage wording") with a **Show** button. Once shown, the passage carries a label with the detail; click the label to dispute and remove it. "Medium" passages can optionally be labelled.
- **Declared labels:** reads labels that already exist rather than guessing. Images whose metadata declares them AI-generated (IPTC Digital Source Type) are hidden the same way; fact-check markup (schema.org ClaimReview), publisher-declared opinion, satire and advertiser content, and `rel="sponsored"` links are listed in the page panel.
- **Claim lookup (opt-in):** if you save a Google Fact Check Tools API key in settings, selecting text and choosing "Check this claim" from the right-click menu sends only that selected text to Google and lists any published fact-checks. Without a key the menu item does not exist.
- **Draining design:** the page panel also lists autoplay video, infinite scroll, pressure wording ("only 3 left") and countdown timers.
- **Domain lists:** warn-before-opening, block, and always-allow lists. Allow beats block, block beats warn; subdomains are included.
- **Your choice of action:** settings switch flagged content between hide (the default), blur until hovered, or label only. Nothing is removed from the page, and the page panel has a "Show all hidden" button.

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

## Limits in v0.4

- English only; the emotion scorer is a word-list heuristic that has not been validated against labelled data. Because flagged content is now hidden by default, its false positives hide things: accurate reporting on a disaster will often be covered until you press Show.
- 300 domains per list (browser sync storage allows 8 KB per item).
- Declared labels are unverified: image metadata can be stripped or forged, and a missing label proves nothing.
- Content Credentials (C2PA) are detected but their signatures are not validated yet.
- Image labels are read from the first 256 KB of up to 20 images per page, so metadata stored late in a large file is missed.
- Claim lookup has been tested against a stubbed response, not the live Google service. No licensed domain reputation yet.
- Chrome and Edge only. The Safari build needs a Mac with Xcode and has not been started. The Android app is in [`../android/`](../android/).
