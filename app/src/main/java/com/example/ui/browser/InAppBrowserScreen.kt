package com.example.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.LocalPrefsStore
import com.example.data.network.CustomAppDns
import com.example.model.CustomDnsConfig
import com.example.model.CustomSite
import com.example.model.QualityAndHealthParser
import com.example.model.WebsiteOpenMode
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryMonoStyle
import com.example.util.IntentActions
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.Locale

private val PRESET_SITES = listOf(
    CustomSite("preset_1337x", "1337x.to", "https://1337x.to", isCustom = false),
    CustomSite("preset_x1337x", "x1337x.cc", "https://x1337x.cc", isCustom = false),
    CustomSite("preset_eztv", "EZTV", "https://eztvx.to", isCustom = false),
    CustomSite("preset_yts", "YTS", "https://yts.mx", isCustom = false),
    CustomSite("preset_nyaa", "Nyaa", "https://nyaa.si", isCustom = false),
    CustomSite("preset_tpb", "TPB", "https://thepiratebay.org", isCustom = false),
    CustomSite("preset_lime", "LimeTorrents", "https://www.limetorrents.lol", isCustom = false)
)

private val BLOCKED_AD_HOST_KEYWORDS = listOf(
    "popads.", "popcash.", "propellerads.", "exoclick.", "juicyads.",
    "adsterra.", "clickadu.", "hilltopads.", "ad-maven.", "admaven.",
    "doubleclick.net", "googlesyndication.", "googleadservices.", "trafficjunky.",
    "bongacams.", "chaturbate.", "livejasmin.", "stripchat.", "pushground.",
    "onclickalgo.", "monetag.", "a-ads.", "tsyndicate.", "realsrv.",
    "bet365.", "1xbet.", "taboola.", "outbrain.", "mgid.", "adcash.",
    "adnxs.", "criteo.", "pubmatic.", "histats.", "ero-advertising.", "plugrush.",
    "onclkds.", "acscdn."
)

private val DIRECT_DOWNLOAD_EXTENSIONS = listOf(
    ".mkv", ".mp4", ".avi", ".mov", ".m4v", ".wmv", ".webm",
    ".zip", ".rar", ".7z", ".tar", ".gz", ".iso",
    ".mp3", ".flac", ".m4a", ".aac", ".ogg", ".wav",
    ".epub", ".pdf", ".srt", ".ass", ".cbz", ".cbr"
)

private data class CapturedDownload(
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val contentLengthBytes: Long
)

/**
 * Detects direct file download links (e.g., Seedr CDN `/ff_get/`, TorBox `/requestdl`,
 * S3/R2 signed `response-content-disposition=attachment`, or direct media/archive file paths)
 * so they hand off to an external download manager chooser instead of rendering inline.
 */
fun isDirectDownloadUrl(rawUrl: String): Boolean {
    val parsed = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
    val scheme = parsed.scheme?.lowercase(Locale.US)
    if (scheme != "http" && scheme != "https") return false
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

private fun formatDownloadBytes(bytes: Long): String {
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

/**
 * Validates and normalizes user-entered URLs strictly to http:// or https://.
 * Rejects dangerous schemes (javascript:, file:, content:, data:, intent:, mailto:, etc.),
 * control/whitespace characters, and embedded userinfo credentials.
 */
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
    val host = parsed.host?.trim() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (!parsed.userInfo.isNullOrEmpty()) return null
    if (!host.contains('.') || host.startsWith('.') || host.endsWith('.') || host.length > 253) {
        return null
    }
    return withScheme
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InAppBrowserScreen(
    prefsStore: LocalPrefsStore,
    torrentBoxOpenMode: WebsiteOpenMode,
    customDnsConfig: CustomDnsConfig,
    requestedUrl: String?,
    requestNonce: Int,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val telemetry = LocalTelemetryColors.current

    val customSites by prefsStore.customSitesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val allSites = remember(customSites) { customSites + PRESET_SITES }
    val latestOnShowMessage by rememberUpdatedState(onShowMessage)

    var currentUrl by remember {
        mutableStateOf(
            requestedUrl?.let { validateAndNormalizeWebUrl(it) } ?: PRESET_SITES.first().url
        )
    }
    var addressInput by remember { mutableStateOf(currentUrl) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var popupShieldEnabled by remember { mutableStateOf(true) }
    var blockedAdsCount by remember { mutableIntStateOf(0) }
    var capturedMagnet by remember { mutableStateOf<String?>(null) }
    var capturedDownload by remember { mutableStateOf<CapturedDownload?>(null) }

    var showAddSiteDialog by remember { mutableStateOf(false) }
    var newSiteName by remember { mutableStateOf("") }
    var newSiteUrl by remember { mutableStateOf("https://") }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun handleDownloadIntercepted(
        rawUrl: String,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val safeUrl = validateAndNormalizeWebUrl(rawUrl)
        if (safeUrl == null) {
            latestOnShowMessage("Unsupported download link format")
            return
        }
        val guessedName = runCatching {
            URLUtil.guessFileName(safeUrl, contentDisposition, mimeType)
        }.getOrDefault("download").replace(Regex("[\\r\\n\\t]+"), " ").trim().ifBlank { "download" }

        val download = CapturedDownload(
            url = safeUrl,
            fileName = guessedName,
            mimeType = mimeType,
            contentLengthBytes = contentLength
        )
        capturedDownload = download
        val launched = IntentActions.openDownloadManagerChooser(
            context = context,
            url = safeUrl,
            fileName = guessedName,
            mimeType = mimeType
        )
        if (!launched) {
            latestOnShowMessage("No external download manager or browser found — copy the link below")
        }
    }

    fun navigateToUrl(raw: String) {
        val valid = validateAndNormalizeWebUrl(raw)
        if (valid == null) {
            onShowMessage("Enter a valid http:// or https:// website URL")
            return
        }
        currentUrl = valid
        addressInput = valid
        webViewRef?.loadUrl(valid)
    }

    // Load any URL pushed from Search/Vault error fallback or torrent detail sheet
    LaunchedEffect(requestedUrl, requestNonce) {
        if (!requestedUrl.isNullOrBlank()) {
            val valid = validateAndNormalizeWebUrl(requestedUrl)
            if (valid != null) {
                currentUrl = valid
                addressInput = valid
                webViewRef?.loadUrl(valid)
            }
        }
    }

    BackHandler(enabled = canGoBack) {
        webViewRef?.goBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Quick Site Chips Bar (Custom Sites + Presets + Add Site)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        newSiteName = ""
                        newSiteUrl = "https://"
                        showAddSiteDialog = true
                    }
                    .testTag("add_custom_site_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Add custom website",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Add Site",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            allSites.forEach { site ->
                val isSelected = currentUrl.startsWith(site.url, ignoreCase = true)
                Surface(
                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else telemetry.cardBorder
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            focusManager.clearFocus()
                            navigateToUrl(site.url)
                        }
                        .testTag("site_chip_${site.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(
                            start = 10.dp,
                            end = if (site.isCustom) 4.dp else 10.dp,
                            top = 7.dp,
                            bottom = 7.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Language,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = site.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                        if (site.isCustom) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Remove ${site.name}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        scope.launch { prefsStore.removeCustomSite(site.id) }
                                    }
                                    .padding(2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Address Bar Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(
                value = addressInput,
                onValueChange = { addressInput = it },
                singleLine = true,
                placeholder = { Text("Enter URL (e.g. 1337x.to)", style = MaterialTheme.typography.bodySmall) },
                textStyle = TelemetryMonoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        focusManager.clearFocus()
                        navigateToUrl(addressInput)
                    }
                ),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = telemetry.cardBorder
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("browser_url_input")
            )

            IconButton(
                onClick = {
                    if (isLoading) webViewRef?.stopLoading() else webViewRef?.reload()
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                    contentDescription = if (isLoading) "Stop loading" else "Reload page",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Browser Navigation & Security Controls Bar (48dp touch targets)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { webViewRef?.goBack() },
                    enabled = canGoBack,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = if (canGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { webViewRef?.goForward() },
                    enabled = canGoForward,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        tint = if (canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (customDnsConfig.isEnabled || blockedAdsCount > 0) {
                    Surface(
                        color = if (customDnsConfig.isEnabled) telemetry.seederGreenContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("browser_dns_status_pill")
                    ) {
                        Text(
                            text = buildString {
                                append(if (customDnsConfig.isEnabled) customDnsConfig.preset.displayName else "Shield")
                                if (blockedAdsCount > 0) {
                                    append(" • ")
                                    append(blockedAdsCount)
                                    append(" blocked")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (customDnsConfig.isEnabled) telemetry.seederGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        popupShieldEnabled = !popupShieldEnabled
                        latestOnShowMessage(
                            if (popupShieldEnabled) "Popup & ad-redirect shield enabled"
                            else "Popup shield paused for this session"
                        )
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("browser_shield_toggle")
                ) {
                    Icon(
                        imageVector = if (popupShieldEnabled) Icons.Filled.Shield else Icons.Outlined.Shield,
                        contentDescription = "Toggle popup shield",
                        tint = if (popupShieldEnabled) telemetry.seederGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = {
                        val safe = validateAndNormalizeWebUrl(currentUrl)
                        if (safe != null) {
                            IntentActions.openUrlInBrowser(context, safe)
                        }
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open current page in default browser",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        if (isLoading && loadProgress in 1..99) {
            LinearProgressIndicator(
                progress = { loadProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        } else if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Embedded Hardened Android WebView
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("in_app_webview"),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            // Hardened WebView defaults: disallow local file/content URI access and ad popups
                            allowFileAccess = false
                            allowContentAccess = false
                            setSupportMultipleWindows(false)
                            javaScriptCanOpenWindowsAutomatically = false
                            builtInZoomControls = true
                            displayZoomControls = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                safeBrowsingEnabled = true
                            }
                        }
                        // Persist login sessions for Seedr.cc & TorBox inside the in-app WebView
                        val webViewInstance = this
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(webViewInstance, true)
                        }
                        // Intercept file downloads (Seedr/TorBox/etc.) and launch bottom intent chooser for IDM/1DM/ADM/Browser
                        setDownloadListener { url, _, contentDisposition, mimetype, contentLength ->
                            handleDownloadIntercepted(
                                rawUrl = url,
                                contentDisposition = contentDisposition,
                                mimeType = mimetype,
                                contentLength = contentLength
                            )
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                                isLoading = newProgress < 100
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val targetUri = request?.url ?: return null
                                val scheme = targetUri.scheme?.lowercase(Locale.US).orEmpty()
                                if (scheme != "http" && scheme != "https") return null
                                val host = targetUri.host?.lowercase(Locale.US).orEmpty()
                                if (host.isBlank()) return null

                                val blockedByShield = popupShieldEnabled &&
                                    BLOCKED_AD_HOST_KEYWORDS.any { host.contains(it) }
                                val blockedByCustomDns = !blockedByShield &&
                                    CustomAppDns.isHostBlockedByCustomDns(host, request.isForMainFrame)

                                if (blockedByShield || blockedByCustomDns) {
                                    view?.post { blockedAdsCount += 1 }
                                    return WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                                return null
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val targetUri = request?.url ?: return false
                                val urlStr = targetUri.toString()
                                val scheme = targetUri.scheme?.lowercase(Locale.US).orEmpty()

                                // 1. Intercept Magnet links natively so user can copy or send to Seedr/TorBox in 1 tap
                                if (scheme == "magnet") {
                                    capturedMagnet = urlStr
                                    return true
                                }

                                // 2. Block unsafe or app-hijacking URI schemes
                                if (scheme != "http" && scheme != "https") {
                                    return true
                                }

                                // 3. Intercept direct media/archive download URLs (e.g., Seedr/TorBox CDN links)
                                // on top-level navigations so the user gets the download manager chooser immediately
                                if (request.isForMainFrame && isDirectDownloadUrl(urlStr)) {
                                    handleDownloadIntercepted(
                                        rawUrl = urlStr,
                                        contentDisposition = null,
                                        mimeType = null,
                                        contentLength = -1L
                                    )
                                    return true
                                }

                                // 4. Optional Popup/Ad-Redirect Shield (allows Cloudflare challenges to pass cleanly)
                                val host = targetUri.host?.lowercase(Locale.US).orEmpty()
                                if (popupShieldEnabled && BLOCKED_AD_HOST_KEYWORDS.any { host.contains(it) }) {
                                    blockedAdsCount += 1
                                    latestOnShowMessage("Blocked ad redirect ($host)")
                                    return true
                                }

                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                blockedAdsCount = 0
                                if (!url.isNullOrBlank()) {
                                    currentUrl = url
                                    addressInput = url
                                }
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                if (!url.isNullOrBlank()) {
                                    currentUrl = url
                                    addressInput = url
                                }
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }
                        }
                        webViewRef = this
                        loadUrl(currentUrl)
                    }
                },
                update = { view ->
                    webViewRef = view
                }
            )
        }
    }

    // Add Custom Website Dialog
    if (showAddSiteDialog) {
        AlertDialog(
            onDismissRequest = { showAddSiteDialog = false },
            title = { Text("Add Custom Website") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Save any torrent site or custom mirror to open directly inside MagnetVault.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newSiteName,
                        onValueChange = { newSiteName = it },
                        label = { Text("Site Name (optional)") },
                        placeholder = { Text("e.g. 1337x Mirror") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newSiteUrl,
                        onValueChange = { newSiteUrl = it },
                        label = { Text("Website URL") },
                        placeholder = { Text("https://x1337x.cc") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val valid = validateAndNormalizeWebUrl(newSiteUrl)
                        if (valid == null) {
                            onShowMessage("Enter a valid https:// or http:// website URL")
                        } else {
                            scope.launch { prefsStore.addCustomSite(newSiteName, valid) }
                            navigateToUrl(valid)
                            showAddSiteDialog = false
                            onShowMessage("Saved custom website")
                        }
                    }
                ) {
                    Text("Save & Open")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddSiteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Magnet Link Captured Bottom Sheet (when user taps any magnet:? link inside WebView)
    val magnet = capturedMagnet
    if (magnet != null) {
        val displayName = remember(magnet) {
            val dnParam = Regex("[?&]dn=([^&]+)").find(magnet)?.groupValues?.getOrNull(1)
            runCatching {
                if (!dnParam.isNullOrBlank()) URLDecoder.decode(dnParam, "UTF-8") else ""
            }.getOrDefault("").ifBlank { "Captured Magnet Link" }
        }
        val infoHash = remember(magnet) { QualityAndHealthParser.extractHashFromMagnet(magnet) }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { capturedMagnet = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = "MAGNET LINK CAPTURED",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (infoHash.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "INFO HASH • ${infoHash.uppercase(Locale.US)}",
                        style = TelemetryMonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        IntentActions.copyToClipboard(context, displayName, magnet)
                        onShowMessage("Magnet link copied to clipboard")
                        capturedMagnet = null
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_copy_magnet), fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            IntentActions.copyToClipboard(context, displayName, magnet)
                            capturedMagnet = null
                            if (torrentBoxOpenMode == WebsiteOpenMode.IN_APP) {
                                navigateToUrl(IntentActions.SEEDR_WEB_URL)
                                onShowMessage("Magnet copied — opening Seedr.cc in app")
                            } else {
                                IntentActions.openUrlInBrowser(context, IntentActions.SEEDR_WEB_URL)
                                onShowMessage("Magnet copied — opening Seedr.cc in browser")
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_open_seedr), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalButton(
                        onClick = {
                            IntentActions.copyToClipboard(context, displayName, magnet)
                            capturedMagnet = null
                            if (torrentBoxOpenMode == WebsiteOpenMode.IN_APP) {
                                navigateToUrl(IntentActions.TORBOX_WEB_URL)
                                onShowMessage("Magnet copied — opening TorBox in app")
                            } else {
                                IntentActions.openUrlInBrowser(context, IntentActions.TORBOX_WEB_URL)
                                onShowMessage("Magnet copied — opening TorBox in browser")
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_open_torbox), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val opened = IntentActions.openMagnetInLocalApp(context, magnet)
                            if (!opened) {
                                IntentActions.copyToClipboard(context, displayName, magnet)
                                onShowMessage("No local torrent app found—magnet copied")
                            }
                            capturedMagnet = null
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_open_local))
                    }
                    OutlinedButton(
                        onClick = {
                            IntentActions.shareText(context, displayName, "$displayName\n$magnet")
                            capturedMagnet = null
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_share))
                    }
                }
            }
        }
    }

    // Download Manager Handoff Bottom Sheet (pairs with Android's native bottom intent chooser)
    val download = capturedDownload
    if (download != null) {
        val downloadSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val sizeText = remember(download.contentLengthBytes) {
            formatDownloadBytes(download.contentLengthBytes)
        }
        val hostLabel = remember(download.url) {
            runCatching { Uri.parse(download.url).host.orEmpty() }.getOrDefault("")
        }

        ModalBottomSheet(
            onDismissRequest = { capturedDownload = null },
            sheetState = downloadSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = "DOWNLOAD LINK READY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = download.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val metaLine = listOfNotNull(
                    hostLabel.takeIf { it.isNotBlank() },
                    sizeText.takeIf { it.isNotBlank() },
                    download.mimeType?.substringBefore(';')?.takeIf { it.isNotBlank() }
                ).joinToString(" • ")
                if (metaLine.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = metaLine,
                        style = TelemetryMonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        val launched = IntentActions.openDownloadManagerChooser(
                            context = context,
                            url = download.url,
                            fileName = download.fileName,
                            mimeType = download.mimeType
                        )
                        if (!launched) {
                            onShowMessage("No external download manager or browser found")
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("choose_download_manager_button")
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_choose_download_manager), fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            IntentActions.copyToClipboard(context, download.fileName, download.url)
                            onShowMessage("Direct download link copied to clipboard")
                            capturedDownload = null
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("copy_download_url_button")
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_copy_download_url), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    OutlinedButton(
                        onClick = {
                            IntentActions.shareText(
                                context = context,
                                title = download.fileName,
                                content = download.url,
                                chooserLabel = "Send Download Link To…"
                            )
                            capturedDownload = null
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_share))
                    }
                }
            }
        }
    }
}
