# AndroidTvRemote

One Android app, installed on your phone, that finds an Android TV / Google TV on your Wi-Fi network, pairs with it, and controls it (D-pad, home, back, volume, power) - no separate TV-side app, no ADB, no developer mode required.

## How it works

Google TV's own mobile app talks to the TV using a protocol usually called **"Android TV Remote protocol v2"**. It is **not officially published or documented by Google** (there is no page for it on developer.android.com) - it has been reverse-engineered by the open-source community and is used by many existing projects (Home Assistant's `androidtvremote2`, `louis49/androidtv-remote`, etc.). This app implements that same protocol directly in Kotlin:

1. **Discovery** - `TvDiscovery` uses Android's built-in `NsdManager` (mDNS) to find TVs advertising `_androidtvremote2._tcp.` on the local network.
2. **Pairing** (`PairingSession`, port 6467) - opens a mutual-TLS connection using a self-signed client certificate generated in the Android Keystore, asks the TV to show a 6-character code on screen, and proves you saw it by sending back `SHA-256(client modulus + client exponent + server modulus + server exponent + last 4 hex chars of the code)`.
3. **Remote control** (`RemoteSession`, port 6466) - after pairing, sends `RemoteKeyInject` protobuf messages for D-pad/volume/power/etc., replies to the TV's ping keep-alives, and can launch apps via deep link.

The wire format is defined by `remotemessage.proto` and `pairingmessage.proto` (standard protobuf, delimited framing) under `app/src/main/proto/`.

## Sources for the protocol

- Detailed byte-level write-up: https://github.com/Aymkdn/assistant-freebox-cloud/wiki/Google-TV-(aka-Android-TV)-Remote-Control-(v2)
- Reference Python implementation (Apache-2.0): https://github.com/tronikos/androidtvremote2
- Reference JS implementation: https://github.com/louis49/androidtv-remote
- `RemoteKeyCode` values cross-checked against AOSP: https://android.googlesource.com/platform/frameworks/native/+/master/include/android/keycodes.h

Because this is a community-reverse-engineered protocol and not an official Google API, it can break if Google changes the Android TV Remote Service on the TV side. It has been reported to work against TVs running Remote Service v5+ (check on the TV under Settings > Apps > See all apps > Android TV Remote Service).

## Requirements

- Phone and TV on the same Wi-Fi network.
- TV is an Android TV / Google TV device (this protocol does not apply to Tizen/Samsung, webOS/LG, Roku, etc.).
- Android Studio (Ladybird/Koala or newer) to build; `minSdk 26`.

## Building

Open the project root in Android Studio and run the `app` module on a phone. The first Gradle sync will download the `protoc` compiler and the `com.google.protobuf` Gradle plugin, so an internet connection is needed the first time.

## Status / known limitations (MVP)

- Network I/O currently runs on a plain background `Thread` rather than coroutines/WorkManager - fine for a remote, but a good next step for production polish.
- Voice input and on-screen-keyboard text injection (both part of the real protocol) are not implemented yet.
- No Wi-Fi multicast lock is acquired before mDNS discovery, which can make discovery flaky on some routers/OEM Wi-Fi stacks.
- `RemoteSetActive` handling is a stub - the exact expected reply value isn't publicly documented, so the app currently does not answer it (contributions welcome once verified against real hardware).
