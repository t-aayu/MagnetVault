# MagnetVault

**MagnetVault** (`v2.0.0`) is a local-first Android multi-indexer torrent search, magnet handoff, and hardened browser utility built with **Kotlin**, **Jetpack Compose (Material 3)**, and **Room**. Search across indexers with progressive streaming and per-source failover, inspect canonical deduplicated releases, manage offline Vault collections with optional opt-in Google sync, receive shared text via Android's share sheet, and browse sites inside a hardened in-app WebView with encrypted DoH DNS filtering.

---

## Features

- **Progressive Multi-Indexer Search**: Query **1337x** (index + direct mirror failover), **EZTV**, **YTS**, **ThePirateBay**, **Nyaa**, and **LimeTorrents** concurrently with partial result streaming, per-source status & retry cards, local filtering/sorting without redundant network requests, and canonical info-hash (`btih` / `btmh`) grouping.
- **Local-First Room Vault & Migration**:
  - Offline-ready **Room** storage for Saved Bookmarks (with provider listing aliases and upgradeable unresolved listings), local-only **Collections**, Submitted Search History, **Custom Sites**, and Mirror Configurations.
  - Idempotent, transactional upgrade migration from legacy v1.x DataStore records with non-blocking recovery banner and atomic schema exports (`app/schemas/`).
  - Portable manual **Vault → Export JSON** preview and share/copy export.
- **Optional Opt-In Cloud Sync**:
  - Works completely without sign-in or Firebase configuration.
  - Signing in with Google never uploads local data automatically—sync stays **OFF** until explicitly reviewed in **Settings → Account and sync** via a merge preview dialog (with Search History & Mirror sync defaulting to OFF).
- **Hardened Sites Browser & Magnet Capture**:
  - **Sites** opens to **Pinned Sites** and local **Custom Sites** (add, edit, remove) and retains active WebView page & history across app-tab switches.
  - Single parsed-URL policy applied to both navigation and redirects: HTTPS by default, explicit scoped HTTP mirror exceptions, blocked `file:` / `content:` / third-party schemes, no mixed content, and terminal TLS certificate errors.
  - Capturing `magnet:?` links opens the shared **Magnet Action Sheet**.
- **Pre-Chooser Download Handoff & Android Share Sheet (`ACTION_SEND`)**:
  - Share plain text containing one or multiple `magnet:?` links into MagnetVault from any app (supports cold start, warm `onNewIntent`, and recreation deduplication). Outbound sharing excludes MagnetVault's own intake activity.
  - Browser download links open a pre-chooser **Download Ready** sheet displaying the target hostname, sanitized filename, and cookie/session boundary notice before invoking Android's download chooser (**1DM**, **IDM+**, **ADM**, **FDM**, or browser).
- **App-Wide Custom DNS (DoH) & Privacy**:
  - Built-in RFC 8484 DoH resolver (AdGuard DNS default, Mullvad Adblock, Control D, Cloudflare, Custom DoH, System Default) wired into OkHttp search/mirror requests and WebView `shouldInterceptRequest` DNS-based request filtering, with automatic **System DNS fallback** when DoH is unreachable and live telemetry (`Working`, `Blocked`, `System Fallback`, `Invalid Config`).
  - Conservative Android Auto Backup / Device Transfer rules (`backup_rules.xml`, `data_extraction_rules.xml`) exclude Room DB, preferences, and WebView caches.

---

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material Design 3 + Window-size adaptive navigation (`NavigationBar` / `NavigationRail`)
- **Local Persistence**: Room + Jetpack DataStore Preferences
- **Networking & DNS**: OkHttp + custom RFC 8484 DNS-over-HTTPS resolver (`CustomAppDns`)
- **Optional Cloud Sync**: Firebase Authentication (Google Sign-In via Credential Manager) + Cloud Firestore
- **Android SDK**: `minSdk = 24` (Android 7.0+), `compileSdk = 36`, `targetSdk = 36`
- **Build Tooling**: Gradle `9.3.1` (`gradle` CLI), JDK `21`

---

## Building & Testing Locally

### Prerequisites

1. **JDK 21** (recommended: Eclipse Temurin 21)
2. **Android SDK** with Platform 36 (`android-36`) and Build-Tools 36
3. **Gradle 9.3.1** on `PATH` (or `./gradlew` if using the Gradle wrapper locally)

### 1. Prepare the Debug Keystore

```bash
# macOS / Linux
base64 -d debug.keystore.base64 > debug.keystore

# Windows (PowerShell)
[IO.File]::WriteAllBytes("debug.keystore", [Convert]::FromBase64String((Get-Content -Raw "debug.keystore.base64")))
```

### 2. Lint, Test, and Build Debug APK

```bash
gradle :app:lintDebug
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`

### 3. Build Without Firebase (Local-Only Smoke Check)

Remove or omit `app/google-services.json` and run:

```bash
gradle :app:assembleDebug
```

MagnetVault compiles cleanly and runs 100% locally in **Local Vault** mode without Firebase configuration.

### 4. Build Signed Release APK & App Bundle (AAB)

```bash
export KEYSTORE_PATH="$(pwd)/my-upload-key.jks"
export STORE_PASSWORD="your-keystore-password"
export KEY_PASSWORD="your-key-password"

gradle :app:assembleRelease :app:bundleRelease
```

Outputs:
- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`

### 5. Firestore Rules Verification (Optional)

```bash
FIRESTORE_EMULATOR_HOST=127.0.0.1:8085 node --test firestore.test.js
```

---

## Release Checklist (`v2.0.0`)

1. Verify `versionCode` and `versionName` in `app/build.gradle.kts` and update `CHANGELOG.md`.
2. Run `gradle :app:lintDebug`, `gradle :app:testDebugUnitTest`, and `gradle :app:assembleDebug` (both with and without `app/google-services.json`).
3. Verify Firestore security rules against the local emulator (`firestore.test.js`).
4. In CI/release environment with upload keystore secrets (`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`), run `:app:assembleRelease :app:bundleRelease` and verify `SHA256SUMS.txt`.
5. Run physical-device checks before publishing:
   - Signed APK/AAB install & **in-place upgrade** over v1.0 (`versionCode = 1`) verifying Room migration, collection preservation, and release signing lineage.
   - Real WebView interactive flows (Cloudflare sites, HTTP mirror exception, Seedr/TorBox download handoff).
   - System `ACTION_SEND` share-sheet end-to-end launch from another app.

---

## Disclaimer & License

MagnetVault is a search aggregator and browser utility. It does not host, store, or distribute any torrent files or media content.

Licensed under the [MIT License](LICENSE).
