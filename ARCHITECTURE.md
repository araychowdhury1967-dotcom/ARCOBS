# Architecture

## Design goals

1. Keep Android/Compose UI separate from capture, codecs, and network work.
2. Use one composed video output for preview, RTMP, and MP4.
3. Represent server acknowledgment honestly; never equate socket connection with Live.
4. Bound queues, profile imports, diagnostics, and reconnect behavior.
5. Keep credentials and platform authorization outside generic media code.

## Module graph

```text
app
├── core-model
├── core-data ───> core-model
└── streaming ───> core-model
```

### `core-model`

Pure Kotlin data models and invariants: `StreamConfig`, `StreamState`, `RecordingState`, `AudioMixSettings`, `SceneLibrary`, `Scene`, `VisualSource`, transforms, crop, endpoint validation, and quality presets. It has no Android or Compose dependency.

### `core-data`

Android-local persistence and files:

- `SettingsRepository`: non-secret preferences and reactive settings state.
- `SecureStreamKeyStore`: Android Keystore AES-GCM encryption/decryption.
- `SceneRepository`: atomic scene persistence and bounded/versioned JSON profiles.
- `RecordingRepository`: completed recording history.
- `AssetRepository`: app-owned copies of imported images.
- `DiagnosticLog`: bounded/redacted diagnostics.

### `streaming`

All media and transport work:

- `Camera2Controller`, `AudioEncoder`, `VideoEncoder`
- `SceneCompositor`, bitmap/text/source renderers, preview view
- `MediaCoordinator`, `RecordingMuxer`
- `RtmpPublisher`, chunking, AMF0, FLV packetization, endpoint handling
- `ScreenCaptureController`, `BrowserOverlayController`
- `BroadcastEngine`, the application-level session coordinator
- `StreamPlatformIntegration`, the authorized-platform boundary

### `app`

`MainActivity`, Compose destinations/components/theme, `StudioViewModel`, `StudioApplication`/`AppContainer`, and `BroadcastService`. UI collects `StateFlow`/`SharedFlow`; it does not run codec, socket, or GL work on the main thread.

## Video data flow

```text
Camera2 ──> SurfaceTexture ──┐
MediaProjection frames ──────┤
Imported bitmaps/text ───────┤
Restricted browser bitmap ───┤
                             v
                    SceneCompositor (GL thread)
                    one ordered scene snapshot
                     ┌───────┴────────┐
                     v                v
              Preview EGL surface   Encoder EGL surface
                                      │
                                      v
                              MediaCodec H.264
                                      │
                           MediaCoordinator fan-out
                           ┌──────────┴──────────┐
                           v                     v
                    FLV / RtmpPublisher    MediaMuxer video track
```

Audio follows an equivalent fan-out:

```text
AudioRecord ─> gain/effects ─> MediaCodec AAC ─> MediaCoordinator
                                                ├─> FLV / RTMP
                                                └─> MediaMuxer audio track
```

The compositor owns EGL contexts/surfaces and renders each visible source into every attached output. `MediaCoordinator` owns codec-facing callbacks and forwards identical encoded samples to the currently attached publisher and recorder. Publisher attachment/detachment for reconnect does not create a second scene renderer.

## Ownership and lifecycle

`StudioApplication` creates one `AppContainer`. The container owns repositories, the `BroadcastEngine`, and browser-overlay coordination for the process lifetime.

`StudioViewModel` translates UI actions into repository or service calls. The foreground service sends stream/record/screen commands to that same engine; it does not create an independent encoder. This avoids the UI and service disagreeing about capture ownership.

- Preview can stop when the activity stops only if no encoding session is active.
- Stream and recording states are independent; recording may continue after streaming stops.
- Media is torn down when no publisher/recording consumer remains.
- The service removes foreground state and stops itself after observed work becomes idle.
- **Stop all** requests stream stop, recording finalization, and screen-capture cleanup.

## Stream state machine

```text
OFFLINE ──startStream──> CONNECTING
                           │  ├─server NetStream.Publish.Start──> LIVE
                           │  └─retryable failure──> RECONNECTING
                           │                            │
                           │                            ├─accepted──> LIVE
                           │                            ├─retry──────> RECONNECTING
                           │                            └─exhausted──> ERROR
                           └─invalid/fatal before publish──> ERROR

LIVE / CONNECTING / RECONNECTING ──stopStream──> STOPPING ──> OFFLINE
```

`RtmpPublisher.connect()` returns only after the publish status is accepted. The engine then attaches the publisher and enters `LIVE`. TCP connect, TLS handshake, `connect`, `createStream`, or dispatching `publish` alone is insufficient.

Recording uses a separate state: inactive → active → finalizing → completed/inactive or error. MP4 output is published to the library only after successful finalization.

## Threading and backpressure

- Compose/UI and flow collection: main thread.
- Network connect and selected blocking setup/teardown: IO dispatcher.
- RTMP socket reads/writes: dedicated daemon threads.
- EGL/render operations: dedicated `HandlerThread`.
- Codec output: codec callback threads, with narrow publisher/recorder locks.
- Atomic scene writes: IO dispatcher under a mutex; gestures use main-thread local updates and persist on completion.

The publisher queue is bounded. Dropped packets and rendered/dropped frames feed diagnostics/statistics. Adaptive bitrate changes are bounded by the configured minimum/maximum. There is no unbounded “catch up later” media buffer.

## Security boundaries

### Credentials

`SecureStreamKeyStore` generates an AES key in `AndroidKeyStore`. Preferences contain a random IV plus GCM ciphertext/tag, not the plaintext stream key. Decryption failure caused by key invalidation clears the unusable ciphertext instead of returning misleading data.

Endpoint diagnostics omit the stream key and redact URL query tokens. Application backup is disabled. Keystore encryption is not a defense against a compromised/rooted device.

### RTMPS

The raw RTMP socket is wrapped in an `SSLSocket` after TCP connect. The implementation sets `endpointIdentificationAlgorithm = "HTTPS"` and starts the TLS handshake before RTMP data. It does not install a trust-all manager or disable certificate checks.

### MediaProjection and browser sources

Screen capture requires the framework consent result and a media-projection foreground service. Browser sources are HTTPS-only, have no app JavaScript bridge, and use restricted `WebView` settings solely to produce a texture.

### Platform APIs

Generic RTMP knows nothing about OAuth, chat, or viewer metrics. Provider implementations must use official APIs, approved OAuth flows, and documented scopes. The current UI exposes unavailable status rather than manufacturing integration data.

## Extension points

- Add a platform provider behind `StreamPlatformIntegration`, then explicitly bind its state to the chat/information UI.
- Add a source renderer inside `SceneCompositor` while preserving the shared-output path.
- Add recording-only encoding only as a separately negotiated policy; the current deliberate guarantee is identical encoded samples for RTMP and MP4.
- Add protocol features in the pure RTMP package with standalone tests before connecting them to Android lifecycle code.
