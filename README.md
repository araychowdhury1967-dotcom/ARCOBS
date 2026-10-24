# Mobile Streaming Studio

Mobile Streaming Studio is an original, native Android camera studio written in Kotlin and Jetpack Compose. It publishes H.264/AAC over **RTMP or RTMPS**, records local MP4 files, and renders preview, streaming, and recording from the same OpenGL scene composition pipeline.

- **Application ID:** `com.mobilestreaming.studio`
- **Debug application ID:** `com.mobilestreaming.studio.debug`
- **Version:** 0.1.0 (`versionCode` 1)
- **Android:** minimum API 26, target/compile API 35
- **Build:** AGP 8.9.0, Gradle 8.11.1, Kotlin 1.9.22, Compose BOM 2024.02.02

This is a production-oriented source baseline, not a claim of production readiness. Phase 1 source and the complete Compose app compile, but camera, codec, network, and lifecycle behavior still require on-device acceptance testing.

## Verified debug APK

A real `:app:assembleDebug` build completed on **2026-10-24**. The verified deliverable is:

```text
artifacts/MobileStreamingStudio-0.1.0-debug.apk
```

In this checkout, its absolute location is:

```text
/home/user/ARCOBS/artifacts/MobileStreamingStudio-0.1.0-debug.apk
```

| Property | Verified value |
|---|---|
| Size | 15,828,393 bytes |
| SHA-256 | `0f9d55b407bea3e8d727ab0e482b919dcb83cd3078b0d3a0051672a7022d38cc` |
| Package | `com.mobilestreaming.studio.debug` |
| Version | `0.1.0-debug` (1) |
| Signing | Android debug certificate; **not a release signature** |
| Structure | ZIP integrity passed; manifest and `classes.dex` present |

Verify the retained copy with:

```bash
(cd artifacts && sha256sum -c SHA256SUMS)
```

Install it on a connected, developer-enabled Android device with:

```bash
adb install -r artifacts/MobileStreamingStudio-0.1.0-debug.apk
```

The APK contains no configured stream key. Replace the placeholder RTMP URL in **Stream Settings** before broadcasting. **Building and signing the APK is not device, live-stream, or release verification.** No emulator/device or live RTMP endpoint was available in the build environment.

## What is implemented

- Camera2 preview and capture, front/back switching, capability-bounded zoom, exposure, focus, torch, and front-camera mirroring.
- Microphone capture, AAC encoding, volume/mute, level metering, and runtime noise-suppression/AGC changes where the device supports them.
- Actual RTMP/RTMPS socket publishing with handshake validation, AMF0 commands, chunking, FLV packetization, TLS hostname verification, timeouts, bounded queues, and reconnect attempts.
- Truthful `OFFLINE`, `CONNECTING`, `LIVE`, `RECONNECTING`, `STOPPING`, and `ERROR` states. `LIVE` is entered only after `NetStream.Publish.Start`.
- Hardware H.264/AAC `MediaCodec` encoding and local MP4 recording through `MediaMuxer`.
- One shared GPU scene renderer for preview, outgoing video, and recording—not a mock preview or separate recording-only pipeline.
- Six starter scenes, scene/source editing, transforms, crop, opacity, text, images, logos, lower-thirds, tickers, clocks, dates, and restricted HTTPS browser textures.
- Persisted settings, Keystore-encrypted stream key, scene profile import/export, redacted diagnostics, and a recording library with play/share/delete actions.
- Foreground-service session ownership, consent-gated MediaProjection video, and platform-integration interfaces without fabricated chat or viewer counts.

See [FEATURES.md](FEATURES.md) for the detailed inventory and [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) for boundaries.

## Open and build in Android Studio

### Prerequisites

1. A compatible current Android Studio (AGP 8.9.0 / Meerkat 2024.3.1 family or newer compatible version).
2. JDK 17.
3. Android SDK Platform 35 and current build tools.
4. A device running Android 8.0/API 26 or later for runtime testing.

Use the included Gradle wrapper; do not replace it with an older system Gradle.

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

Android Studio can use its normal SDK Manager and `local.properties`. That file is intentionally ignored. The standard debug output is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Run the Gradle unit-test tasks in a normal online/JDK 17 development environment with:

```bash
./gradlew :core-model:test :streaming:testDebugUnitTest
```

A standalone JVM harness is also included for the protocol/model tests:

```bash
# java must be on PATH; set KOTLINC if kotlinc is not.
KOTLINC=/path/to/kotlinc ./tools/run-core-tests.sh
```

The sandbox-verification run used the standalone harness because the recovered offline cache did not contain the JUnit test artifacts. It passed **16/16 tests**. See [TESTING.md](TESTING.md).

## First run

1. Install and launch the debug app.
2. Grant camera and microphone permissions. Grant notification permission on API 33+ so session status is visible.
3. Open **Stream Settings** and enter the ingest URL and stream key from your RTMP server or platform.
4. Start the preview and confirm the scene before streaming.
5. Start streaming. Connecting or sending a publish command is not shown as Live; the server must accept publishing.
6. Optionally start local recording at the same time. Recording and streaming consume the same encoded samples.
7. Stop recording and allow MP4 finalization to finish before playing or sharing it.

Use a server you control and a test stream key first. RTMPS uses Android's default trust store and TLS hostname verification; untrusted/self-signed certificates are not silently accepted.

## Project layout

```text
app/          Compose UI, Activity, ViewModel, application container, foreground service
core-model/   Pure Kotlin stream, scene, source, validation, and recording models
core-data/    Preferences, Android Keystore, scenes, assets, recordings, diagnostics
streaming/    Camera2, GL compositor, codecs, RTMP, MP4, screen/browser capture
tools/        Standalone JVM core-test harness
artifacts/    Verified debug APK and SHA-256 manifest
```

## Documentation

- [FEATURES.md](FEATURES.md) — implemented feature inventory and integration status
- [ARCHITECTURE.md](ARCHITECTURE.md) — data flow, state machine, ownership, threading, security
- [TESTING.md](TESTING.md) — exact verification results and device acceptance plan
- [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) — technical and validation limitations
- [RELEASE.md](RELEASE.md) — signing, release builds, and distribution checklist
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) — dependency and asset notices

## Privacy and security defaults

- Stream keys are encrypted with AES-GCM using a key held by Android Keystore; only ciphertext is stored in preferences.
- The app does not log stream keys, access tokens, media payloads, or RTMP query credentials.
- Scene exports contain scenes/sources only, never the RTMP endpoint credentials or encrypted preferences.
- `android:allowBackup="false"`, `android:usesCleartextTraffic="false"`, and non-exported broadcast service are set deliberately. Raw RTMP sockets are independent of HTTP cleartext policy; use RTMPS where transport confidentiality is required.
- MediaProjection starts only from the system's consent result. Browser sources are an optional texture feature; the application itself is native Compose, not a WebView wrapper.

No Streamlabs/OBS branding, protected assets, or third-party streaming engine is bundled.
