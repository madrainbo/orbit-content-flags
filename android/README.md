# Orbit Content Flags for Android (v0.8.1)

Brings the browser extension's flags to every app on the phone. Same stance: **signals, not verdicts**, scored on the device.

## What it does

- **On-screen flags in any app.** An accessibility service reads the text of whichever app is in front, and scores it with the same word-list scorer as the extension. Scam-style, adult, engagement-bait and highly charged passages are covered in place by a notice that says why ("Hidden: scam-style wording (“claim your prize”, …). Tap to show."); tapping it shows the passage, and it stays shown. A small pill summarises the screen ("1 scam-style · 2 high-charge · 3 hidden · AI label on screen · pressure wording"); tap it to hide it until you change screens. Covering can be turned off in the app, leaving only the pill. Password fields and text you type are skipped.
- **Text inside images.** About once a second, while the screen is changing, the service captures the app in front and reads any text in it on the device (memes, ads, video overlays), then applies the same rules. The capture lives in memory for one analysis and is discarded; it is never stored or sent. This can be turned off in the app. Needs Android 11 or later.
- **Flags screen.** Tapping the pill opens a list of what was flagged on the screen you came from, including "possible" flags that were not covered, and what was flagged recently: the kind, the reason, which app, how long ago and the quoted text. Pressing and holding the pill hides it instead. The list is kept in memory only.
- **Reporting.** Each flag has a "Not a problem" button; a passage marked that way is not flagged again (only a one-way fingerprint of it is stored). Phone numbers, account names and sites found in a flag or on that screen have "Report" buttons, and anything can be reported by typing it in. Reported items are flagged wherever they appear on the phone, and reported sites are also blocked when domain blocking is on. Reports can be removed. **Reports stay on this phone; nothing is shared with other users.**
- **Long-running scams.** In messaging and dating apps the service follows the stages of investment and romance scams across a chat over days or weeks: contact from a stranger, fast affection, a request to move to another app, investment talk, an unfamiliar trading site, a deposit request, a fee demanded before withdrawal, a personal plea for money, and pressure or secrecy. When three stages including one about money have appeared, a red warning lists them with how long ago each was seen. Only stage markers are stored on the phone, under a scrambled identifier, for up to 180 days; messages and names are never stored. The warning can be hidden for a week, the feature can be turned off, and a button clears all markers.
- **Text model.** Passages the phrase rules let through are judged as a whole by a small bundled model, so reworded scams, bait and adult posts can still be caught. Very confident results are covered; moderately confident ones are only mentioned in the pill. See [`../tools/textmodel/`](../tools/textmodel/) for how it was trained and measured.
- **Adult images.** Pictures and videos the app in front exposes (including images on web pages in Chrome) are cut out of the same screen capture and run through a bundled on-device model. A flagged picture is covered with "Hidden: image may show adult content. Tap to show." See [`../tools/model/`](../tools/model/) for the model and what has been checked. This can be turned off in the app.
- **Reads post text in social apps.** Text is taken from each element's text or, where an app leaves that empty (Instagram and YouTube often do), from its description.
- **Web pages in Chrome and other browsers.** Browsers give a cut-down view of the page to services that ask for little, so this service requests all accessibility event types to be shown the full page. Incognito tabs block screen capture, so text inside images is not read there.
- **Domain blocking in any app.** A local DNS filter, built on Android's VPN feature, answers lookups for blocked domains with "no such domain". Only DNS packets enter it; other traffic is untouched and nothing is sent to a remote server. Allowed lookups go to the network's own DNS server.

## Build and install

```bash
cd android
./gradlew :core:test :app:assembleDebug     # needs JDK 17+ and the Android SDK (platform 35)
adb install app/build/outputs/apk/debug/app-debug.apk
```

Then open the app, turn on on-screen flags (a disclosure screen comes first), add domains, and start domain blocking. Requires Android 10 or later.

**Installing from an APK file (Android 13+):** Android blocks accessibility access for apps installed outside a store and shows "App was denied access". After seeing that dialog once, open Settings > Apps > Orbit Content Flags, tap the menu in the top-right corner, choose "Allow restricted settings", then turn the service on in Settings > Accessibility. Installs from Google Play are not affected.

**Checking it works:** when the service starts, a pill reading "Orbit on-screen flags are on" appears for a few seconds. Most screens produce no flag, so the app has a "Show sample text" button: with the service on, three text samples (high charge, scam-style, adult) should each be covered within a second, and a fourth sample, a picture of scam text, should be covered a moment later. Tapping a cover shows that sample.

## Layout

- `core/` is pure Kotlin with no Android dependencies: the scorer, domain lists and DNS packet handling, unit-tested on the JVM. The scorer is a port of `content-filter-extension/core/scoring.js`; both are tested against `shared/scoring-vectors.tsv` so they cannot drift apart.
- `app/` holds the accessibility service, the DNS filter service and the settings screen.

## Limits in v0.8

- **Lightly tested on devices.** On an Android 11 emulator the on-screen flags were checked: the start-up notice appears, the in-app sample is covered at the right position, each of the three text samples gets its own cover, tapping a cover reveals it and it stays revealed, and overlays clear on the home screen. On an Android 14 emulator the three text covers appeared and the screen capture succeeded, but text recognition never finished there (the emulator has no hardware acceleration), so **reading text inside images has not been seen working end to end**. Instagram, YouTube and Chrome themselves have not been tested, so whether Chrome now exposes full page text to the service is unconfirmed. The pill's wording was not confirmed by eye. Covers were not checked while scrolling or in third-party apps. Domain blocking has not been exercised on a device or emulator; only its packet handling is unit-tested. Nothing has been checked on Android 12 or later beyond compiling.
- The scorer is the same unvalidated English word-list heuristic as the extension; no on-device ML model is used.
- Covers follow the text with a short delay (the screen is re-read at most every 0.3 seconds), so while scrolling a cover can lag behind its passage or briefly expose it. At most 12 passages are covered at once. There is no dispute action yet.
- **Reporting is local only.** There is no shared database yet, so one person's report does not protect anyone else. A shared service would need a server, protection against false or malicious reports, and a privacy decision about what leaves the phone.
- Reporting was checked on an Android 11 emulator: tapping the pill opens the list, reporting an account covers text that mentions it, reporting a phone number stores it, and "Not a problem" removes the cover. Phone numbers are matched with or without a country code, which can occasionally match a different number with the same ending. A reported phone number is only flagged on screen; calls and texts from it are not blocked.
- **The text model is still weak on real apps.** It runs on a real phone, where its first Instagram test listed Instagram's own button and image labels as possible flags. Control labels are now filtered out before anything is judged, the model was retrained with such labels as ordinary examples, it needs at least six words, and a "possible" flag now needs 75% confidence. On the held-out test set it catches 28% of reworded harmful passages at the "cover" level and 58% at the "possible" level, each with 1 false alarm among 62 ordinary passages; that test set is small and synthetic, and whether real captions and comments are now judged well is untested.
- **Long-running scam tracking is new and only lightly tested.** The stage logic is unit-tested and the warning appeared on an emulator for the built-in sample, but no real messaging app has been tried. A chat is recognised by having a message box, and identified by the first short text near the top of the screen, which will sometimes be wrong. Watched apps: WhatsApp, Telegram, Signal, Google and Samsung Messages, Messenger, Line, WeChat, Viber, Tinder, Bumble and Hinge. Instagram and other apps that mix feeds with chats are not watched. The app cannot tell who wrote a message, so your own words count too.
- **The adult-image model has not been tested on adult images.** On ordinary photos it behaved correctly (27 of 28 neutral), and on an emulator it loaded and ran, but how often it catches real adult content is unmeasured here. It will miss some and wrongly cover others.
- Only pictures that the app exposes as images are checked (up to 8 per screen, at least 96dp on each side). Apps that draw everything themselves expose none.
- Violence and gore are not detected; there is no model for them. Unlabelled AI images are not detected.
- The settings screen shows what the last screen check did ("12 text blocks read, 3 pictures found, 3 checked"), which is the quickest way to see whether capture is working on a given phone.
- On Android 11 to 13 the capture includes this app's own covers, so anything found in an image is uncovered and re-checked whenever the screen scrolls, which can flicker. Android 14 and later capture the app window alone and do not have this problem.
- Text recognition uses Google's ML Kit with a model bundled in the app. The image and text stay on the device, but ML Kit itself sends usage and performance metrics to Google.
- The app is about 82 MB because of the bundled recognition, image and text models.
- Engagement-bait wording ("follow for more", "link in bio") is common on ordinary creator posts, so expect more covers on social feeds.
- Because flagged text is hidden by default, the scorer's false positives hide things; accurate reporting on a disaster will often be covered until tapped.
- Domain blocking only blocks. There is no "warn first" page as in the browser, because DNS cannot show one.
- Blocking is bypassed by apps that use their own encrypted DNS (for example a browser's "secure DNS") and when Android's Private DNS is set to a named provider. It handles IPv4 DNS over UDP only, and cannot run alongside another VPN.
- Lists are separate from the browser extension's; there is no sync between devices yet.
- AI content is only reported when the app on screen shows its own badge (for example "AI info"); the post is not covered, because its edges cannot be found reliably. Unlabelled AI content is not detected.
- Scam and adult detection are English phrase lists applied to on-screen text; images and video are never inspected.
- No image metadata labels, fact-check markup or claim lookup on Android.

## Google Play

Not submitted. Two declarations are needed before review, and either can be refused:

- **Accessibility API.** The service is declared with `isAccessibilityTool="false"`, so Play requires a permission declaration and the in-app prominent disclosure (implemented in `MainActivity`). Play's policy lists monitoring-style apps among those that are not accessibility tools.
- **VpnService.** Play requires a declaration and disclosure for VPN use; this app uses it only for on-device DNS filtering.
