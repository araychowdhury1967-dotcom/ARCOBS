# Testing and Verification

## Verified in the build environment — 2026-10-24

### 1. Full Android debug build

The actual Gradle Android build ran through Kotlin compilation, Compose compilation, resource processing, DEX, packaging, and debug signing:

```text
:app:assembleDebug
BUILD SUCCESSFUL
77 actionable tasks in the final successful invocation
```

The successful invocation used the recovered offline toolchain described by the environment, not a mock compiler or a WebView wrapper. All current `core-model`, `core-data`, `streaming`, and `app` sources participated.

### 2. Standalone model/RTMP tests

Command used in the prepared environment:

```bash
export JAVA_HOME=/home/user/.cache/mss-tools/jdk/runtime
export PATH="$JAVA_HOME/bin:/home/user/.cache/mss-tools/kotlin/bin:$PATH"
KOTLINC=/home/user/.cache/mss-tools/kotlin/bin/kotlinc ./tools/run-core-tests.sh
```

Result: **16 core tests passed**.

1. RTMPS configuration normalization
2. Rejection of non-RTMP protocols and embedded credentials
3. Quality-preset output profiles
4. Presence of six starter scenes
5. Independent source IDs after scene duplication
6. Prevention of last-scene deletion
7. Scene switching and bounded source reordering
8. AMF0 command round trip
9. AMF0 strict-array round trip
10. Fragmented RTMP message round trip
11. Extended RTMP timestamp round trip
12. Interleaved chunk-stream preservation
13. H.264 SPS/PPS decoder-configuration generation
14. Annex-B to length-prefixed FLV conversion
15. AAC sequence-header/raw-frame distinction
16. Endpoint diagnostics redaction of query credentials and stream keys

For a normal local setup, use `java` on `PATH` and either put `kotlinc` on `PATH` or set `KOTLINC`.

### 3. APK verification

Retained artifact:

```text
artifacts/MobileStreamingStudio-0.1.0-debug.apk
```

Verified properties:

```text
Size:       15,828,393 bytes
SHA-256:    0f9d55b407bea3e8d727ab0e482b919dcb83cd3078b0d3a0051672a7022d38cc
Package:    com.mobilestreaming.studio.debug
Version:    0.1.0-debug (versionCode 1)
Min SDK:    26
Target SDK: 35
```

Checks performed:

- SHA-256 match between Gradle output and retained artifact.
- ZIP central-directory/integrity check passed.
- `AndroidManifest.xml` and `classes.dex` are present.
- AAPT2 package dump reports the package/version/SDK values above.
- APK Signature verification with `apksigner` succeeded and reports `C=US, O=Android, CN=Android Debug`.
- Signer certificate SHA-256: `2e24e2b88fbecb1f0ec9d029905accfea7abd5d32f9164c9e258b4e65386d963`.

Recheck the retained file with:

```bash
(cd artifacts && sha256sum -c SHA256SUMS)
apksigner verify --print-certs artifacts/MobileStreamingStudio-0.1.0-debug.apk
```

## Not verified

No result in this document should be read as evidence of any of the following:

- Installation or launch on a device/emulator
- Camera/microphone permission flow or actual captured frames/audio
- OpenGL rendering correctness on a GPU
- Hardware encoder compatibility or sustained performance
- A live RTMP/RTMPS connection to a real server
- Playback of a generated MP4
- MediaProjection, background execution, reconnect, or thermal behavior
- Chat/viewer-count/provider API behavior
- Instrumented Android tests, release minification, or release signing

No emulator/device, live endpoint, or streaming credentials were available.

## Gradle tests in a normal development environment

With JDK 17, Android SDK 35, and dependency access:

```bash
./gradlew :core-model:test :streaming:testDebugUnitTest
```

Those Gradle tasks were not the tests executed in the offline sandbox because JUnit artifacts were absent from the recovered cache. The standalone harness compiles the same core model/RTMP sources and executes the 16 checks above without Android or JUnit dependencies.

## Device acceptance plan

Record device model, Android version, build fingerprint, app SHA-256, server software/version, and test date for each run. Never attach stream keys to test reports.

### A. Install and permissions

1. Install the exact APK listed above with `adb install -r`.
2. Launch and verify original launcher assets and Compose navigation.
3. Deny camera/microphone initially; confirm streaming/recording does not start and reports the permission requirement.
4. Grant camera/microphone; verify preview starts. On API 33+, test notification grant and denial separately.
5. Confirm the app does not request storage permission on API 29+ for normal MediaStore recording.

### B. Camera and composition

1. Test front/back switching, zoom limits, exposure, focus, torch (if available), and front mirror.
2. Switch among all six starter scenes. Verify the outgoing scene, not just UI labels, changes.
3. Add text/image/logo/lower-third/ticker/clock sources; drag, scale, rotate, crop, reorder, hide, lock, and delete them.
4. Change opacity and colors. Background/foreground behavior must match between preview and recorded/published output.
5. Rotate the device and test landscape, portrait, and square output configurations while idle.
6. Verify scene edits survive process restart and profile export/import. Confirm exported JSON has no endpoint credentials.

### C. RTMP/RTMPS publishing

Use a controlled RTMP server and a revocable test key.

1. Enter an invalid endpoint and verify validation fails before capture/publish.
2. Use an unreachable endpoint; verify `CONNECTING`/retry behavior and eventual `ERROR`, never `LIVE`.
3. Connect with a wrong key; verify rejection is not shown as Live and diagnostics redact the key.
4. Connect with the correct key; verify `LIVE` appears only after the server accepts `NetStream.Publish.Start`.
5. Independently inspect server input or a player for continuous H.264/AAC, correct aspect, overlays, keyframes, and monotonic timestamps.
6. Interrupt Wi-Fi/mobile data briefly. Verify `RECONNECTING`, bounded attempts, restored keyframe/configuration, and eventual error if recovery fails.
7. Repeat using RTMPS with a valid certificate. Test an untrusted/wrong-host certificate and verify failure; no trust bypass is acceptable.
8. Inspect logcat and exported diagnostics for stream keys, URL query tokens, access tokens, and media payloads.

### D. Simultaneous MP4 recording

1. Start recording alone, then while streaming; test stream-only stop while recording continues.
2. Verify overlays/scene switches in both the published input and MP4.
3. Stop recording and wait for finalization. Confirm `Movies/MobileStreamingStudio` contains a playable H.264/AAC MP4.
4. Play, share, and delete it from the recording library. Verify deleted items do not remain presented as completed recordings.
5. Test short recordings, storage exhaustion, process death, and permission revocation. Incomplete output must not be reported as a successful recording.

### E. Foreground service and MediaProjection

1. Start a session, leave the activity, and verify the foreground notification reflects actual state.
2. Use **Stop all** and verify stream stop, recording finalization, capture cleanup, and foreground removal.
3. Start screen capture only through the system consent prompt. Deny consent and verify no capture starts.
4. Verify secure windows remain protected and no internal-audio claim is made.
5. Revoke projection, lock/unlock the device, and test API 26/28/29/33/34/35 as available.
6. Test OEM battery restrictions and process pressure without treating foreground service as a guarantee.

### F. Soak and performance

Run at least a 30–60 minute controlled stream plus recording on representative low/mid/high devices. Track rendered/dropped frames, network drops, bitrate changes, memory, CPU, temperature/thermal status, battery, and output A/V sync. Repeat with overlays and screen capture. Any release candidate should also pass a clean release build and a fresh-install upgrade/persistence test.

## Adding tests

- Keep pure model, AMF0, FLV, and chunking tests independent of Android.
- Add malformed/truncated input, maximum bounds, timestamp wraparound, reconnect/status, and credential-redaction regressions with protocol changes.
- Use Android instrumentation for permission, MediaStore, Keystore, and projection behavior on real devices.
- Do not replace failed integration tests with mocked “Live” states or simulated viewer/chat data.
