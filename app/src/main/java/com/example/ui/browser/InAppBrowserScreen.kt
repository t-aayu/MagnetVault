package com.example.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.firebase.CloudSyncRepository
import com.example.model.CustomDnsConfig
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.WebsiteOpenMode
import com.example.ui.handoff.HandoffFeedback
import com.example.ui.handoff.MagnetActionModalSheet
import com.example.ui.handoff.MagnetHandoffController
import com.example.util.IntentActions
import kotlinx.coroutines.launch

/**
 * Redesigned Sites coordinator:
 * - Opens to Pinned & Custom Sites directory backed by Phase 2 Room storage.
 * - Retains active WebView page and history across app-tab switches via [sessionState].
 * - Routes captured magnet:? links into the shared [MagnetActionModalSheet].
 * - Routes intercepted file downloads into the pre-chooser [DownloadHandoffSheet].
 */
@Composable
fun InAppBrowserScreen(
    cloudSyncRepo: CloudSyncRepository,
    handoffController: MagnetHandoffController,
    sessionState: SitesBrowserSessionState,
    mirrorConfigs: Map<IndexerSource, MirrorConfig>,
    torrentBoxOpenMode: WebsiteOpenMode,
    customDnsConfig: CustomDnsConfig,
    requestedUrl: String?,
    requestNonce: Int,
    onApplyHandoffFeedback: (HandoffFeedback) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val customSites by cloudSyncRepo.observeCustomSites().collectAsStateWithLifecycle(initialValue = emptyList())
    val bookmarkedIds by cloudSyncRepo.observeActiveBookmarkMatchKeys().collectAsStateWithLifecycle(initialValue = emptySet())

    val configuredHttpHosts = remember(customSites, mirrorConfigs) {
        val urls = buildList {
            customSites.forEach { add(it.url) }
            mirrorConfigs.values.forEach { cfg -> addAll(cfg.mirrors) }
        }
        BrowserUrlPolicy.extractHttpHosts(urls)
    }

    // Handle external open-in-app requests (from Search/Vault/Settings) once per requestNonce
    LaunchedEffect(requestedUrl, requestNonce) {
        if (!requestedUrl.isNullOrBlank() && requestNonce > sessionState.lastHandledRequestNonce) {
            sessionState.lastHandledRequestNonce = requestNonce
            sessionState.navigate(
                rawUrl = requestedUrl,
                configuredHttpHosts = configuredHttpHosts,
                onBlocked = onShowMessage
            )
        }
    }

    // Layered Back handling inside Sites:
    // 1. Dismiss open magnet or download sheet / HTTP approval overlay first
    // 2. Navigate WebView history if browser is active and canGoBack
    // 3. Otherwise let MainAppShell's BackHandler follow app navigation
    val hasModalOverlay = sessionState.capturedMagnetGroup != null ||
        sessionState.capturedDownload != null ||
        sessionState.errorState is BrowserErrorState.PendingHttpApproval

    BackHandler(enabled = hasModalOverlay) {
        when {
            sessionState.capturedDownload != null -> sessionState.capturedDownload = null
            sessionState.capturedMagnetGroup != null -> sessionState.capturedMagnetGroup = null
            sessionState.errorState is BrowserErrorState.PendingHttpApproval -> {
                sessionState.errorState = null
            }
        }
    }

    BackHandler(enabled = !hasModalOverlay && sessionState.isBrowserOpen && sessionState.canGoBack) {
        sessionState.goBack()
    }

    if (sessionState.isBrowserOpen) {
        InAppBrowserChrome(
            sessionState = sessionState,
            customDnsConfig = customDnsConfig,
            configuredHttpHosts = configuredHttpHosts,
            onNavigateToUrl = { raw ->
                sessionState.navigate(
                    rawUrl = raw,
                    configuredHttpHosts = configuredHttpHosts,
                    onBlocked = onShowMessage
                )
            },
            onReturnToSitesDirectory = { sessionState.openSitesDirectoryView() },
            onDiscardSession = { sessionState.discardSession() },
            onShowMessage = onShowMessage
        )
    } else {
        SitesHomeContent(
            customSites = customSites,
            pinnedSites = PINNED_PRESET_SITES,
            sessionState = sessionState,
            onOpenUrl = { raw ->
                sessionState.navigate(
                    rawUrl = raw,
                    configuredHttpHosts = configuredHttpHosts,
                    onBlocked = onShowMessage
                )
            },
            onResumeActiveSession = { sessionState.resumeActiveSession() },
            onDiscardActiveSession = { sessionState.discardSession() },
            onAddCustomSite = { name, validUrl ->
                scope.launch {
                    cloudSyncRepo.addCustomSite(name, validUrl)
                        .onSuccess { onShowMessage("Saved custom website") }
                        .onFailure { onShowMessage("Could not save custom site") }
                }
            },
            onUpdateCustomSite = { siteId, name, validUrl ->
                scope.launch {
                    cloudSyncRepo.updateCustomSite(siteId, name, validUrl)
                        .onSuccess { onShowMessage("Updated custom website") }
                        .onFailure { onShowMessage("Could not update custom site") }
                }
            },
            onRemoveCustomSite = { siteId ->
                scope.launch { cloudSyncRepo.removeCustomSite(siteId) }
            },
            onShowMessage = onShowMessage
        )
    }

    // Shared Magnet Action Sheet when WebView captures a magnet:? link
    val magnetGroup = sessionState.capturedMagnetGroup
    if (magnetGroup != null) {
        val candidateKeys = remember(magnetGroup) {
            listOfNotNull(
                magnetGroup.verifiedHash?.storageKey,
                magnetGroup.canonicalIdentity.storageKey,
                cloudSyncRepo.bookmarkDocId(magnetGroup.toRepresentativeTorrentResult())
            )
        }
        val isBookmarked = remember(candidateKeys, bookmarkedIds) {
            candidateKeys.any { it in bookmarkedIds }
        }
        MagnetActionModalSheet(
            group = magnetGroup,
            isBookmarked = isBookmarked,
            torrentBoxOpenMode = torrentBoxOpenMode,
            onDismiss = { sessionState.capturedMagnetGroup = null },
            onRetryResolveMagnet = {},
            onOpenLocalClient = { magnet ->
                sessionState.capturedMagnetGroup = null
                onApplyHandoffFeedback(handoffController.openLocalClient(magnetGroup.title, magnet))
            },
            onCopyMagnet = { magnet ->
                sessionState.capturedMagnetGroup = null
                onApplyHandoffFeedback(handoffController.copyMagnet(magnetGroup.title, magnet))
            },
            onToggleSave = {
                scope.launch {
                    if (isBookmarked) {
                        val key = candidateKeys.firstOrNull { it in bookmarkedIds }
                            ?: magnetGroup.groupKey
                        cloudSyncRepo.removeBookmarkByLocalIdOrDocId(key)
                            .onSuccess { onShowMessage("Removed from Vault") }
                    } else {
                        cloudSyncRepo.saveOrUpgradeBookmark(magnetGroup.toRepresentativeTorrentResult())
                            .onSuccess { onShowMessage("Saved to Vault") }
                    }
                }
            },
            onOpenSeedr = { magnet ->
                sessionState.capturedMagnetGroup = null
                onApplyHandoffFeedback(
                    handoffController.openCloudBox(
                        title = magnetGroup.title,
                        magnetUri = magnet,
                        targetUrl = IntentActions.SEEDR_WEB_URL,
                        targetLabel = "Seedr.cc",
                        mode = torrentBoxOpenMode
                    )
                )
            },
            onOpenTorBox = { magnet ->
                sessionState.capturedMagnetGroup = null
                onApplyHandoffFeedback(
                    handoffController.openCloudBox(
                        title = magnetGroup.title,
                        magnetUri = magnet,
                        targetUrl = IntentActions.TORBOX_WEB_URL,
                        targetLabel = "TorBox",
                        mode = torrentBoxOpenMode
                    )
                )
            },
            onShareMagnet = { magnet ->
                sessionState.capturedMagnetGroup = null
                onApplyHandoffFeedback(handoffController.shareMagnet(magnetGroup.title, magnet))
            },
            onOpenSourcePage = { url ->
                sessionState.capturedMagnetGroup = null
                sessionState.navigate(
                    rawUrl = url,
                    configuredHttpHosts = configuredHttpHosts,
                    onBlocked = onShowMessage
                )
            }
        )
    }

    // Pre-chooser Download Handoff Sheet (always shows hostname, filename & session boundary first)
    val download = sessionState.capturedDownload
    if (download != null) {
        DownloadHandoffSheet(
            download = download,
            onDismiss = { sessionState.capturedDownload = null },
            onChooseDownloadManager = { item ->
                sessionState.capturedDownload = null
                onApplyHandoffFeedback(handoffController.launchDownloadManager(item))
            },
            onCopyDownloadLink = { item ->
                sessionState.capturedDownload = null
                onApplyHandoffFeedback(handoffController.copyDownloadLink(item))
            },
            onShareDownloadLink = { item ->
                sessionState.capturedDownload = null
                onApplyHandoffFeedback(handoffController.shareDownloadLink(item))
            }
        )
    }
}
