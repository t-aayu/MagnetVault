package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.IncomingShareRequest
import com.example.R
import com.example.data.auth.GoogleAuthManager
import com.example.data.firebase.CloudSyncRepository
import com.example.data.indexers.IndexerAggregatorRepository
import com.example.data.local.LocalPrefsStore
import com.example.data.network.CustomAppDns
import com.example.data.sync.SyncRuntimeRegistry
import com.example.data.sync.SyncSessionStatus
import com.example.domain.magnet.MagnetIntake
import com.example.domain.magnet.MagnetIntakeResolution
import com.example.domain.magnet.MagnetParseResult
import com.example.domain.magnet.toCanonicalReleaseGroup
import com.example.model.AppAppearanceMode
import com.example.model.CanonicalReleaseGroup
import com.example.model.CustomDnsConfig
import com.example.model.CustomDnsPreset
import com.example.ui.browser.InAppBrowserScreen
import com.example.ui.browser.MultiMagnetSelectionDialog
import com.example.ui.browser.UnsupportedShareDialog
import com.example.ui.browser.rememberSitesBrowserSessionState
import com.example.ui.handoff.HandoffFeedback
import com.example.ui.handoff.MagnetActionModalSheet
import com.example.ui.handoff.MagnetHandoffController
import com.example.ui.search.SearchScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.MagnetVaultTheme
import com.example.ui.vault.VaultScreen
import com.example.util.IntentActions
import com.example.viewmodel.SearchUiEvent
import com.example.viewmodel.SearchViewModel
import com.example.viewmodel.SettingsViewModel
import com.example.viewmodel.VaultViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

/**
 * Primary top-level destinations: Search, Vault, Sites.
 * Settings is accessed from the persistent top app bar.
 */
enum class AppDestination(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    SEARCH(R.string.nav_search, Icons.Filled.Search, Icons.Outlined.Search, "nav_search"),
    VAULT(R.string.nav_vault, Icons.Filled.Bookmarks, Icons.Outlined.Bookmarks, "nav_vault"),
    SITES(R.string.nav_sites, Icons.Filled.Language, Icons.Outlined.Language, "nav_sites")
}

@Composable
fun MagnetVaultApp(
    indexerRepoOverride: IndexerAggregatorRepository? = null,
    handoffControllerOverride: MagnetHandoffController? = null,
    incomingShare: IncomingShareRequest? = null
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val runtimeContainer = remember(appContext) { SyncRuntimeRegistry.getContainer(appContext) }
    val prefsStore = runtimeContainer.prefsStore
    val appearanceMode by prefsStore.appearanceModeFlow.collectAsStateWithLifecycle(
        initialValue = AppAppearanceMode.OBSIDIAN
    )
    val customDnsConfig by prefsStore.customDnsConfigFlow.collectAsStateWithLifecycle(
        initialValue = CustomDnsConfig(preset = CustomDnsPreset.ADGUARD)
    )

    LaunchedEffect(customDnsConfig) {
        CustomAppDns.configure(customDnsConfig)
    }

    val firebaseConfigured = remember(context) { GoogleAuthManager.isFirebaseConfigured(context) }
    var currentUser by remember { mutableStateOf(GoogleAuthManager.currentUserOrNull(context)) }

    DisposableEffect(firebaseConfigured) {
        if (!firebaseConfigured) {
            onDispose { }
        } else {
            val listener = FirebaseAuth.AuthStateListener { auth ->
                currentUser = auth.currentUser
            }
            Firebase.auth.addAuthStateListener(listener)
            onDispose { Firebase.auth.removeAuthStateListener(listener) }
        }
    }

    // Bind sync session lifecycle strictly to current authenticated UID;
    // signing in leaves sync OFF unless the user explicitly enrolled this account.
    LaunchedEffect(currentUser?.uid) {
        runtimeContainer.syncEngine.startOrUpdateSession(currentUser?.uid)
    }

    MagnetVaultTheme(appearanceMode = appearanceMode) {
        val user = currentUser
        val sessionKey = user?.uid ?: "local_vault"
        val isSignedIn = user != null
        // Local-first: launch directly into the workspace regardless of sign-in or Firebase config.
        key(sessionKey) {
            MainAppShell(
                sessionKey = sessionKey,
                isSignedIn = isSignedIn,
                userUid = user?.uid,
                userEmail = user?.email,
                userName = user?.displayName,
                firebaseConfigured = firebaseConfigured,
                prefsStore = prefsStore,
                indexerRepoOverride = indexerRepoOverride,
                handoffControllerOverride = handoffControllerOverride,
                incomingShare = incomingShare,
                onRefreshAuthUser = { currentUser = GoogleAuthManager.currentUserOrNull(context) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainAppShell(
    sessionKey: String,
    isSignedIn: Boolean,
    userUid: String?,
    userEmail: String?,
    userName: String?,
    firebaseConfigured: Boolean,
    prefsStore: LocalPrefsStore,
    indexerRepoOverride: IndexerAggregatorRepository?,
    handoffControllerOverride: MagnetHandoffController?,
    incomingShare: IncomingShareRequest?,
    onRefreshAuthUser: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val telemetry = LocalTelemetryColors.current

    val runtimeContainer = remember(appContext) { SyncRuntimeRegistry.getContainer(appContext) }
    val handoffController = remember(context, handoffControllerOverride) {
        handoffControllerOverride ?: MagnetHandoffController(context)
    }

    val cloudSyncRepo = remember(sessionKey, userUid, runtimeContainer) {
        CloudSyncRepository(
            appContext = appContext,
            prefsStore = prefsStore,
            isCloudMode = !userUid.isNullOrBlank(),
            accountUidOverride = userUid,
            localRepoOverride = runtimeContainer.localRepo,
            syncEngineOverride = runtimeContainer.syncEngine
        )
    }
    val indexerRepo = remember(indexerRepoOverride) {
        indexerRepoOverride ?: IndexerAggregatorRepository()
    }

    val searchVm: SearchViewModel = viewModel(
        key = "search_$sessionKey",
        factory = SearchViewModel.Factory(indexerRepo, cloudSyncRepo)
    )
    val vaultVm: VaultViewModel = viewModel(
        key = "vault_$sessionKey",
        factory = VaultViewModel.Factory(cloudSyncRepo, prefsStore, indexerRepo)
    )
    val settingsVm: SettingsViewModel = viewModel(
        key = "settings_$sessionKey",
        factory = SettingsViewModel.Factory(prefsStore, cloudSyncRepo, indexerRepo)
    )

    val websiteOpenMode by settingsVm.websiteOpenMode.collectAsStateWithLifecycle()
    val torrentBoxOpenMode by settingsVm.torrentBoxOpenMode.collectAsStateWithLifecycle()
    val customDnsConfig by settingsVm.customDnsConfig.collectAsStateWithLifecycle()
    val mirrorConfigs by settingsVm.mirrorConfigs.collectAsStateWithLifecycle()
    val syncStatus by runtimeContainer.syncEngine.syncStatus.collectAsStateWithLifecycle()
    val bookmarkedKeys by cloudSyncRepo.observeActiveBookmarkMatchKeys().collectAsStateWithLifecycle(
        initialValue = emptySet()
    )

    val browserSession = rememberSitesBrowserSessionState()
    DisposableEffect(Unit) {
        onDispose { browserSession.discardSession() }
    }

    var activeDestinationName by rememberSaveable { mutableStateOf(AppDestination.SEARCH.name) }
    val activeDestination = remember(activeDestinationName) {
        runCatching { AppDestination.valueOf(activeDestinationName) }.getOrDefault(AppDestination.SEARCH)
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var browserTargetUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var browserRequestNonce by rememberSaveable { mutableIntStateOf(0) }

    var lastHandledShareId by rememberSaveable { mutableStateOf<String?>(null) }
    var sharedSingleGroup by remember { mutableStateOf<CanonicalReleaseGroup?>(null) }
    var sharedMultipleMagnets by remember { mutableStateOf<List<MagnetParseResult.Valid>?>(null) }
    var sharedUnsupported by remember { mutableStateOf<MagnetIntakeResolution.Unsupported?>(null) }

    LaunchedEffect(incomingShare?.eventId) {
        val req = incomingShare ?: return@LaunchedEffect
        if (req.eventId == lastHandledShareId) return@LaunchedEffect
        lastHandledShareId = req.eventId
        when (val resolved = MagnetIntake.resolveSharedText(req.rawText)) {
            is MagnetIntakeResolution.Single -> {
                sharedMultipleMagnets = null
                sharedUnsupported = null
                sharedSingleGroup = resolved.group
            }
            is MagnetIntakeResolution.Multiple -> {
                sharedSingleGroup = null
                sharedUnsupported = null
                sharedMultipleMagnets = resolved.magnets
            }
            is MagnetIntakeResolution.Unsupported -> {
                sharedSingleGroup = null
                sharedMultipleMagnets = null
                sharedUnsupported = resolved
            }
            MagnetIntakeResolution.Empty -> Unit
        }
    }

    fun navigateToDestination(dest: AppDestination) {
        showSettings = false
        activeDestinationName = dest.name
    }

    val hasShareOverlay = sharedSingleGroup != null ||
        sharedMultipleMagnets != null ||
        sharedUnsupported != null

    // Back stack layering: share overlay / child browser sheets / WebView history -> Settings -> Search tab
    BackHandler(enabled = hasShareOverlay) {
        when {
            sharedSingleGroup != null -> sharedSingleGroup = null
            sharedMultipleMagnets != null -> sharedMultipleMagnets = null
            sharedUnsupported != null -> sharedUnsupported = null
        }
    }
    BackHandler(enabled = !hasShareOverlay && !showSettings && activeDestination != AppDestination.SEARCH) {
        activeDestinationName = AppDestination.SEARCH.name
    }
    BackHandler(enabled = !hasShareOverlay && showSettings) {
        showSettings = false
    }

    fun showMessage(msg: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    fun showUndoMessage(msg: String, actionLabel: String, onUndo: () -> Unit) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = actionLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                onUndo()
            }
        }
    }

    fun applyHandoffFeedback(feedback: HandoffFeedback) {
        if (!feedback.openInAppUrl.isNullOrBlank()) {
            browserTargetUrl = feedback.openInAppUrl
            browserRequestNonce++
            showSettings = false
            activeDestinationName = AppDestination.SITES.name
        }
        if (feedback.message.isNotBlank()) {
            showMessage(feedback.message)
        }
    }

    LaunchedEffect(searchVm) {
        searchVm.events.collect { event ->
            when (event) {
                is SearchUiEvent.ShowMessage -> showMessage(event.message)
                is SearchUiEvent.MagnetReady -> {
                    if (!event.openBrowserUrl.isNullOrBlank()) {
                        applyHandoffFeedback(
                            handoffController.openCloudBox(
                                title = event.title,
                                magnetUri = event.magnetUri,
                                targetUrl = event.openBrowserUrl,
                                targetLabel = event.targetLabel ?: "web box",
                                mode = torrentBoxOpenMode
                            )
                        )
                    } else {
                        applyHandoffFeedback(handoffController.copyMagnet(event.title, event.magnetUri))
                    }
                }
            }
        }
    }

    val enabledSourceCount = mirrorConfigs.values.count { it.enabled }
    val syncStatusShortLabel = when (val st = syncStatus) {
        SyncSessionStatus.LocalOnly -> "Local Vault"
        is SyncSessionStatus.Synced -> "Synced"
        is SyncSessionStatus.Pending -> if (st.isSyncing) "Syncing (${st.pendingCount})" else "${st.pendingCount} pending"
        is SyncSessionStatus.Offline -> if (st.pendingCount > 0) "Offline • ${st.pendingCount} queued" else "Offline"
        is SyncSessionStatus.ActionNeeded -> "Sync needs attention"
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 680.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = telemetry.chromeTranslucent,
                            titleContentColor = MaterialTheme.colorScheme.onBackground,
                            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        navigationIcon = {
                            if (showSettings) {
                                IconButton(
                                    onClick = { showSettings = false },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("settings_back_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back from Settings"
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_magnetvault_mark),
                                        contentDescription = stringResource(R.string.app_name),
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .border(
                                                width = 0.75.dp,
                                                color = telemetry.glassBorderHighlight,
                                                shape = RoundedCornerShape(9.dp)
                                            )
                                    )
                                }
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    text = if (showSettings) {
                                        stringResource(R.string.nav_settings)
                                    } else {
                                        stringResource(R.string.app_name)
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!showSettings) {
                                    Text(
                                        text = "$enabledSourceCount sources • $syncStatusShortLabel",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("topbar_status_subtitle")
                                    )
                                }
                            }
                        },
                        actions = {
                            if (!showSettings) {
                                IconButton(
                                    onClick = { showSettings = true },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("nav_settings")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Settings,
                                        contentDescription = stringResource(R.string.nav_settings),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    )
                    HorizontalDivider(
                        thickness = 0.75.dp,
                        color = telemetry.cardBorder.copy(alpha = 0.7f)
                    )
                }
            },
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Surface(
                        color = telemetry.glassSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, telemetry.glassBorderHighlight.copy(alpha = 0.55f)),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Snackbar(
                            snackbarData = data,
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            actionColor = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            bottomBar = {
                if (!useNavRail && !showSettings) {
                    Column {
                        HorizontalDivider(
                            thickness = 0.75.dp,
                            color = telemetry.cardBorder.copy(alpha = 0.7f)
                        )
                        NavigationBar(
                            containerColor = telemetry.chromeTranslucent,
                            tonalElevation = 0.dp,
                            windowInsets = WindowInsets.navigationBars,
                            modifier = Modifier.testTag("bottom_nav_bar")
                        ) {
                            val navColors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            AppDestination.entries.forEach { dest ->
                                val selected = activeDestination == dest
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToDestination(dest) },
                                    colors = navColors,
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(dest.labelRes),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail && !showSettings) {
                    val railColors = NavigationRailItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    NavigationRail(
                        containerColor = telemetry.chromeTranslucent,
                        modifier = Modifier
                            .fillMaxHeight()
                            .testTag("side_nav_rail")
                    ) {
                        Spacer(modifier = Modifier.width(4.dp))
                        AppDestination.entries.forEach { dest ->
                            val selected = activeDestination == dest
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToDestination(dest) },
                                colors = railColors,
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(dest.labelRes),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                    VerticalDivider(
                        thickness = 0.75.dp,
                        color = telemetry.cardBorder.copy(alpha = 0.7f)
                    )
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val containerModifier = when {
                        showSettings -> Modifier
                            .fillMaxSize()
                            .widthIn(max = 760.dp)
                        activeDestination == AppDestination.SITES || activeDestination == AppDestination.SEARCH ->
                            Modifier.fillMaxSize()
                        else -> Modifier
                            .fillMaxSize()
                            .widthIn(max = 760.dp)
                    }

                    Box(modifier = containerModifier) {
                        if (showSettings) {
                            SettingsScreen(
                                viewModel = settingsVm,
                                isSignedIn = isSignedIn,
                                userUid = userUid,
                                userEmail = userEmail,
                                userName = userName,
                                firebaseConfigured = firebaseConfigured,
                                onSignInSuccess = onRefreshAuthUser,
                                onSignOut = {
                                    runtimeContainer.syncEngine.stopSessionForSignOut(userUid)
                                    GoogleAuthManager.signOut(credentialManager, scope) {
                                        onRefreshAuthUser()
                                    }
                                },
                                onOpenTorrentBoxUrl = { url, label ->
                                    applyHandoffFeedback(
                                        handoffController.openCloudBox(
                                            title = label,
                                            magnetUri = null,
                                            targetUrl = url,
                                            targetLabel = label,
                                            mode = torrentBoxOpenMode
                                        )
                                    )
                                },
                                onShowMessage = ::showMessage
                            )
                        } else {
                            when (activeDestination) {
                                AppDestination.SEARCH -> SearchScreen(
                                    viewModel = searchVm,
                                    websiteOpenMode = websiteOpenMode,
                                    torrentBoxOpenMode = torrentBoxOpenMode,
                                    onReadClipboard = { handoffController.readClipboard() },
                                    onOpenSettings = { showSettings = true },
                                    onOpenLocalClient = { title, magnet ->
                                        applyHandoffFeedback(handoffController.openLocalClient(title, magnet))
                                    },
                                    onCopyMagnet = { title, magnet ->
                                        applyHandoffFeedback(handoffController.copyMagnet(title, magnet))
                                    },
                                    onOpenSeedr = { title, magnet ->
                                        applyHandoffFeedback(
                                            handoffController.openCloudBox(
                                                title = title,
                                                magnetUri = magnet,
                                                targetUrl = IntentActions.SEEDR_WEB_URL,
                                                targetLabel = "Seedr.cc",
                                                mode = torrentBoxOpenMode
                                            )
                                        )
                                    },
                                    onOpenTorBox = { title, magnet ->
                                        applyHandoffFeedback(
                                            handoffController.openCloudBox(
                                                title = title,
                                                magnetUri = magnet,
                                                targetUrl = IntentActions.TORBOX_WEB_URL,
                                                targetLabel = "TorBox",
                                                mode = torrentBoxOpenMode
                                            )
                                        )
                                    },
                                    onShareMagnet = { title, magnet ->
                                        applyHandoffFeedback(handoffController.shareMagnet(title, magnet))
                                    },
                                    onOpenSourcePage = { url ->
                                        applyHandoffFeedback(handoffController.openSourcePage(url, websiteOpenMode))
                                    },
                                    onShowMessage = ::showMessage
                                )

                                AppDestination.VAULT -> VaultScreen(
                                    viewModel = vaultVm,
                                    currentUserUid = userUid,
                                    torrentBoxOpenMode = torrentBoxOpenMode,
                                    onOpenLocalClient = { title, magnet ->
                                        applyHandoffFeedback(handoffController.openLocalClient(title, magnet))
                                    },
                                    onCopyMagnet = { title, magnet ->
                                        applyHandoffFeedback(handoffController.copyMagnet(title, magnet))
                                    },
                                    onOpenSeedr = { title, magnet ->
                                        applyHandoffFeedback(
                                            handoffController.openCloudBox(
                                                title = title,
                                                magnetUri = magnet,
                                                targetUrl = IntentActions.SEEDR_WEB_URL,
                                                targetLabel = "Seedr.cc",
                                                mode = torrentBoxOpenMode
                                            )
                                        )
                                    },
                                    onOpenTorBox = { title, magnet ->
                                        applyHandoffFeedback(
                                            handoffController.openCloudBox(
                                                title = title,
                                                magnetUri = magnet,
                                                targetUrl = IntentActions.TORBOX_WEB_URL,
                                                targetLabel = "TorBox",
                                                mode = torrentBoxOpenMode
                                            )
                                        )
                                    },
                                    onShareMagnet = { title, magnet ->
                                        applyHandoffFeedback(handoffController.shareMagnet(title, magnet))
                                    },
                                    onOpenSourcePage = { url ->
                                        applyHandoffFeedback(handoffController.openSourcePage(url, websiteOpenMode))
                                    },
                                    onReplaySearch = { query, cat ->
                                        navigateToDestination(AppDestination.SEARCH)
                                        searchVm.runSearchFromHistory(query, cat)
                                    },
                                    onShowMessage = ::showMessage,
                                    onShowUndoMessage = ::showUndoMessage
                                )

                                AppDestination.SITES -> InAppBrowserScreen(
                                    cloudSyncRepo = cloudSyncRepo,
                                    handoffController = handoffController,
                                    sessionState = browserSession,
                                    mirrorConfigs = mirrorConfigs,
                                    torrentBoxOpenMode = torrentBoxOpenMode,
                                    customDnsConfig = customDnsConfig,
                                    requestedUrl = browserTargetUrl,
                                    requestNonce = browserRequestNonce,
                                    onApplyHandoffFeedback = ::applyHandoffFeedback,
                                    onShowMessage = ::showMessage
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Incoming Android ACTION_SEND Share Sheet Resolution Overlays
    val singleShareGroup = sharedSingleGroup
    if (singleShareGroup != null) {
        val candidateKeys = remember(singleShareGroup) {
            listOfNotNull(
                singleShareGroup.verifiedHash?.storageKey,
                singleShareGroup.canonicalIdentity.storageKey,
                cloudSyncRepo.bookmarkDocId(singleShareGroup.toRepresentativeTorrentResult())
            )
        }
        val isBookmarked = remember(candidateKeys, bookmarkedKeys) {
            candidateKeys.any { it in bookmarkedKeys }
        }
        MagnetActionModalSheet(
            group = singleShareGroup,
            isBookmarked = isBookmarked,
            torrentBoxOpenMode = torrentBoxOpenMode,
            onDismiss = { sharedSingleGroup = null },
            onRetryResolveMagnet = {},
            onOpenLocalClient = { magnet ->
                sharedSingleGroup = null
                applyHandoffFeedback(handoffController.openLocalClient(singleShareGroup.title, magnet))
            },
            onCopyMagnet = { magnet ->
                sharedSingleGroup = null
                applyHandoffFeedback(handoffController.copyMagnet(singleShareGroup.title, magnet))
            },
            onToggleSave = {
                scope.launch {
                    if (isBookmarked) {
                        val key = candidateKeys.firstOrNull { it in bookmarkedKeys }
                            ?: singleShareGroup.groupKey
                        cloudSyncRepo.removeBookmarkByLocalIdOrDocId(key)
                            .onSuccess { showMessage("Removed from Vault") }
                    } else {
                        cloudSyncRepo.saveOrUpgradeBookmark(singleShareGroup.toRepresentativeTorrentResult())
                            .onSuccess { showMessage("Saved to Vault") }
                    }
                }
            },
            onOpenSeedr = { magnet ->
                sharedSingleGroup = null
                applyHandoffFeedback(
                    handoffController.openCloudBox(
                        title = singleShareGroup.title,
                        magnetUri = magnet,
                        targetUrl = IntentActions.SEEDR_WEB_URL,
                        targetLabel = "Seedr.cc",
                        mode = torrentBoxOpenMode
                    )
                )
            },
            onOpenTorBox = { magnet ->
                sharedSingleGroup = null
                applyHandoffFeedback(
                    handoffController.openCloudBox(
                        title = singleShareGroup.title,
                        magnetUri = magnet,
                        targetUrl = IntentActions.TORBOX_WEB_URL,
                        targetLabel = "TorBox",
                        mode = torrentBoxOpenMode
                    )
                )
            },
            onShareMagnet = { magnet ->
                sharedSingleGroup = null
                applyHandoffFeedback(handoffController.shareMagnet(singleShareGroup.title, magnet))
            },
            onOpenSourcePage = { url ->
                sharedSingleGroup = null
                applyHandoffFeedback(handoffController.openSourcePage(url, websiteOpenMode))
            }
        )
    }

    val multiMagnets = sharedMultipleMagnets
    if (multiMagnets != null) {
        MultiMagnetSelectionDialog(
            magnets = multiMagnets,
            onSelectMagnet = { selected ->
                sharedMultipleMagnets = null
                sharedSingleGroup = selected.toCanonicalReleaseGroup(originLabel = "Shared")
            },
            onDismiss = { sharedMultipleMagnets = null }
        )
    }

    val unsupportedShare = sharedUnsupported
    if (unsupportedShare != null) {
        UnsupportedShareDialog(
            reason = unsupportedShare.reason,
            rawPreview = unsupportedShare.rawPreview,
            onDismiss = { sharedUnsupported = null }
        )
    }
}
