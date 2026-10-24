# Known Limitations

## Validation status

The complete app and all modules compiled and a signed debug APK was produced and structurally verified. The 16 standalone model/RTMP tests passed. **No physical device, emulator, live RTMP server, or platform account was available for runtime verification.** Camera, microphone, OpenGL, hardware codecs, MediaProjection, background behavior, MP4 playback, RTMPS interoperability, and OEM-specific behavior therefore remain acceptance-test items.

The debug APK is not a release build and must not be distributed as one.

## Protocol and codec scope

- Output is H.264 video with AAC audio over classic RTMP/RTMPS. There is no SRT, WebRTC, RTSP, HLS/DASH output, RTMPE, enhanced RTMP, HEVC/AV1, or software-codec fallback.
- The publisher implements the commands and packet types needed by this app, not every optional RTMP specification feature or every vendor extension. Interoperability with each target ingest server must be tested.
- RTMPS uses the platform trust store. Self-signed, expired, wrong-host, or otherwise untrusted certificates fail; there is no custom CA import or certificate-pinning UI.
- Network recovery is bounded reconnect logic, not a guarantee of uninterrupted playback. Queue overflow drops packets, and a server/player may need a later keyframe to recover cleanly.
- Advertised dimensions/profile/frame rate are requests to hardware codecs. Actual support and stable performance vary by device, thermal state, resolution, and encoder vendor. A 4K-validated configuration bound is not a promise of 4K performance.
- Audio echo cancellation, stereo behavior, noise suppression, AGC, Bluetooth routing, and hardware level characteristics are device-dependent.

## Composition and capture

- Multiple camera source layers share the active camera texture; the app does not capture two physical cameras simultaneously.
- There is no multi-camera switching effects engine, chroma key, LUT/color pipeline, transitions library, animated scene scripting, or arbitrary shader editor.
- Only the first visible browser source is actively rendered by the application container. Browser capture is approximately two frames per second at 960×540 before scene scaling, has no browser-audio path, and is unsuitable for smooth video/animation, DRM playback, or highly interactive pages.
- Browser rendering depends on Android System WebView. Safe Browsing and navigation restrictions do not make untrusted remote content inherently safe.
- Image/text/browser sources are rendered to the encoded scene, but source-specific performance has not been profiled on low-end GPUs.

## MediaProjection and audio restrictions

- Screen capture records screen video only after user consent. It does not bypass Android's per-session consent or revoked-token behavior.
- There is no internal/device-audio capture path. Microphone audio remains separate. Android version, app, and DRM policies can prevent internal audio capture; this project does not attempt to circumvent them.
- Black/secure app windows remain protected by the operating system.
- Starting capture from the background is not supported as a workaround for Android foreground-service/visibility rules.

## Recording

- Streaming and recording deliberately share one encoded output. There is no independent recording resolution, bitrate, codec, or “record clean output without overlays” mode in this version.
- Recording requires both audio and video samples; a recording with no complete A/V data is discarded rather than reported as successful.
- There is no pause/resume, editing, chaptering, crash-recovery journal, or guaranteed recovery of an MP4 if the process/device dies before finalization.
- The recording library stores metadata and content URIs, not a second backup of video files. Deleting a recording removes the underlying MediaStore item after confirmation.
- External playback and sharing depend on compatible apps installed on the device.

## Background and lifecycle

- A foreground service improves session visibility but cannot guarantee that every OEM will preserve a process under aggressive power management, memory pressure, or a force-stop.
- Android 14/15 foreground-service, while-in-use permission, notification, and MediaProjection behavior still needs API-specific device testing.
- No claim is made that streaming survives device reboot, force-stop, permission revocation, or permanent network loss.

## Platform features

- YouTube/Twitch/Facebook chat, OAuth account linking, viewer counts, and publishing stream metadata through official provider APIs are boundaries only, not connected provider implementations.
- Saved stream title/description/category/destination are local values and are not automatically sent to a platform.
- Generic RTMP ingest availability, required URL form, stream-key policy, and account eligibility are controlled by each provider and can change.

## Security and persistence

- Android Keystore protects the stream-key encryption key under normal platform assumptions; it does not protect against a rooted/compromised device, screenshots of the key while entered, or a user intentionally exporting/sharing secrets.
- Keystore keys are not user-authentication-bound in this version.
- Scene import is bounded and validated, but imported remote browser URLs should still be treated as untrusted content.
- Diagnostic redaction is defense in depth, not permission to pass secrets into future log statements. New code must avoid credentials and media payloads at the source.

## Testing and release gaps

- The sandbox's offline Gradle cache lacked normal JUnit/AndroidX-test artifacts, so the standalone harness—not the Gradle test tasks or instrumentation runner—was executed there.
- No UI screenshot tests, Compose semantics tests, instrumented permission tests, soak test, bandwidth/CPU/memory profile, or release/R8 build has been verified.
- Before a release, complete [TESTING.md](TESTING.md) on representative hardware and follow [RELEASE.md](RELEASE.md), including current Play policy declarations for sensitive permissions and foreground services.
