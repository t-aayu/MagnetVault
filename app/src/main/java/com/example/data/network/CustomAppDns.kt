package com.example.data.network

import android.net.Uri
import com.example.model.CustomDnsConfig
import com.example.model.CustomDnsPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class DnsRuntimeStatus(val label: String) {
    WORKING("Working"),
    BLOCKED("Blocked"),
    FALLBACK("System Fallback"),
    INVALID_CONFIG("Invalid Config"),
    SYSTEM_DEFAULT("System Default")
}

data class DnsDiagnosticsSnapshot(
    val status: DnsRuntimeStatus = DnsRuntimeStatus.WORKING,
    val activePreset: CustomDnsPreset = CustomDnsPreset.ADGUARD,
    val blockedCount: Int = 0,
    val fallbackCount: Int = 0,
    val lastFallbackReason: String? = null,
    val updatedAtEpochMs: Long? = null
)

data class DnsPacketParseResult(
    val rcode: Int,
    val addresses: List<InetAddress>,
    val isSinkholed: Boolean
) {
    val isBlocked: Boolean
        get() = rcode == 3 || rcode == 5 || isSinkholed
}

data class CustomDnsTestResult(
    val endpoint: String,
    val reachable: Boolean,
    val latencyMs: Long,
    val adBlockingVerified: Boolean,
    val status: DnsRuntimeStatus,
    val message: String
)

/**
 * App-wide DNS-over-HTTPS (RFC 8484) resolver and DNS-based host filter.
 * - Wired into [HttpClientFactory] so all indexer searches and mirror checks resolve via Custom DoH,
 *   falling back to System DNS if the configured DoH endpoint is unreachable.
 * - Queried by [com.example.ui.browser] inside WebView.shouldInterceptRequest for DNS-based request
 *   filtering so blocked ad/tracker domains are dropped before loading.
 */
object CustomAppDns : Dns {
    private val DOH_MEDIA_TYPE = "application/dns-message".toMediaType()
    private const val CACHE_TTL_VALID_MS = 5 * 60 * 1000L
    private const val CACHE_TTL_BLOCKED_MS = 3 * 60 * 1000L
    private const val MAX_CACHE_ENTRIES = 500

    private val CLOUDFLARE_ESSENTIAL_HOSTS = setOf(
        "challenges.cloudflare.com",
        "static.cloudflareinsights.com"
    )

    private data class CachedEntry(
        val addresses: List<InetAddress>,
        val isBlocked: Boolean,
        val expiresAtMs: Long
    )

    // Dedicated bootstrap client using System DNS so DoH requests never recurse into CustomAppDns.
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(3500, TimeUnit.MILLISECONDS)
            .writeTimeout(3500, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @Volatile
    private var activeConfig: CustomDnsConfig = CustomDnsConfig(preset = CustomDnsPreset.ADGUARD)

    @Volatile
    private var activeDohHost: String = extractHostLower(CustomDnsPreset.ADGUARD.dohUrl)

    @Volatile
    internal var wireQueryOverride: ((hostname: String, dohUrl: String) -> DnsPacketParseResult?)? = null

    @Volatile
    internal var systemDnsOverride: Dns = Dns.SYSTEM

    private val cache = ConcurrentHashMap<String, CachedEntry>()
    private val inFlightLocks = ConcurrentHashMap<String, Any>()

    private val _diagnostics = MutableStateFlow(DnsDiagnosticsSnapshot())
    val diagnostics: StateFlow<DnsDiagnosticsSnapshot> = _diagnostics.asStateFlow()

    fun currentConfig(): CustomDnsConfig = activeConfig

    internal fun resetForTesting() {
        wireQueryOverride = null
        systemDnsOverride = Dns.SYSTEM
        cache.clear()
        configure(CustomDnsConfig(preset = CustomDnsPreset.ADGUARD))
        _diagnostics.value = DnsDiagnosticsSnapshot()
    }

    fun configure(config: CustomDnsConfig) {
        val isInvalidCustom = config.preset == CustomDnsPreset.CUSTOM &&
            validateAndNormalizeDohEndpoint(config.customEndpoint) == null
        val sanitizedCustom = if (config.preset == CustomDnsPreset.CUSTOM) {
            validateAndNormalizeDohEndpoint(config.customEndpoint).orEmpty()
        } else {
            config.customEndpoint
        }
        val normalized = config.copy(customEndpoint = sanitizedCustom)
        val changed = normalized != activeConfig
        activeConfig = normalized
        activeDohHost = extractHostLower(normalized.activeDohUrl)
        if (changed) {
            cache.clear()
        }
        val initialStatus = when {
            isInvalidCustom -> DnsRuntimeStatus.INVALID_CONFIG
            normalized.preset == CustomDnsPreset.SYSTEM -> DnsRuntimeStatus.SYSTEM_DEFAULT
            !normalized.isEnabled -> DnsRuntimeStatus.INVALID_CONFIG
            else -> DnsRuntimeStatus.WORKING
        }
        _diagnostics.value = DnsDiagnosticsSnapshot(
            status = initialStatus,
            activePreset = normalized.preset,
            blockedCount = if (changed) 0 else _diagnostics.value.blockedCount,
            fallbackCount = if (changed) 0 else _diagnostics.value.fallbackCount,
            lastFallbackReason = if (initialStatus == DnsRuntimeStatus.INVALID_CONFIG) {
                "Custom DoH endpoint is empty or not a valid public https:// resolver"
            } else {
                null
            },
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    /**
     * Validates and normalizes a user-supplied Custom DNS-over-HTTPS endpoint.
     * Accepts either a full https:// URL (e.g. https://dns.nextdns.io/abc123) or a bare DoH hostname
     * (e.g. dns.adguard-dns.com -> https://dns.adguard-dns.com/dns-query).
     * Rejects non-HTTPS schemes, embedded credentials, whitespace, and private/loopback hosts.
     */
    fun validateAndNormalizeDohEndpoint(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        val lower = trimmed.lowercase(Locale.US)
        val hasExplicitScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(trimmed)
        if (hasExplicitScheme && !lower.startsWith("https://")) {
            return null
        }
        val candidate = if (lower.startsWith("https://")) {
            trimmed
        } else {
            val bare = trimmed.trimEnd('/')
            if (bare.contains('/')) "https://$bare" else "https://$bare/dns-query"
        }
        val parsed = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("https", ignoreCase = true)) return null
        if (!parsed.userInfo.isNullOrEmpty()) return null
        val host = parsed.host?.trim()?.lowercase(Locale.US) ?: return null
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.') || host.length > 253) {
            return null
        }
        if (isPrivateOrLocalHost(host)) return null
        return candidate
    }

    private fun isPrivateOrLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost")) {
            return true
        }
        if (host == "::1" || host == "0.0.0.0" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")) {
            return true
        }
        val ipv4Parts = host.split('.')
        if (ipv4Parts.size == 4 && ipv4Parts.all { it.toIntOrNull() in 0..255 }) {
            val a = ipv4Parts[0].toInt()
            val b = ipv4Parts[1].toInt()
            if (a == 0 || a == 10 || a == 127) return true
            if (a == 169 && b == 254) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
        }
        return false
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val cleanHost = hostname.trim().lowercase(Locale.US).trimEnd('.')
        val cfg = activeConfig
        if (cfg.preset == CustomDnsPreset.CUSTOM && cfg.activeDohUrl.isBlank()) {
            recordFallback(cfg.preset, "Invalid DoH configuration; using System DNS")
            return systemDnsOverride.lookup(hostname)
        }
        if (!cfg.isEnabled || cleanHost.isBlank() || cleanHost == activeDohHost || isIpLiteral(cleanHost)) {
            return systemDnsOverride.lookup(hostname)
        }

        val entry = resolveWithCache(cleanHost, cfg.activeDohUrl)
        if (entry != null) {
            if (entry.isBlocked) {
                recordBlockedHost(cfg.preset)
                throw UnknownHostException("Blocked by Custom DNS (${cfg.preset.displayName})")
            }
            if (entry.addresses.isNotEmpty()) {
                recordWorking(cfg.preset)
                return entry.addresses
            }
        }
        // Fail open to system DNS on transient DoH connectivity issues so app search remains usable.
        recordFallback(cfg.preset, "DoH resolver unavailable — fell back to System DNS")
        return systemDnsOverride.lookup(hostname)
    }

    /**
     * Checks whether a host requested by the in-app WebView is blocked by the active Custom DoH server.
     * Safe to invoke from WebViewClient.shouldInterceptRequest (background IO threads).
     */
    fun isHostBlockedByCustomDns(rawHost: String, isMainFrame: Boolean = false): Boolean {
        val cfg = activeConfig
        if (!cfg.isEnabled) return false
        val cleanHost = rawHost.trim().lowercase(Locale.US).trimEnd('.')
        if (cleanHost.isBlank() || cleanHost == activeDohHost || isIpLiteral(cleanHost)) return false
        if (cleanHost in CLOUDFLARE_ESSENTIAL_HOSTS) return false
        val entry = resolveWithCache(cleanHost, cfg.activeDohUrl)
        if (entry == null) {
            recordFallback(cfg.preset, "DoH filter unavailable — allowing WebView fallback")
            return false
        }
        if (isMainFrame && !entry.isBlocked) return false
        if (entry.isBlocked) {
            recordBlockedHost(cfg.preset)
        }
        return entry.isBlocked
    }

    private fun recordWorking(preset: CustomDnsPreset) {
        val now = System.currentTimeMillis()
        _diagnostics.update { cur ->
            cur.copy(
                status = DnsRuntimeStatus.WORKING,
                activePreset = preset,
                lastFallbackReason = null,
                updatedAtEpochMs = now
            )
        }
    }

    private fun recordBlockedHost(preset: CustomDnsPreset) {
        val now = System.currentTimeMillis()
        _diagnostics.update { cur ->
            cur.copy(
                status = DnsRuntimeStatus.BLOCKED,
                activePreset = preset,
                blockedCount = cur.blockedCount + 1,
                updatedAtEpochMs = now
            )
        }
    }

    private fun recordFallback(preset: CustomDnsPreset, reason: String) {
        val now = System.currentTimeMillis()
        val isInvalid = preset == CustomDnsPreset.CUSTOM && activeConfig.activeDohUrl.isBlank()
        _diagnostics.update { cur ->
            cur.copy(
                status = if (isInvalid) DnsRuntimeStatus.INVALID_CONFIG else DnsRuntimeStatus.FALLBACK,
                activePreset = preset,
                fallbackCount = cur.fallbackCount + 1,
                lastFallbackReason = reason,
                updatedAtEpochMs = now
            )
        }
    }

    /**
     * Tests the currently active Custom DNS endpoint by resolving a benign domain (example.com)
     * and checking whether a known ad domain (doubleclick.net) is blocked.
     */
    fun testEndpoint(config: CustomDnsConfig): CustomDnsTestResult {
        if (config.preset == CustomDnsPreset.SYSTEM) {
            return CustomDnsTestResult(
                endpoint = "System Default",
                reachable = true,
                latencyMs = 0L,
                adBlockingVerified = false,
                status = DnsRuntimeStatus.SYSTEM_DEFAULT,
                message = "Using System Default DNS (no DoH or DNS ad filtering)"
            )
        }
        val endpoint = if (config.preset == CustomDnsPreset.CUSTOM) {
            validateAndNormalizeDohEndpoint(config.customEndpoint)
        } else {
            config.preset.dohUrl
        }
        if (endpoint.isNullOrBlank()) {
            _diagnostics.update {
                it.copy(
                    status = DnsRuntimeStatus.INVALID_CONFIG,
                    activePreset = config.preset,
                    lastFallbackReason = "Invalid HTTPS DoH URL or hostname",
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            }
            return CustomDnsTestResult(
                endpoint = config.customEndpoint,
                reachable = false,
                latencyMs = 0L,
                adBlockingVerified = false,
                status = DnsRuntimeStatus.INVALID_CONFIG,
                message = "Invalid Config: enter a valid public https:// DoH URL or hostname"
            )
        }

        val start = System.currentTimeMillis()
        val benign = queryDohWire("example.com", endpoint)
        val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        if (benign == null) {
            _diagnostics.update {
                it.copy(
                    status = DnsRuntimeStatus.FALLBACK,
                    activePreset = config.preset,
                    lastFallbackReason = "DoH probe failed — search falls back to System DNS",
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            }
            return CustomDnsTestResult(
                endpoint = endpoint,
                reachable = false,
                latencyMs = latency,
                adBlockingVerified = false,
                status = DnsRuntimeStatus.FALLBACK,
                message = "DoH unreachable • app search falls back to System DNS"
            )
        }
        if (benign.isBlocked || benign.addresses.isEmpty()) {
            _diagnostics.update {
                it.copy(
                    status = DnsRuntimeStatus.BLOCKED,
                    activePreset = config.preset,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            }
            return CustomDnsTestResult(
                endpoint = endpoint,
                reachable = false,
                latencyMs = latency,
                adBlockingVerified = false,
                status = DnsRuntimeStatus.BLOCKED,
                message = "Resolver blocked test domain (${latency}ms)"
            )
        }

        val adCheck = queryDohWire("doubleclick.net", endpoint)
        val blocksAds = adCheck?.isBlocked == true
        val summary = if (blocksAds) {
            "Working (${latency}ms) • Test ad host blocked"
        } else {
            "Working (${latency}ms) • Encrypted DoH active (no ad filter)"
        }
        _diagnostics.update {
            it.copy(
                status = DnsRuntimeStatus.WORKING,
                activePreset = config.preset,
                lastFallbackReason = null,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }
        return CustomDnsTestResult(
            endpoint = endpoint,
            reachable = true,
            latencyMs = latency,
            adBlockingVerified = blocksAds,
            status = DnsRuntimeStatus.WORKING,
            message = summary
        )
    }

    private fun resolveWithCache(cleanHost: String, dohUrl: String): CachedEntry? {
        val now = System.currentTimeMillis()
        cache[cleanHost]?.let { cached ->
            if (cached.expiresAtMs > now) return cached
        }

        val lock = inFlightLocks.computeIfAbsent(cleanHost) { Any() }
        return try {
            synchronized(lock) {
                val refreshedNow = System.currentTimeMillis()
                cache[cleanHost]?.let { cached ->
                    if (cached.expiresAtMs > refreshedNow) return cached
                }

                val parsed = queryDohWire(cleanHost, dohUrl) ?: return null
                val ttl = if (parsed.isBlocked) CACHE_TTL_BLOCKED_MS else CACHE_TTL_VALID_MS
                val entry = CachedEntry(
                    addresses = parsed.addresses,
                    isBlocked = parsed.isBlocked,
                    expiresAtMs = System.currentTimeMillis() + ttl
                )
                if (cache.size >= MAX_CACHE_ENTRIES) {
                    cache.clear()
                }
                cache[cleanHost] = entry
                entry
            }
        } finally {
            inFlightLocks.remove(cleanHost)
        }
    }

    private fun queryDohWire(hostname: String, dohUrl: String): DnsPacketParseResult? {
        wireQueryOverride?.let { override ->
            return override(hostname, dohUrl)
        }
        val queryBytes = buildDnsQueryPacket(hostname, qtype = 1) ?: return null
        val request = Request.Builder()
            .url(dohUrl)
            .post(queryBytes.toRequestBody(DOH_MEDIA_TYPE))
            .header("Accept", "application/dns-message")
            .header("Content-Type", "application/dns-message")
            .build()

        return try {
            dohClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyBytes = response.body?.bytes() ?: return null
                parseDnsResponsePacket(bodyBytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Builds a standard RFC 1035 / RFC 8484 DNS query packet for [hostname] and [qtype] (1=A, 28=AAAA).
     */
    fun buildDnsQueryPacket(hostname: String, qtype: Int = 1): ByteArray? {
        val clean = hostname.trim().lowercase(Locale.US).trimEnd('.')
        if (clean.isBlank() || clean.length > 253) return null
        val labels = clean.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return null

        val out = ByteArrayOutputStream(64)
        // Header: ID=0x0000, Flags=0x0100 (standard query + recursion desired), QDCOUNT=1, AN/NS/AR=0
        out.write(0x00); out.write(0x00)
        out.write(0x01); out.write(0x00)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)

        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.size > 63) return null
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00) // Root label terminator
        out.write((qtype shr 8) and 0xFF)
        out.write(qtype and 0xFF)
        out.write(0x00); out.write(0x01) // QCLASS = IN
        return out.toByteArray()
    }

    /**
     * Parses an RFC 1035 / RFC 8484 DNS wire response packet, extracting RCODE, A/AAAA addresses,
     * and detecting ad-blocking sinkhole responses (0.0.0.0, ::, 127.0.0.1, ::1, NXDOMAIN).
     */
    fun parseDnsResponsePacket(data: ByteArray): DnsPacketParseResult? {
        if (data.size < 12) return null
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val rcode = flags and 0x0F
        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)

        if (rcode == 3 || rcode == 5) {
            return DnsPacketParseResult(rcode = rcode, addresses = emptyList(), isSinkholed = false)
        }

        var offset = 12
        repeat(qdCount) {
            offset = skipDnsName(data, offset) ?: return null
            if (offset + 4 > data.size) return null
            offset += 4 // QTYPE + QCLASS
        }

        val validAddresses = mutableListOf<InetAddress>()
        var sawSinkhole = false

        repeat(anCount) {
            offset = skipDnsName(data, offset) ?: return null
            if (offset + 10 > data.size) return null
            val type = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val rdLength = ((data[offset + 8].toInt() and 0xFF) shl 8) or (data[offset + 9].toInt() and 0xFF)
            offset += 10
            if (offset + rdLength > data.size) return null

            if (type == 1 && rdLength == 4) {
                val rawIp = data.copyOfRange(offset, offset + 4)
                val isZero = rawIp[0] == 0.toByte() && rawIp[1] == 0.toByte() && rawIp[2] == 0.toByte() && rawIp[3] == 0.toByte()
                val isLoopback = rawIp[0] == 127.toByte() && rawIp[1] == 0.toByte() && rawIp[2] == 0.toByte() && rawIp[3] == 1.toByte()
                if (isZero || isLoopback) {
                    sawSinkhole = true
                } else {
                    runCatching { InetAddress.getByAddress(rawIp) }.getOrNull()?.let(validAddresses::add)
                }
            } else if (type == 28 && rdLength == 16) {
                val rawIp = data.copyOfRange(offset, offset + 16)
                val isZero = rawIp.all { it == 0.toByte() }
                val isLoopback = rawIp.take(15).all { it == 0.toByte() } && rawIp[15] == 1.toByte()
                if (isZero || isLoopback) {
                    sawSinkhole = true
                } else {
                    runCatching { InetAddress.getByAddress(rawIp) }.getOrNull()?.let(validAddresses::add)
                }
            }
            offset += rdLength
        }

        return DnsPacketParseResult(
            rcode = rcode,
            addresses = validAddresses,
            isSinkholed = sawSinkhole && validAddresses.isEmpty()
        )
    }

    private fun skipDnsName(data: ByteArray, startOffset: Int): Int? {
        var offset = startOffset
        var steps = 0
        while (offset < data.size && steps < 128) {
            steps++
            val len = data[offset].toInt() and 0xFF
            if (len == 0) return offset + 1
            if ((len and 0xC0) == 0xC0) {
                return if (offset + 2 <= data.size) offset + 2 else null
            }
            offset += 1 + len
        }
        return null
    }

    private fun extractHostLower(url: String): String {
        if (url.isBlank()) return ""
        return runCatching { Uri.parse(url).host?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        val parts = host.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
    }
}
