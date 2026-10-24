# Release Instructions

The retained APK is **debug-signed** and is not a release artifact. No release APK/AAB has been generated or verified in this environment.

## 1. Complete acceptance testing first

Do not release solely because `assembleDebug` succeeds. Complete the device, RTMP/RTMPS, recording, MediaProjection, lifecycle, security, and soak checks in [TESTING.md](TESTING.md), and review [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

Update `versionCode` and `versionName` in `app/build.gradle.kts` for every release. Keep release notes specific about untested devices/providers.

## 2. Protect the release key

Generate a dedicated release keystore on a secure development machine. `keytool` prompts for passwords; do not place passwords in shell history or source control.

```bash
keytool -genkeypair -v \
  -keystore /secure/path/mobile-streaming-studio-release.keystore \
  -alias mobile-streaming-studio \
  -keyalg RSA -keysize 4096 -validity 10000
```

Back up the keystore and passwords securely. Losing the signing key can prevent updates; exposing it lets others sign as your app.

Create `keystore.properties` in the repository root:

```properties
storeFile=/secure/path/mobile-streaming-studio-release.keystore
storePassword=YOUR_RELEASE_STORE_PASSWORD
keyAlias=mobile-streaming-studio
keyPassword=YOUR_RELEASE_KEY_PASSWORD
```

`storeFile` may be absolute or relative to the repository root. Both `keystore.properties` and keystore files are ignored by Git. Restrict local file permissions and never commit real values.

## 3. Build a signed release APK

Use JDK 17, SDK 35, and the included wrapper in a normal Android development environment:

```bash
./gradlew clean :app:assembleRelease
```

Release builds enable R8 minification and resource shrinking using `proguard-android-optimize.txt` plus `app/proguard-rules.pro`. Test the minified output independently; debug success does not validate release rules.

With the signing properties present, the expected output is:

```text
app/build/outputs/apk/release/app-release.apk
```

This path is an **expected future build output**, not a currently verified artifact.

Verify structure, package, certificate, and installation:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
aapt2 dump badging app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release package is `com.mobilestreaming.studio` (without the debug suffix).

## 4. Build an Android App Bundle for stores

```bash
./gradlew clean :app:bundleRelease
```

Expected output:

```text
app/build/outputs/bundle/release/app-release.aab
```

Generate signed installable APKs from the AAB with a current official `bundletool` and test those APKs on target devices. Follow the store's current signing/key-management requirements.

## 5. Release review checklist

- [ ] Version code increased; version name and release notes correct.
- [ ] Clean release build completed from a fresh checkout.
- [ ] Release certificate verified and protected; no debug certificate used.
- [ ] R8/shrunk app tested, including scenes, Keystore, MediaStore, codecs, and service startup.
- [ ] Fresh install and upgrade tested; no plaintext legacy stream key remains after migration.
- [ ] No real endpoint, stream key, password, OAuth secret, keystore, or personal recording is bundled.
- [ ] Logs/profile exports/diagnostics reviewed for credentials and personal data.
- [ ] Camera, microphone, notifications, storage, foreground-service, and MediaProjection behavior tested on supported API levels.
- [ ] RTMP and RTMPS tested against documented target services; Live state verified against server acknowledgment.
- [ ] Current Google Play Data Safety, permission, foreground-service, and MediaProjection declarations completed truthfully where distributing there.
- [ ] Platform provider/API terms and current ingest requirements reviewed; no unsupported chat/viewer integration claimed.
- [ ] [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) retained and any new dependencies reviewed.

## 6. Distribution

Distribute only the exact artifact that passed the release checks, with its own newly generated checksum. Do not reuse the debug APK checksum for a release. Record the artifact filename, size, SHA-256, signer certificate, source revision, build time, and test report in the release record.
