package com.example.ui.browser

import android.net.Uri
import android.webkit.URLUtil
import java.net.URLDecoder
import java.util.Locale

sealed class UrlNavigationDecision {
    data class Allowed(
        val normalizedUrl: String,
        val host: String,
        val isLegacyHttpException: Boolean
    ) : UrlNavigationDecision()

    data class RequiresHttpExceptionPrompt(
        val httpUrl: String,
        val httpsCandidateUrl: String,
        val host: String
    ) : UrlNavigationDecision()

    data class Blocked(
        val reason: String,
        val scheme: String
    ) : UrlNavigationDecision()
}

data class InterceptedDownload(
    val url: String,
    val hostLabel: String,
    val fileName: String,
    val mimeType: String?,
    val contentLengthBytes: Long,
    val isLikelySessionBound: Boolean,
    val sessionNote: String
)

/**
 * Single parsed-URL policy for in-app WebView navigation, redirects, custom sites,
 * and download handoff parsing.
 * - Defaults to HTTPS.
 * - Blocks file:, content:, javascript:, data:, intent:, mailto:, and embedded userinfo credentials.
 * - Preserves configured HTTP mirrors through explicit scoped host exceptions.
 */
object BrowserUrlPolicy {

    private val DIRECT_DOWNLOAD_EXTENSIONS = listOf(
        ".mkv", ".mp4", ".avi", ".mov", ".m4v", ".wmv", ".webm",
        ".zip", ".rar", ".7z", ".tar", ".gz", ".iso",
        ".mp3", ".flac", ".m4a", ".aac", ".ogg", ".wav",
        ".epub", ".pdf", ".srt", ".ass", ".cbz", ".cbr"
    )

    private val SESSION_BOUND_HOSTS = listOf(
        "seedr.cc",
        "torbox.app"
    )

    fun validateStrictHttpsUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        val lower = trimmed.lowercase(Locale.US)
        val hasExplicitScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(trimmed)
        if (hasExplicitScheme && !lower.startsWith("https://")) {
            return null
        }
        val withScheme = if (lower.startsWith("https://")) trimmed else "https://$trimmed"
        val parsed = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("https", ignoreCase = true)) return null
        if (!parsed.userInfo.isNullOrEmpty()) return null
        val host = parsed.host?.trim()?.lowercase(Locale.US) ?: return null
        if (!isValidPublicHostname(host)) return null
        return withScheme
    }

    fun validateAndNormalizeWebUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        val lower = trimmed.lowercase(Locale.US)
        val hasExplicitScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(trimmed)
        val isHttpOrHttps = lower.startsWith("https://") || lower.startsWith("http://")
        if (hasExplicitScheme && !isHttpOrHttps) {
            return null
        }
        val withScheme = if (isHttpOrHttps) trimmed else "https://$trimmed"
        val parsed = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase(Locale.US) ?: return null
        val host = parsed.host?.trim()?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (!parsed.userInfo.isNullOrEmpty()) return null
        if (!isValidPublicHostname(host)) return null
        return withScheme
    }

    fun validateCustomSiteUrl(raw: String, allowLegacyHttpMirror: Boolean): String? =
        if (allowLegacyHttpMirror) validateAndNormalizeWebUrl(raw) else validateStrictHttpsUrl(raw)

    /**
     * Evaluates both user-initiated navigations and server/client redirects under one parsed-URL policy.
     */
    fun evaluateNavigation(
        rawUrl: String,
        configuredHttpHosts: Set<String> = emptySet(),
        sessionApprovedHttpHosts: Set<String> = emptySet()
    ): UrlNavigationDecision {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return UrlNavigationDecision.Blocked("Empty URL cannot be opened.", "")
        }
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) {
            return UrlNavigationDecision.Blocked("URL contains invalid control or whitespace characters.", "")
        }

        val lower = trimmed.lowercase(Locale.US)
        val schemePrefixMatch = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(trimmed)
        val explicitScheme = schemePrefixMatch?.groupValues?.getOrNull(1)?.lowercase(Locale.US)

        if (explicitScheme != null && explicitScheme != "http" && explicitScheme != "https") {
            return UrlNavigationDecision.Blocked(
                reason = "Blocked unsupported '$explicitScheme:' scheme. Only HTTPS (and approved HTTP mirror exceptions) are permitted.",
                scheme = explicitScheme
            )
        }

        val candidate = if (lower.startsWith("http://") || lower.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val parsed = runCatching { Uri.parse(candidate) }.getOrNull()
            ?: return UrlNavigationDecision.Blocked("Malformed web URL.", explicitScheme.orEmpty())
        val scheme = parsed.scheme?.lowercase(Locale.US).orEmpty()
        if (scheme != "http" && scheme != "https") {
            return UrlNavigationDecision.Blocked("Blocked unsupported '$scheme:' scheme.", scheme)
        }
        if (!parsed.userInfo.isNullOrEmpty()) {
            return UrlNavigationDecision.Blocked("Blocked URL containing embedded credentials.", scheme)
        }
        val host = parsed.host?.trim()?.lowercase(Locale.US).orEmpty()
        if (!isValidPublicHostname(host)) {
            return UrlNavigationDecision.Blocked("URL does not contain a valid public hostname.", scheme)
        }

        if (scheme == "https") {
            return UrlNavigationDecision.Allowed(
                normalizedUrl = candidate,
                host = host,
                isLegacyHttpException = false
            )
        }

        // Scheme is HTTP: only allow if this host is an explicit configured HTTP mirror or approved for this session
        val isApprovedException = host in configuredHttpHosts || host in sessionApprovedHttpHosts
        return if (isApprovedException) {
            UrlNavigationDecision.Allowed(
                normalizedUrl = candidate,
                host = host,
                isLegacyHttpException = true
            )
        } else {
            UrlNavigationDecision.RequiresHttpExceptionPrompt(
                httpUrl = candidate,
                httpsCandidateUrl = "https://" + candidate.removePrefix("http://").removePrefix("HTTP://"),
                host = host
            )
        }
    }

    fun extractHttpHosts(urls: Iterable<String>): Set<String> =
        urls.mapNotNull { raw ->
            val trimmed = raw.trim()
            if (!trimmed.startsWith("http://", ignoreCase = true)) return@mapNotNull null
            runCatching { Uri.parse(trimmed).host?.trim()?.lowercase(Locale.US) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        }.toSet()

    fun formatHostLabel(rawUrl: String): String =
        runCatching { Uri.parse(rawUrl).host?.trim()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")

    fun isDirectDownloadUrl(rawUrl: String): Boolean {
        val parsed = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return false
        if (!parsed.userInfo.isNullOrEmpty()) return false
        val path = parsed.path?.lowercase(Locale.US).orEmpty()
        val query = parsed.query?.lowercase(Locale.US).orEmpty()

        if (query.contains("response-content-disposition=attachment") ||
            query.contains("content-disposition=attachment")
        ) {
            return true
        }
        if (path.contains("/ff_get/") || path.contains("/requestdl")) {
            return true
        }
        return DIRECT_DOWNLOAD_EXTENSIONS.any { path.endsWith(it) }
    }

    private fun isValidPublicHostname(host: String): Boolean {
        if (host.isBlank() || host.length > 253) return false
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost")) return false
        return true
    }
}

object DownloadHandoffParser {
    fun parse(
        rawUrl: String,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
        hasPageCookies: Boolean,
        currentPageHost: String?
    ): InterceptedDownload? {
        val safeUrl = BrowserUrlPolicy.validateAndNormalizeWebUrl(rawUrl) ?: return null
        val parsed = runCatching { Uri.parse(safeUrl) }.getOrNull() ?: return null
        val hostLabel = parsed.host?.trim()?.lowercase(Locale.US).orEmpty()
        val fileName = resolveCleanFileName(safeUrl, parsed, contentDisposition, mimeType)

        val lowerPath = parsed.path?.lowercase(Locale.US).orEmpty()
        val lowerQuery = parsed.query?.lowercase(Locale.US).orEmpty()
        val isSignedQuery = lowerQuery.contains("x-amz-signature=") ||
            lowerQuery.contains("sig=") ||
            lowerQuery.contains("signature=") ||
            lowerQuery.contains("expires=") ||
            lowerQuery.contains("token=")

        val matchesSessionHost = SESSION_BOUND_HOSTS_MATCH(hostLabel)
        val sharesCookieHost = hasPageCookies && !currentPageHost.isNullOrBlank() &&
            (hostLabel == currentPageHost || hostLabel.endsWith(".$currentPageHost") ||
                currentPageHost.endsWith(".$hostLabel"))

        val isLikelySessionBound = (!isSignedQuery && (matchesSessionHost || sharesCookieHost)) ||
            lowerPath.contains("/ff_get/") ||
            lowerPath.contains("/requestdl")

        val sessionNote = if (isLikelySessionBound) {
            "Session cookies and login state remain inside MagnetVault's browser. External download managers do not receive WebView cookies, so use Copy Link or stay in browser if the download requires an active login."
        } else {
            "External apps do not receive in-app WebView cookies. Verify the filename and host before handing off."
        }

        return InterceptedDownload(
            url = safeUrl,
            hostLabel = hostLabel,
            fileName = fileName,
            mimeType = mimeType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() },
            contentLengthBytes = contentLength,
            isLikelySessionBound = isLikelySessionBound,
            sessionNote = sessionNote
        )
    }

    private fun SESSION_BOUND_HOSTS_MATCH(host: String): Boolean =
        host.contains("seedr.cc") || host.contains("torbox.app")

    private fun resolveCleanFileName(
        safeUrl: String,
        parsed: Uri,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        val dispositionName = extractFilenameFromContentDisposition(contentDisposition)
        val pathSegment = parsed.lastPathSegment
            ?.let { seg -> runCatching { URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg) }
            ?.takeIf { it.isNotBlank() && it.contains('.') }
        val guessed = runCatching {
            URLUtil.guessFileName(safeUrl, contentDisposition, mimeType)
        }.getOrNull()

        val rawCandidate = dispositionName
            ?: pathSegment
            ?: guessed
            ?: "download"
        return rawCandidate
            .replace(Regex("[\\r\\n\\t/\\\\]+"), " ")
            .trim()
            .ifBlank { "download" }
            .take(160)
    }

    private fun extractFilenameFromContentDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val utfMatch = Regex("""filename\*\s*=\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.getOrNull(1)
        if (!utfMatch.isNullOrBlank()) {
            return runCatching { URLDecoder.decode(utfMatch.trim('"', '\''), "UTF-8") }.getOrNull()
        }
        val plainMatch = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.getOrNull(1)
        return plainMatch?.trim('"', '\'', ' ')?.takeIf { it.isNotBlank() }
    }
}

fun validateAndNormalizeWebUrl(raw: String): String? =
    BrowserUrlPolicy.validateAndNormalizeWebUrl(raw)

fun isDirectDownloadUrl(rawUrl: String): Boolean =
    BrowserUrlPolicy.isDirectDownloadUrl(rawUrl)

fun formatDownloadBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}
