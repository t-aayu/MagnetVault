# GitHub Release & Android Signing Guide

This guide explains how to generate an Android release signing keystore, configure GitHub repository settings & Actions secrets, and publish signed MagnetVault releases (`APK` + `AAB` + `SHA256SUMS.txt`).

---

## 1. Release Signing Configuration in `app/build.gradle.kts`

MagnetVault's `release` signing config expects the following environment variables and key alias:

| Setting | Value / Source | Notes |
| --- | --- | --- |
| `KEYSTORE_PATH` | Path to `.jks` file (defaults to `my-upload-key.jks` in repo root) | Never commit `.jks` files (ignored by `.gitignore`). |
| `keyAlias` | `upload` *(fixed in `app/build.gradle.kts`)* | Must match `-alias upload` when generating your keystore. |
| `STORE_PASSWORD` | `System.getenv("STORE_PASSWORD")` | Keystore password. |
| `KEY_PASSWORD` | `System.getenv("KEY_PASSWORD")` | Key password for the `upload` alias. |
| `applicationId` | `com.aistudio.magnetvault.kxprqm` | Must stay constant across upgrades. |

> **Critical:** Keep `my-upload-key.jks` backed up securely offline (password manager / encrypted backup). Losing it breaks in-place upgrades for users who installed an earlier signed APK, unless you use Google Play App Signing.

---

## 2. Generate the Upload Keystore (`my-upload-key.jks`)

Run `keytool` (included with JDK 21) **outside** your Git checkout or in a local path ignored by `.gitignore`:

```bash
keytool -genkeypair -v \
  -keystore my-upload-key.jks \
  -storetype PKCS12 \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias upload
```

You will be prompted for:
1. Keystore password (`STORE_PASSWORD`)
2. Key password (`KEY_PASSWORD` — pressing Enter reuses the keystore password in PKCS12)
3. Distinguished name fields (e.g., `CN=MagnetVault`, `OU=Release`, `O=YourName`, `C=US`)

### Verify Alias & Certificate Fingerprints

Confirm the `upload` alias exists and copy the **SHA-1** and **SHA-256** fingerprints (needed if you configure Firebase Google Sign-In for release builds):

```bash
keytool -list -v -keystore my-upload-key.jks -alias upload
```

If you enable Google Sign-In / Firestore sync for release builds, add both the release **SHA-1** and **SHA-256** fingerprints to your Android app (`com.aistudio.magnetvault.kxprqm`) in **Firebase Console → Project Settings → Your apps**, then download an updated `google-services.json`.

---

## 3. Encode Secrets for GitHub Actions

Convert your keystore (and optional `app/google-services.json`) to single-line Base64 strings:

### Linux
```bash
base64 -w 0 my-upload-key.jks
base64 -w 0 app/google-services.json   # Optional (only if shipping Firebase sync)
```

### macOS
```bash
base64 -b 0 -i my-upload-key.jks
base64 -b 0 -i app/google-services.json
```

### Windows (PowerShell)
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("my-upload-key.jks"))
[Convert]::ToBase64String([IO.File]::ReadAllBytes("app\google-services.json"))
```

---

## 4. GitHub Repository Settings

Configure the following in your GitHub repository:

### A. Actions Secrets (`Settings → Secrets and variables → Actions → Repository secrets`)

| Secret Name | Required? | Description |
| --- | --- | --- |
| `KEYSTORE_BASE64` | Required for signed releases | Single-line Base64 output of `my-upload-key.jks`. |
| `STORE_PASSWORD` | Required for signed releases | Keystore password you entered in `keytool`. |
| `KEY_PASSWORD` | Required for signed releases | Key password for the `upload` alias. |
| `GOOGLE_SERVICES_JSON_BASE64` | Optional | Single-line Base64 of `app/google-services.json`. Omit for a Firebase-free Local Vault release build. |

> If `KEYSTORE_BASE64`, `STORE_PASSWORD`, or `KEY_PASSWORD` are omitted, `.github/workflows/release.yml` falls back to attaching `MagnetVault-<tag>-debug.apk` instead of signed release binaries.

### B. Workflow Permissions (`Settings → Actions → General → Workflow permissions`)

- Select **Read and write permissions** (the workflow also explicitly declares `permissions: contents: write` so `softprops/action-gh-release@v2` can create releases and upload assets).
- Keep **Allow GitHub Actions to create and approve pull requests** disabled unless needed.

### C. Tag & Branch Protection (Recommended)

- Protect `main` with **Require status checks to pass before merging** (`Lint, Unit & Robolectric Tests, Debug APK`, `No-Firebase Build Smoke Check`, `Firestore Security Rules Tests`).
- Optionally protect `v*.*.*` tags via **Settings → Rules → Rulesets** so only maintainers can push release tags.

---

## 5. Triggering a GitHub Release

1. Ensure `versionCode` and `versionName` in `app/build.gradle.kts` and the matching `## [x.y.z]` section in `CHANGELOG.md` are updated and merged into `main`.
2. **Option A — Tag push:**
   ```bash
   git checkout main
   git pull origin main
   git tag -a v2.0.0 -m "MagnetVault v2.0.0"
   git push origin v2.0.0
   ```
3. **Option B — Manual workflow dispatch:**
   - Open **Actions → Release Android App → Run workflow**, enter `release_tag` (e.g., `v2.0.0`), and optionally enable **Create as draft release** or **Mark as pre-release**.

The workflow runs `:app:lintDebug` and `:app:testDebugUnitTest`, builds & cryptographically verifies `MagnetVault-<tag>-release.apk` and `MagnetVault-<tag>-release.aab`, computes `SHA256SUMS.txt`, extracts release notes from `CHANGELOG.md` + GitHub PR categories (`.github/release.yml`), and publishes the GitHub Release.
