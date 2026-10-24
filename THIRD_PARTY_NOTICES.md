# Third-Party Notices

Mobile Streaming Studio uses original application branding, vector launcher/notification assets, scene presets, protocol code, and media-pipeline code. No Streamlabs or OBS protected branding/assets are included. No RootEncoder library or binary is bundled.

## Runtime/build dependencies

The cached Maven POMs used for the successful build identify the following license families:

| Components | License |
|---|---|
| AndroidX Core, Activity, Lifecycle, Compose UI/Foundation/Material3/Material Icons, annotations, collections | Apache License 2.0 |
| Kotlin standard library and Kotlin Gradle tooling | Apache License 2.0 |
| kotlinx-coroutines | Apache License 2.0 |
| Android Gradle Plugin and Android build tooling | Apache License 2.0 |
| Gradle | Apache License 2.0 |
| JUnit 4 (test-only dependency) | Eclipse Public License 1.0 |
| AndroidX Test / Espresso (instrumentation-only dependencies) | Apache License 2.0 |

Apache License 2.0 text: <https://www.apache.org/licenses/LICENSE-2.0>

Eclipse Public License 1.0 text: <https://www.eclipse.org/legal/epl-v10.html>

The application also calls Android platform APIs supplied by the device; those platform binaries are not redistributed as part of the source.

## Dependency policy for new work

Before adding a library or asset:

1. Confirm the exact API and platform behavior from maintained documentation/source.
2. Confirm the license and redistribution/notice obligations for the exact version.
3. Keep credentials and platform-specific authorization outside generic RTMP code.
4. Do not add a second, disconnected preview/rendering path for outgoing video.
5. Do not add protected third-party branding or imply provider certification.

Update this file and release notices when dependencies change.
