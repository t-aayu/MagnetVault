package com.example.data.network

/**
 * Explicit bounded network budgets for MagnetVault multi-provider search, failover, and magnet resolution.
 *
 * Chosen limits & rationale:
 * - [wholeSearchDeadlineMs] = 20_000 ms: Finite per-session wall-clock ceiling so every enabled
 *   provider reaches a terminal state without hanging the UI when dead mirrors tarpit connections.
 * - [fastFailoverAttemptTimeoutMs] = 7_500 ms & [defaultAttemptTimeoutMs] = 10_000 ms:
 *   Tighter budget per mirror on Cloudflare-prone indexers (1337x) triggers fallback within seconds
 *   while leaving room inside the 20 s whole-search deadline for alternate mirrors.
 * - [magnetResolveTimeoutMs] = 7_500 ms: Bounds each lazy magnet detail-page/index lookup.
 * - [maxConcurrentProviderCalls] = 4: Caps simultaneous provider network pipelines via semaphore so
 *   sockets, mobile radio wake-locks, and local DoH lookups remain bounded while fast sources still finish first.
 * - [maxResponseBytes] = 2 MiB (2_097_152 bytes): Streaming response size cap protecting heap memory
 *   against runaway/malformed HTML or JSON bodies.
 * - [maxRateLimitDelayMs] = 2_500 ms: Maximum HTTP 429 Retry-After wait honored inside a mirror attempt
 *   before failing over to an alternate mirror or emitting a rate-limited terminal outcome.
 */
data class SearchNetworkLimits(
    val wholeSearchDeadlineMs: Long = 20_000L,
    val fastFailoverAttemptTimeoutMs: Long = 7_500L,
    val defaultAttemptTimeoutMs: Long = 10_000L,
    val magnetResolveTimeoutMs: Long = 7_500L,
    val maxConcurrentProviderCalls: Int = 4,
    val maxResponseBytes: Long = 2L * 1024L * 1024L,
    val maxRateLimitDelayMs: Long = 2_500L
)
