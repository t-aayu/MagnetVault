# Changelog

All notable changes to **MagnetVault** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - 2026-11-26

### Added
- **MagnetVault 2.0 App Shell & Progressive Search (Phase 1)**:
  - Three-tab responsive navigation (`Search`, `Vault`, `Sites`) with `Settings` in the top app bar and adaptive bottom bar / side navigation rail.
  - Progressive multi-indexer search streaming, per-source status & retry cards, local filter/sort without redundant network calls, `btih`/`btmh` canonical release grouping, and shared `MagnetActionModalSheet`.
  - Typed `SavedStateHandle` persistence for submitted query, filters, and selected release group without storing result lists in saved state.
- **Local-First Room Vault & Opt-In Sync (Phase 2)**:
  - Durable **Room** database (`MagnetVaultDatabase` with exported schemas) for bookmarks, provider listing aliases, local-only collections, search history, custom sites, and mirror configs.
  - Atomic, idempotent v1.x DataStore-to-Room legacy migration with error recovery and manual **Vault → Export JSON** preview & export.
  - Sync decoupled from Google sign-in: signing in keeps Cloud Sync **OFF** until reviewed via a merge preview dialog, backed by a durable sync outbox and revision CAS conflict resolution.
- **Sites Browser, Share Intake, Privacy & Release Readiness (Phase 3)**:
  - Redesigned **Sites** directory (pinned + local custom sites with add/edit/remove) and modular hardened WebView chrome (`BrowserUrlPolicy`, `SitesBrowserSessionState`, `DownloadHandoffSheet`).
  - Retained WebView page and back/forward history across app-tab switches with layered `BackHandler` dismissal (sheet -> WebView history -> app navigation) and explicit session disposal.
  - Single parsed-URL policy defaulting to HTTPS with scoped HTTP mirror exceptions, blocked `file:`/`content:`/unsafe schemes, mixed content disabled, and terminal TLS certificate errors.
  - Pre-chooser **Download Ready** sheet displaying hostname, filename, and browser cookie/session boundary notices prior to launching external download choosers.
  - Android `ACTION_SEND` (`text/plain`) share-sheet intake (`singleTop`, cold start & warm `onNewIntent`, recreation deduplication, multi-magnet picker, unsupported share notice), while excluding MagnetVault from outbound sharing targets.
  - Reorganized **Settings** into 5 sections (**Appearance**, **Search sources**, **Open with**, **Privacy and DNS**, **Account and sync**), separating HTTP reachability checks from parser verification (`NEVER_RUN`, `VERIFIED`, `FAILED`) with timestamps, and surfacing live DoH telemetry (`Working`, `Blocked`, `System Fallback`, `Invalid Config`).
  - Conservative `backup_rules.xml` and `data_extraction_rules.xml` excluding Room DB, preferences, and WebView caches from automatic cloud backups/device transfer.
  - Updated CI & release workflows (`lintDebug`, full unit/Robolectric suite, no-Firebase smoke check, Firestore emulator job, separate live-indexer smoke workflow, `SHA256SUMS.txt` checksums), and bumped `versionCode = 2` (`versionName = "2.0.0"`).

## [1.0.0] - 2026-11-22

### Added
- **Multi-Indexer Torrent Search**: Parallel search across 1337x, EZTV, YTS, ThePirateBay, Nyaa, and LimeTorrents.
- **In-App Sites Browser & Magnet Capture**: Embedded hardened browser for preset and custom torrent mirrors.
- **App-Wide Custom DNS (DoH)**: Built-in RFC 8484 DoH resolver and ad/tracker filtering.
- **Local Mode & Optional Google Sign-In Sync**: Initial DataStore persistence and Firestore sync support.
