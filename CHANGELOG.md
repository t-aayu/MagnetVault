# Changelog

All notable changes to **MagnetVault** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-11-22

### Added
- **Multi-Indexer Torrent Search**: Parallel search across 1337x (Cloudflare-resilient index + direct mirror failover), EZTV, YTS, ThePirateBay, Nyaa, and LimeTorrents.
- **Category, Quality & Sort Controls**: Category chips, quality tags (`4K`, `1080p`, `720p`, `HEVC`, `HDR`), sorting by seeders/leechers/size/date, and swarm health badges.
- **Clickable Search Website Chips & Error Fallback**: Tap any indexer chip at the top of search results to open its live HTML search page, plus an automatic fallback banner on site errors with configurable **In-App HTML** or **Default Browser** mode.
- **In-App Sites Browser & Magnet Capture**: Embedded hardened browser for preset and custom torrent mirrors with automatic `magnet:` link capture.
- **Seedr.cc & TorBox Integration + Download Manager Chooser**: One-tap copy-and-open for Seedr.cc and TorBox (in-app or default browser) with automatic Android bottom intent chooser when downloading files (1DM, IDM+, ADM, FDM, VLC, or browsers).
- **App-Wide Custom DNS (DNS-over-HTTPS Ad-Blocker)**: Built-in RFC 8484 DoH resolver with AdGuard DNS, Mullvad Adblock, Control D, Cloudflare, Custom DoH (e.g. NextDNS), System Default, live **Test DNS**, and live in-app ad-blocking counter.
- **Live Mirror Editor & Health Checker**: Add/remove mirrors, ping all endpoints for latency, and promote the fastest mirror.
- **Local Mode & Optional Google Sign-In Sync**: Full local-only mode via Jetpack DataStore plus optional Google Sign-In Firestore cloud sync for bookmarks, history, and mirrors.
- **Obsidian Dark & Alabaster Light Themes**: Material 3 dark-first UI with light theme toggle and adaptive tablet/foldable navigation rail.
