# Orbit Content Flags for Android (v0.1)

Brings the browser extension's flags to every app on the phone. Same stance: **signals, not verdicts**, scored on the device.

## What it does

- **On-screen flags in any app.** An accessibility service reads the text of whichever app is in front, scores it with the same word-list scorer as the extension, and shows a small pill over the app ("2 high-charge passages · pressure wording"). Tap the pill to hide it until you change screens. Password fields and text you type are skipped.
- **Domain blocking in any app.** A local DNS filter, built on Android's VPN feature, answers lookups for blocked domains with "no such domain". Only DNS packets enter it; other traffic is untouched and nothing is sent to a remote server. Allowed lookups go to the network's own DNS server.

## Build and install

```bash
cd android
./gradlew :core:test :app:assembleDebug     # needs JDK 17+ and the Android SDK (platform 35)
adb install app/build/outputs/apk/debug/app-debug.apk
```

Then open the app, turn on on-screen flags (a disclosure screen comes first), add domains, and start domain blocking. Requires Android 10 or later.

**Installing from an APK file (Android 13+):** Android blocks accessibility access for apps installed outside a store and shows "App was denied access". After seeing that dialog once, open Settings > Apps > Orbit Content Flags, tap the menu in the top-right corner, choose "Allow restricted settings", then turn the service on in Settings > Accessibility. Installs from Google Play are not affected.

**Checking it works:** when the service starts, a pill reading "Orbit on-screen flags are on" appears for a few seconds. Most screens produce no flag, so the app has a "Show sample text" button: with the service on, the pill should appear within a second.

## Layout

- `core/` is pure Kotlin with no Android dependencies: the scorer, domain lists and DNS packet handling, unit-tested on the JVM. The scorer is a port of `content-filter-extension/core/scoring.js`; both are tested against `shared/scoring-vectors.tsv` so they cannot drift apart.
- `app/` holds the accessibility service, the DNS filter service and the settings screen.

## Limits in v0.1

- **Lightly tested on devices.** On an Android 11 emulator the on-screen flags were checked: the start-up notice appears, the pill shows for the in-app sample, hides on the home screen and returns. Domain blocking has not been exercised on a device or emulator; only its packet handling is unit-tested. Nothing has been checked on Android 12 or later beyond compiling.
- The scorer is the same unvalidated English word-list heuristic as the extension; no on-device ML model is used.
- The pill reports counts for the screen; it does not mark individual passages, and there is no dispute action yet.
- Domain blocking only blocks. There is no "warn first" page as in the browser, because DNS cannot show one.
- Blocking is bypassed by apps that use their own encrypted DNS (for example a browser's "secure DNS") and when Android's Private DNS is set to a named provider. It handles IPv4 DNS over UDP only, and cannot run alongside another VPN.
- Lists are separate from the browser extension's; there is no sync between devices yet.
- No image labels, fact-check markup or claim lookup on Android.

## Google Play

Not submitted. Two declarations are needed before review, and either can be refused:

- **Accessibility API.** The service is declared with `isAccessibilityTool="false"`, so Play requires a permission declaration and the in-app prominent disclosure (implemented in `MainActivity`). Play's policy lists monitoring-style apps among those that are not accessibility tools.
- **VpnService.** Play requires a declaration and disclosure for VPN use; this app uses it only for on-device DNS filtering.
