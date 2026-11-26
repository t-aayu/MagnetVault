package com.example.ui.browser

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.domain.magnet.MagnetIntake
import com.example.domain.magnet.MagnetIntakeResolution
import com.example.model.CanonicalReleaseGroup
import com.example.model.CustomSite

sealed class BrowserErrorState {
    data class NetworkError(
        val failedUrl: String,
        val host: String,
        val description: String,
        val errorCode: Int?
    ) : BrowserErrorState()

    /**
     * Terminal TLS/certificate failure — never offers a bypass/proceed option.
     */
    data class TlsError(
        val host: String,
        val reason: String
    ) : BrowserErrorState()

    data class PendingHttpApproval(
        val httpUrl: String,
        val httpsCandidateUrl: String,
        val host: String
    ) : BrowserErrorState()
}

val PINNED_PRESET_SITES: List<CustomSite> = listOf(
    CustomSite("preset_1337x", "1337x.to", "https://1337x.to", isCustom = false),
    CustomSite("preset_x1337x", "x1337x.cc", "https://x1337x.cc", isCustom = false),
    CustomSite("preset_eztv", "EZTV", "https://eztvx.to", isCustom = false),
    CustomSite("preset_yts", "YTS", "https://yts.mx", isCustom = false),
    CustomSite("preset_nyaa", "Nyaa", "https://nyaa.si", isCustom = false),
    CustomSite("preset_tpb", "TPB", "https://thepiratebay.org", isCustom = false),
    CustomSite("preset_lime", "LimeTorrents", "https://www.limetorrents.lol", isCustom = false)
)

/**
 * Retained browser session state owned by MainAppShell so WebView page, history,
 * and controls survive normal app-tab switches and cleanly release when discarded.
 */
@Stable
class SitesBrowserSessionState {
    var isBrowserOpen by mutableStateOf(false)
        private set

    var currentUrl by mutableStateOf("")
        private set

    var currentHostname by mutableStateOf("")
        private set

    var isLegacyHttpSession by mutableStateOf(false)
        private set

    var addressInput by mutableStateOf("")
    var loadProgress by mutableIntStateOf(0)
    var isLoading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var popupShieldEnabled by mutableStateOf(true)
    var blockedAdsCount by mutableIntStateOf(0)

    var errorState by mutableStateOf<BrowserErrorState?>(null)
    var capturedMagnetGroup by mutableStateOf<CanonicalReleaseGroup?>(null)
    var capturedDownload by mutableStateOf<InterceptedDownload?>(null)

    var sessionApprovedHttpHosts by mutableStateOf<Set<String>>(emptySet())
        private set

    var lastHandledRequestNonce by mutableIntStateOf(0)

    internal var webView: WebView? = null
    internal var pendingLoadUrl: String? = null

    val hasRetainedSession: Boolean
        get() = currentUrl.isNotBlank() || webView != null

    fun openSitesDirectoryView() {
        isBrowserOpen = false
    }

    fun resumeActiveSession() {
        if (hasRetainedSession) {
            isBrowserOpen = true
        }
    }

    fun approveHttpHostAndNavigate(host: String, httpUrl: String) {
        sessionApprovedHttpHosts = sessionApprovedHttpHosts + host.lowercase()
        errorState = null
        loadUrlDirect(httpUrl, isLegacyHttp = true)
    }

    fun navigate(
        rawUrl: String,
        configuredHttpHosts: Set<String>,
        onBlocked: (String) -> Unit
    ) {
        when (
            val decision = BrowserUrlPolicy.evaluateNavigation(
                rawUrl = rawUrl,
                configuredHttpHosts = configuredHttpHosts,
                sessionApprovedHttpHosts = sessionApprovedHttpHosts
            )
        ) {
            is UrlNavigationDecision.Allowed -> {
                errorState = null
                loadUrlDirect(decision.normalizedUrl, isLegacyHttp = decision.isLegacyHttpException)
            }
            is UrlNavigationDecision.RequiresHttpExceptionPrompt -> {
                isBrowserOpen = true
                currentHostname = decision.host
                addressInput = decision.httpUrl
                errorState = BrowserErrorState.PendingHttpApproval(
                    httpUrl = decision.httpUrl,
                    httpsCandidateUrl = decision.httpsCandidateUrl,
                    host = decision.host
                )
            }
            is UrlNavigationDecision.Blocked -> {
                onBlocked(decision.reason)
            }
        }
    }

    fun loadUrlDirect(normalizedUrl: String, isLegacyHttp: Boolean = normalizedUrl.startsWith("http://", ignoreCase = true)) {
        val host = BrowserUrlPolicy.formatHostLabel(normalizedUrl)
        currentUrl = normalizedUrl
        currentHostname = host
        isLegacyHttpSession = isLegacyHttp
        addressInput = normalizedUrl
        errorState = null
        isBrowserOpen = true
        val existing = webView
        if (existing != null) {
            pendingLoadUrl = null
            existing.loadUrl(normalizedUrl)
        } else {
            pendingLoadUrl = normalizedUrl
        }
    }

    fun onPageNavigated(url: String, isLegacyHttp: Boolean) {
        currentUrl = url
        currentHostname = BrowserUrlPolicy.formatHostLabel(url)
        isLegacyHttpSession = isLegacyHttp
        addressInput = url
    }

    fun captureMagnet(rawMagnetUri: String, onInvalid: (String) -> Unit) {
        when (
            val resolution = MagnetIntake.resolveSingleMagnetUri(
                rawInput = rawMagnetUri,
                originLabel = currentHostname.ifBlank { "Sites Browser" },
                sourceUrl = currentUrl
            )
        ) {
            is MagnetIntakeResolution.Single -> {
                capturedMagnetGroup = resolution.group
            }
            is MagnetIntakeResolution.Unsupported -> {
                onInvalid(resolution.reason)
            }
            MagnetIntakeResolution.Empty,
            is MagnetIntakeResolution.Multiple -> Unit
        }
    }

    fun reload() {
        errorState = null
        val target = webView
        if (target != null) {
            if (target.url.isNullOrBlank() && currentUrl.isNotBlank()) {
                target.loadUrl(currentUrl)
            } else {
                target.reload()
            }
        } else if (currentUrl.isNotBlank()) {
            pendingLoadUrl = currentUrl
        }
    }

    fun goBack() {
        errorState = null
        webView?.goBack()
    }

    fun goForward() {
        errorState = null
        webView?.goForward()
    }

    fun stopLoading() {
        webView?.stopLoading()
        isLoading = false
    }

    fun detachWebViewFromHost() {
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
        }
    }

    fun discardSession() {
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.destroy()
        }
        webView = null
        pendingLoadUrl = null
        isBrowserOpen = false
        currentUrl = ""
        currentHostname = ""
        isLegacyHttpSession = false
        addressInput = ""
        loadProgress = 0
        isLoading = false
        canGoBack = false
        canGoForward = false
        blockedAdsCount = 0
        errorState = null
        capturedMagnetGroup = null
        capturedDownload = null
        sessionApprovedHttpHosts = emptySet()
    }
}

@Composable
fun rememberSitesBrowserSessionState(): SitesBrowserSessionState =
    remember { SitesBrowserSessionState() }
