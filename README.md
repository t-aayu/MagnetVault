# MagnetVault

**MagnetVault** is a modern Android multi-indexer torrent search and magnet utility built with **Kotlin** and **Jetpack Compose (Material 3)**. Search across multiple torrent indexers simultaneously, inspect swarm health, open live site pages directly inside an ad-blocked in-app browser, copy magnets or hand them off to **Seedr.cc**, **TorBox**, local torrent clients, or external download managers (**1DM**, **IDM+**, **ADM**, **FDM**, or any browser).

---

## Features

- **Multi-Indexer Search**: Query **1337x** (Cloudflare-resilient index + direct mirror failover), **EZTV**, **YTS**, **ThePirateBay**, **Nyaa**, and **LimeTorrents** in parallel.
- **Smart Filtering & Swarm Telemetry**: Filter by category (Movies, TV, Anime, Games, Apps, Music), quality tags (`4K`, `1080p`, `720p`, `HEVC`, `HDR`), sort by seeders, leechers, size, or upload date, and view color-coded swarm health badges.
- **One-Tap Website Search Chips & Error Fallback**: Tap any indexer chip at the top of search results (`1337x`, `EZTV`, `YTS`, `TPB`, `Nyaa`, `Lime`) to jump directly to that website's live search results—or use the inline fallback banner whenever an indexer returns an error. Choose **In-App HTML** or **Default Browser** in Settings.
- **In-App Sites Browser & Magnet Capture**:
  - Browse preset torrent sites or add your own custom mirrors inside the **Sites** tab.
  - Tapping any `magnet:` link opens a native capture sheet to **Copy Magnet**, open **Seedr.cc** / **TorBox**, launch a local torrent app, or share.
- **Seedr.cc & TorBox Handoff + Download Manager Chooser**:
  - Choose in Settings whether **Seedr.cc** and **TorBox** open **In-App** or in your **Default Browser** (automatically copying the magnet link first).
  - Downloading files from Seedr, TorBox, or any in-app site launches Android's native **bottom intent chooser** so you can send the download straight to **1DM / IDM+**, **ADM**, **FDM**, **VLC**, or another browser—plus a quick sheet to copy or share the direct download link.
- **App-Wide Custom DNS (DNS-over-HTTPS Ad-Blocker)**:
  - Built-in encrypted RFC 8484 DoH resolver wired into both app searches and the in-app WebView to drop ad, tracker, and pop-under domains before they load.
  - Presets for **AdGuard DNS** (default), **Mullvad Adblock**, **Control D**, **Cloudflare**, **System Default**, or any **Custom HTTPS DoH** URL/hostname (such as **NextDNS**), complete with a live **Test DNS** checker and real-time blocked-ads counter.
- **Live Mirror Editor & Health Checker**: Enable/disable indexers, add custom mirror URLs, ping all mirrors for live latency, and promote the fastest mirror.
- **Local Mode & Optional Cloud Sync**: Use the app completely without signing in (bookmarks, search history, DNS, and mirrors stored locally via Jetpack DataStore), or optionally sign in with Google to sync your Vault and mirror configurations across devices via Firebase Firestore.
- **Obsidian Dark & Alabaster Light Themes**: Dark-first Material 3 interface with one-tap light mode toggle and adaptive tablet/foldable navigation rail.

---

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material Design 3
- **Networking & DNS**: OkHttp, Retrofit, Moshi, custom RFC 8484 DNS-over-HTTPS resolver
- **Local Persistence**: Jetpack DataStore Preferences
- **Optional Cloud Sync**: Firebase Authentication (Google Sign-In via Credential Manager) + Cloud Firestore
- **Android SDK**: `minSdk = 24` (Android 7.0+), `compileSdk = 36`, `targetSdk = 36`
- **Build Tooling**: Gradle `9.3.1`, Java `11` bytecode compatibility (JDK `17` or `21` recommended for running Gradle)

---

## Building the App Yourself

### Prerequisites

1. **JDK 17 or JDK 21** (recommended: Eclipse Temurin 21)
2. **Android SDK** with:
   - Android SDK Platform 36 (`android-36`)
   - Android SDK Build-Tools 36
3. **Android Studio** (Narwhal / Meerkat or newer) **or** **Gradle 9.3.1** installed on your `PATH`.

### 1. Clone the Repository

```bash
git clone https://github.com/<your-username>/<your-repo>.git
cd <your-repo>
```

### 2. Prepare the Debug Keystore

The debug build configuration expects `debug.keystore` in the repository root. Decode the included base64 template (or generate a standard debug keystore):

```bash
# macOS / Linux
base64 -d debug.keystore.base64 > debug.keystore

# Windows (PowerShell)
[IO.File]::WriteAllBytes("debug.keystore", [Convert]::FromBase64String((Get-Content -Raw "debug.keystore.base64")))
```

### 3. Build a Debug APK

Using **Android Studio**:
- Open the project folder in Android Studio, wait for Gradle sync to finish, and click **Build → Build Bundle(s) / APK(s) → Build APK(s)** (or press **Run** on a connected device/emulator).

Using the **Command Line** (with Gradle 9.3.1 installed):

```bash
gradle :app:assembleDebug
```

Your debug APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### 4. Build a Signed Release APK & App Bundle (AAB)

To build a release APK for sideloading or an AAB for distribution, generate your own upload keystore (never commit this file to Git):

```bash
keytool -genkeypair -v \
  -keystore my-upload-key.jks \
  -alias upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

> **Note:** Keep the alias as `upload` (matching `app/build.gradle.kts`).

Export your signing environment variables and build:

```bash
export KEYSTORE_PATH="$(pwd)/my-upload-key.jks"
export STORE_PASSWORD="your-keystore-password"
export KEY_PASSWORD="your-key-password"

# Build signed Release APK
gradle :app:assembleRelease

# Build signed Android App Bundle (AAB)
gradle :app:bundleRelease
```

Outputs:
- **Release APK**: `app/build/outputs/apk/release/app-release.apk`
- **Release AAB**: `app/build/outputs/bundle/release/app-release.aab`

### 5. Optional: Configure Your Own Firebase Project (Cloud Sync)

MagnetVault works **out of the box in Local Mode** without any Firebase setup. If you want Google Sign-In and Firestore cloud sync in your own build:

1. Create a Firebase project in the [Firebase Console](https://console.firebase.google.com/).
2. Register an Android app with package name `com.aistudio.magnetvault.kxprqm` (or update `applicationId` in `app/build.gradle.kts`), add your SHA-1 / SHA-256 signing fingerprints, and download `google-services.json` into `app/google-services.json`.
3. Enable **Google Sign-In** in Firebase Authentication and deploy `firestore.rules` to your Firestore database.

### 6. Run Unit Tests

```bash
gradle :app:testDebugUnitTest --tests com.example.ExampleUnitTest
```

---

## Automated GitHub Releases

This repository includes ready-to-use GitHub Actions workflows in `.github/workflows/`:

- **CI (`ci.yml`)**: Runs unit tests and builds a debug APK on every push to `main` and pull request.
- **Release (`release.yml`)**: Automatically builds, checksums (`SHA256SUMS.txt`), and publishes a GitHub Release whenever you push a version tag (`v*.*.*`) or trigger the workflow manually from the **Actions** tab.

### Setting Up GitHub Repository Secrets (for Signed Releases)

In your GitHub repository, go to **Settings → Secrets and variables → Actions** and add:

| Secret Name | Required | Description |
| --- | --- | --- |
| `KEYSTORE_BASE64` | For signed release | Base64-encoded contents of your `my-upload-key.jks` (`base64 -w 0 my-upload-key.jks`) |
| `STORE_PASSWORD` | For signed release | Password for your upload keystore |
| `KEY_PASSWORD` | For signed release | Password for the `upload` key alias |
| `GOOGLE_SERVICES_JSON_BASE64` | Optional | Base64-encoded `app/google-services.json` for Firebase cloud sync |

> If signing secrets are not yet configured, the release workflow will still succeed and attach a clearly labeled debug APK so you can test releases immediately.

### Publishing a New Release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts` and update `CHANGELOG.md`.
2. Commit your changes and push a version tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

GitHub Actions will automatically build the APK/AAB, generate SHA-256 checksums, and create the release under **Releases**.

---

## Disclaimer

MagnetVault is a search aggregator and browser utility. It does not host, store, or distribute any torrent files or media content. Users are solely responsible for complying with applicable laws in their jurisdiction.

## License

Licensed under the [MIT License](LICENSE).
