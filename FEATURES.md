# Feature Inventory

**Status terminology:** “Implemented” means the source path exists and was included in the successful debug build. It does not mean the feature has passed hardware or live-service testing. See [TESTING.md](TESTING.md) and [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

## Phase 1: capture, publish, state, recording

| Area | Implemented behavior |
|---|---|
| Camera | Camera2 capture into a `SurfaceTexture`; front/back selection and switching; preview lifecycle separated from active broadcast/recording sessions. |
| Camera controls | Digital zoom, exposure compensation, tap/double-tap focus, torch, and front-camera mirror preference. Values are clamped to reported device capabilities. |
| Microphone | `AudioRecord` capture, mono/stereo configuration, 44.1/48 kHz settings, input level metering, mute, and 0–2× gain. |
| Audio processing | Optional noise suppression and automatic gain control using Android audio effects; changes are applied while running when supported. |
| Video encoding | Hardware `MediaCodec` H.264 with configurable dimensions, frame rate, bitrate, profile, and keyframe interval. |
| Audio encoding | `MediaCodec` AAC with configurable bitrate, sample rate, and channel count. |
| Publishing | Real RTMP/RTMPS TCP/TLS connection, RTMP handshake echo validation, `connect`, `createStream`, and `publish`, AMF0 metadata, chunk streams, and FLV H.264/AAC tags. |
| TLS | RTMPS uses the platform trust store and enables TLS endpoint identification/hostname verification. |
| Backpressure | A bounded 256-message publisher queue; overflow is counted as dropped network packets rather than growing memory without limit. |
| Recovery | Configurable connect/read timeout and bounded reconnect attempts with linearly increasing, capped delay. |
| Adaptive output | Optional bounded bitrate reduction under network pressure and bitrate reduction on severe thermal pressure. |
| Stream state | Explicit offline/connecting/live/reconnecting/stopping/error model. `LIVE` requires the server's `NetStream.Publish.Start` status. |
| Local recording | `MediaMuxer` MP4 with H.264 video and AAC audio in `Movies/MobileStreamingStudio`; API 29+ uses scoped `MediaStore` pending entries. |
| Shared output | `MediaCoordinator` fans out the same encoded video/audio samples to RTMP and the MP4 muxer. It does not record a substitute preview. |
| Recording integrity | Recording starts on a video keyframe, requires both audio and video samples, and discards incomplete output on abort/finalization failure. |

## Scenes, sources, and overlays

- Six original starter scenes: **Main Camera**, **Starting Soon**, **Interview**, **Screen Presentation**, **BRB**, and **Ending Soon**.
- Create, rename, duplicate, reorder, switch, and delete scenes; the final remaining scene cannot be deleted.
- Ordered source layers with select, visibility, lock, rename, reorder, and delete operations.
- Source kinds: camera, screen, image, text, logo, lower-third, breaking-news banner, ticker, clock, date, browser, and solid color.
- Drag/scale/rotate gestures in the studio preview, plus opacity, crop, text, color, font scale, and camera-source mirror properties.
- Gesture updates update the active composition immediately and persist when the gesture ends.
- Imported images are copied into app-managed storage so persisted scenes do not depend on a temporary document-picker grant.
- Landscape 16:9, portrait 9:16, and square 1:1 output configurations.
- Scene changes do not require restarting the encoder.

## Audio and settings

- Persisted server URL, encoder settings, reconnect policy, quality preset, appearance, default scene, default overlay, mirror preference, and audio mix.
- Low, balanced, high, and custom quality presets with validated bounds.
- Keystore-backed AES-256-GCM stream-key storage; a legacy plaintext preference is migrated and removed.
- Scene profile JSON export/import with format/version checks and limits on bytes, scenes, sources, text, and URIs.
- Scene exports do not include stream credentials.
- Bounded diagnostic log with credential/query redaction and export through the Storage Access Framework.

## Recording library

- Completed recording metadata persisted locally.
- Play through an external video-capable activity, share the content URI, or delete the underlying MediaStore item.
- Library size and per-item duration/size display.
- Incomplete recordings are not presented as completed entries.

## Background and system integration

- Application-scoped broadcast engine shared by the UI and non-exported `BroadcastService`.
- Foreground-service types for camera, microphone, and media projection, with a low-importance status notification and **Stop all** action.
- Recording can continue while the stream is stopped; stopping the app UI does not silently replace an active session with preview-only state.
- MediaProjection is started only with the result code and consent `Intent` returned by Android.
- Screen capture supplies frames to the same compositor; microphone capture remains separate.

## Browser source security

The optional browser source is a low-frame-rate texture, not the application UI. It currently provides:

- HTTPS-only URL validation.
- Same-origin main-frame navigation restriction.
- JavaScript disabled by default and no JavaScript interfaces/app bridges.
- File/content access, mixed content, cookies, third-party cookies, zoom, and media autoplay restrictions.
- Safe Browsing enabled and software bitmap capture into the compositor.

## Platform boundaries

`StreamPlatformIntegration` defines connection state, chat messages, and nullable viewer count contracts for future official-API/OAuth implementations.

There is deliberately **no production YouTube, Twitch, Facebook, or other provider implementation** in this version:

- Stream title/description/category/destination are saved locally.
- The UI reports that no chat provider is connected.
- No sample chat messages, fake account connection, or simulated viewer metrics are shown.
- Generic RTMP/RTMPS publishing can still target an ingest URL and key supplied by a platform, subject to that platform's current requirements.

## Originality

The interface, scene presets, vector launcher/notification assets, protocol implementation, and media pipeline are original to this project. No Streamlabs/OBS protected branding or assets are copied, and no RootEncoder dependency is included.
