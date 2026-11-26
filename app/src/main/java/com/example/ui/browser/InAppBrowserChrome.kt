package com.example.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.network.CustomAppDns
import com.example.model.CustomDnsConfig
import com.example.ui.theme.GlassCard
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryMonoStyle
import com.example.ui.theme.ambientGlassBackdrop
import com.example.util.IntentActions
import java.io.ByteArrayInputStream
import java.util.Locale

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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppBrowserChrome(
    sessionState: SitesBrowserSessionState,
    customDnsConfig: CustomDnsConfig,
    configuredHttpHosts: Set<String>,
    onNavigateToUrl: (String) -> Unit,
    onReturnToSitesDirectory: () -> Unit,
    onDiscardSession: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val telemetry = LocalTelemetryColors.current

    val latestShowMessage by rememberUpdatedState(onShowMessage)
    val latestConfiguredHttpHosts by rememberUpdatedState(configuredHttpHosts)

    fun handleDownloadIntercepted(
        rawUrl: String,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val hasCookies = runCatching {
            !CookieManager.getInstance().getCookie(sessionState.currentUrl).isNullOrBlank()
        }.getOrDefault(false)
        val parsed = DownloadHandoffParser.parse(
            rawUrl = rawUrl,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            contentLength = contentLength,
            hasPageCookies = hasCookies,
            currentPageHost = sessionState.currentHostname
        )
        if (parsed == null) {
            latestShowMessage("Blocked unsupported download link scheme")
            return
        }
        sessionState.capturedDownload = parsed
    }

    // Preserve WebView across tab switches: only detach from old container on compose exit.
    DisposableEffect(Unit) {
        onDispose {
            sessionState.detachWebViewFromHost()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .ambientGlassBackdrop(telemetry)
    ) {
        // Hostname & Session Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onReturnToSitesDirectory,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("browser_sites_home_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Apps,
                    contentDescription = "Back to Sites list",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Surface(
                color = if (sessionState.isLegacyHttpSession) {
                    telemetry.leecherAmberContainer
                } else {
                    telemetry.glassSurfaceElevated
                },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    0.75.dp,
                    if (sessionState.isLegacyHttpSession) {
                        telemetry.leecherAmber.copy(alpha = 0.45f)
                    } else {
                        telemetry.cardBorder
                    }
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("browser_hostname_badge")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (sessionState.isLegacyHttpSession) Icons.Filled.WarningAmber else Icons.Filled.Lock,
                        contentDescription = if (sessionState.isLegacyHttpSession) "HTTP mirror exception" else "HTTPS",
                        tint = if (sessionState.isLegacyHttpSession) telemetry.leecherAmber else telemetry.seederGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = sessionState.currentHostname.ifBlank { "Sites Browser" },
                        style = TelemetryMonoStyle.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (sessionState.isLegacyHttpSession) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "HTTP",
                            style = MaterialTheme.typography.labelSmall,
                            color = telemetry.leecherAmber,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            IconButton(
                onClick = onDiscardSession,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("browser_discard_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Discard browser session",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
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
                value = sessionState.addressInput,
                onValueChange = { sessionState.addressInput = it },
                singleLine = true,
                placeholder = { Text("Enter URL (e.g. 1337x.to)", style = MaterialTheme.typography.bodySmall) },
                textStyle = TelemetryMonoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        focusManager.clearFocus()
                        onNavigateToUrl(sessionState.addressInput)
                    }
                ),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = telemetry.glassSurfaceElevated,
                    unfocusedContainerColor = telemetry.glassSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    unfocusedBorderColor = telemetry.cardBorder.copy(alpha = 0.75f),
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("browser_url_input")
            )

            IconButton(
                onClick = {
                    if (sessionState.isLoading) sessionState.stopLoading() else sessionState.reload()
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("browser_reload_button")
            ) {
                Icon(
                    imageVector = if (sessionState.isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                    contentDescription = if (sessionState.isLoading) "Stop loading" else "Reload page",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp)
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
                    onClick = { sessionState.goBack() },
                    enabled = sessionState.canGoBack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("browser_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = if (sessionState.canGoBack) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { sessionState.goForward() },
                    enabled = sessionState.canGoForward,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("browser_forward_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        tint = if (sessionState.canGoForward) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (customDnsConfig.isEnabled || sessionState.blockedAdsCount > 0) {
                    Surface(
                        color = if (customDnsConfig.isEnabled) telemetry.seederGreenContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("browser_dns_status_pill")
                    ) {
                        Text(
                            text = buildString {
                                append(if (customDnsConfig.isEnabled) customDnsConfig.preset.displayName else "Shield")
                                if (sessionState.blockedAdsCount > 0) {
                                    append(" • ")
                                    append(sessionState.blockedAdsCount)
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
                        sessionState.popupShieldEnabled = !sessionState.popupShieldEnabled
                        latestShowMessage(
                            if (sessionState.popupShieldEnabled) "Popup & ad-redirect shield enabled"
                            else "Popup shield paused for this session"
                        )
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("browser_shield_toggle")
                ) {
                    Icon(
                        imageVector = if (sessionState.popupShieldEnabled) Icons.Filled.Shield else Icons.Outlined.Shield,
                        contentDescription = "Toggle popup shield",
                        tint = if (sessionState.popupShieldEnabled) telemetry.seederGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = {
                        val safe = BrowserUrlPolicy.validateAndNormalizeWebUrl(sessionState.currentUrl)
                        if (safe != null) {
                            IntentActions.openUrlInBrowser(context, safe)
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("browser_open_external_button")
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

        if (sessionState.isLoading && sessionState.loadProgress in 1..99) {
            LinearProgressIndicator(
                progress = { sessionState.loadProgress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .testTag("browser_progress_bar"),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
        } else if (sessionState.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .testTag("browser_progress_bar"),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
        } else {
            HorizontalDivider(
                thickness = 0.75.dp,
                color = telemetry.cardBorder.copy(alpha = 0.55f)
            )
        }

        // Embedded Hardened Android WebView + Error/Security Overlays
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("in_app_webview"),
                factory = { ctx ->
                    val retained = sessionState.webView
                    if (retained != null) {
                        (retained.parent as? ViewGroup)?.removeView(retained)
                        sessionState.pendingLoadUrl?.let { pending ->
                            sessionState.pendingLoadUrl = null
                            retained.loadUrl(pending)
                        }
                        retained
                    } else {
                        WebView(ctx).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
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
                            val webViewInstance = this
                            CookieManager.getInstance().apply {
                                setAcceptCookie(true)
                                setAcceptThirdPartyCookies(webViewInstance, true)
                            }
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
                                    sessionState.loadProgress = newProgress
                                    sessionState.isLoading = newProgress < 100
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val targetUri = request?.url ?: return null
                                    val scheme = targetUri.scheme?.lowercase(Locale.US).orEmpty()
                                    if (scheme == "file" || scheme == "content") {
                                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                    }
                                    if (scheme != "http" && scheme != "https") return null
                                    val host = targetUri.host?.lowercase(Locale.US).orEmpty()
                                    if (host.isBlank()) return null

                                    val blockedByShield = sessionState.popupShieldEnabled &&
                                        BLOCKED_AD_HOST_KEYWORDS.any { host.contains(it) }
                                    val blockedByCustomDns = !blockedByShield &&
                                        CustomAppDns.isHostBlockedByCustomDns(host, request.isForMainFrame)

                                    if (blockedByShield || blockedByCustomDns) {
                                        view?.post { sessionState.blockedAdsCount += 1 }
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

                                    if (scheme == "magnet") {
                                        sessionState.captureMagnet(urlStr, onInvalid = latestShowMessage)
                                        return true
                                    }

                                    if (request.isForMainFrame && BrowserUrlPolicy.isDirectDownloadUrl(urlStr)) {
                                        handleDownloadIntercepted(
                                            rawUrl = urlStr,
                                            contentDisposition = null,
                                            mimeType = null,
                                            contentLength = -1L
                                        )
                                        return true
                                    }

                                    return when (
                                        val decision = BrowserUrlPolicy.evaluateNavigation(
                                            rawUrl = urlStr,
                                            configuredHttpHosts = latestConfiguredHttpHosts,
                                            sessionApprovedHttpHosts = sessionState.sessionApprovedHttpHosts
                                        )
                                    ) {
                                        is UrlNavigationDecision.Blocked -> {
                                            latestShowMessage(decision.reason)
                                            true
                                        }
                                        is UrlNavigationDecision.RequiresHttpExceptionPrompt -> {
                                            if (request.isForMainFrame) {
                                                sessionState.errorState = BrowserErrorState.PendingHttpApproval(
                                                    httpUrl = decision.httpUrl,
                                                    httpsCandidateUrl = decision.httpsCandidateUrl,
                                                    host = decision.host
                                                )
                                            }
                                            true
                                        }
                                        is UrlNavigationDecision.Allowed -> {
                                            if (sessionState.popupShieldEnabled &&
                                                BLOCKED_AD_HOST_KEYWORDS.any { decision.host.contains(it) }
                                            ) {
                                                sessionState.blockedAdsCount += 1
                                                latestShowMessage("Blocked ad redirect (${decision.host})")
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                    }
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    sessionState.isLoading = true
                                    sessionState.blockedAdsCount = 0
                                    if (!url.isNullOrBlank()) {
                                        sessionState.onPageNavigated(
                                            url = url,
                                            isLegacyHttp = url.startsWith("http://", ignoreCase = true)
                                        )
                                    }
                                    sessionState.canGoBack = view?.canGoBack() == true
                                    sessionState.canGoForward = view?.canGoForward() == true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    sessionState.isLoading = false
                                    if (!url.isNullOrBlank()) {
                                        sessionState.onPageNavigated(
                                            url = url,
                                            isLegacyHttp = url.startsWith("http://", ignoreCase = true)
                                        )
                                    }
                                    sessionState.canGoBack = view?.canGoBack() == true
                                    sessionState.canGoForward = view?.canGoForward() == true
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    if (request?.isForMainFrame != true) return
                                    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.errorCode else null
                                    if (code == ERROR_UNSUPPORTED_SCHEME) return
                                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        error?.description?.toString().orEmpty()
                                    } else {
                                        ""
                                    }.ifBlank { "Unable to reach site" }
                                    val failedUrl = request.url?.toString() ?: sessionState.currentUrl
                                    val host = BrowserUrlPolicy.formatHostLabel(failedUrl)
                                    sessionState.isLoading = false
                                    sessionState.errorState = BrowserErrorState.NetworkError(
                                        failedUrl = failedUrl,
                                        host = host,
                                        description = desc,
                                        errorCode = code
                                    )
                                }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?
                                ) {
                                    // TLS errors are strictly terminal — never proceed past certificate failures.
                                    handler?.cancel()
                                    val failedUrl = error?.url ?: sessionState.currentUrl
                                    val host = BrowserUrlPolicy.formatHostLabel(failedUrl)
                                        .ifBlank { sessionState.currentHostname }
                                    sessionState.isLoading = false
                                    sessionState.errorState = BrowserErrorState.TlsError(
                                        host = host,
                                        reason = "TLS certificate validation failed for $host. Connection blocked to protect your session."
                                    )
                                }
                            }
                            sessionState.webView = this
                            val initial = sessionState.pendingLoadUrl ?: sessionState.currentUrl
                            sessionState.pendingLoadUrl = null
                            if (initial.isNotBlank()) {
                                loadUrl(initial)
                            }
                        }
                    }
                },
                update = { view ->
                    sessionState.webView = view
                    sessionState.pendingLoadUrl?.let { pending ->
                        sessionState.pendingLoadUrl = null
                        view.loadUrl(pending)
                    }
                }
            )

            // Overlays for Recoverable Network Error, Terminal TLS Error, and Scoped HTTP Exception Prompt
            when (val err = sessionState.errorState) {
                is BrowserErrorState.NetworkError -> {
                    BrowserOverlayScaffold(
                        tag = "browser_network_error_state",
                        icon = Icons.Filled.ErrorOutline,
                        iconTint = telemetry.leecherAmber,
                        title = "Page Failed to Load",
                        subtitle = "${err.host.ifBlank { "Site" }} • ${err.description}"
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { sessionState.reload() },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("browser_error_retry_button")
                            ) {
                                Text("Retry")
                            }
                            OutlinedButton(
                                onClick = {
                                    val safe = BrowserUrlPolicy.validateAndNormalizeWebUrl(err.failedUrl)
                                    if (safe != null) {
                                        IntentActions.openUrlInBrowser(context, safe)
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("browser_error_open_external_button")
                            ) {
                                Text("Open in browser")
                            }
                        }
                    }
                }
                is BrowserErrorState.TlsError -> {
                    BrowserOverlayScaffold(
                        tag = "browser_tls_error_state",
                        icon = Icons.Filled.GppBad,
                        iconTint = telemetry.deadRose,
                        title = "Secure Connection Blocked",
                        subtitle = err.reason
                    ) {
                        Button(
                            onClick = {
                                if (sessionState.canGoBack) {
                                    sessionState.goBack()
                                } else {
                                    onDiscardSession()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("browser_tls_return_button")
                        ) {
                            Text(if (sessionState.canGoBack) "Go Back Safely" else "Close Unsafe Tab")
                        }
                    }
                }
                is BrowserErrorState.PendingHttpApproval -> {
                    BrowserOverlayScaffold(
                        tag = "browser_http_prompt_state",
                        icon = Icons.Filled.WarningAmber,
                        iconTint = telemetry.leecherAmber,
                        title = "Unencrypted HTTP Navigation",
                        subtitle = "${err.host} requested plain HTTP. MagnetVault defaults to HTTPS unless you allow a scoped exception for this mirror."
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    sessionState.errorState = null
                                    sessionState.loadUrlDirect(err.httpsCandidateUrl, isLegacyHttp = false)
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("browser_http_upgrade_https_button")
                            ) {
                                Text("Use HTTPS (Recommended)")
                            }
                            OutlinedButton(
                                onClick = {
                                    sessionState.approveHttpHostAndNavigate(err.host, err.httpUrl)
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("browser_http_allow_host_button")
                            ) {
                                Text("Allow HTTP for ${err.host} (This Session)")
                            }
                        }
                    }
                }
                null -> Unit
            }
        }
    }
}

@Composable
private fun BrowserOverlayScaffold(
    tag: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    actions: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
            .padding(24.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(
            shape = RoundedCornerShape(16.dp),
            elevated = true,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                actions()
            }
        }
    }
}
