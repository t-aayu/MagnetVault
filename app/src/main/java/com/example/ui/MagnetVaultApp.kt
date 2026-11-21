package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.auth.GoogleAuthManager
import com.example.data.firebase.CloudSyncRepository
import com.example.data.indexers.IndexerAggregatorRepository
import com.example.data.local.LocalPrefsStore
import com.example.ui.auth.AuthScreen
import com.example.ui.search.SearchScreen
import com.example.ui.settings.SettingsScreen
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

enum class AppDestination(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    SEARCH(R.string.nav_search, Icons.Filled.Search, Icons.Outlined.Search, "nav_search"),
    VAULT(R.string.nav_vault, Icons.Filled.Bookmarks, Icons.Outlined.Bookmarks, "nav_vault"),
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
}

@Composable
fun MagnetVaultApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsStore = remember(context) { LocalPrefsStore(context.applicationContext) }
    val isDarkTheme by prefsStore.darkThemeFlow.collectAsStateWithLifecycle(initialValue = true)
    val guestModeEnabled by prefsStore.guestModeEnabledFlow.collectAsStateWithLifecycle(initialValue = null)

    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose { Firebase.auth.removeAuthStateListener(listener) }
    }

    MagnetVaultTheme(darkTheme = isDarkTheme) {
        when {
            currentUser == null && guestModeEnabled == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            currentUser == null && guestModeEnabled == false -> {
                AuthScreen(
                    onAuthSuccess = { currentUser = Firebase.auth.currentUser },
                    onContinueWithoutSignIn = {
                        scope.launch { prefsStore.setGuestModeEnabled(true) }
                    }
                )
            }
            else -> {
                val user = currentUser
                val sessionKey = user?.uid ?: "guest_local"
                val isSignedIn = user != null
                key(sessionKey) {
                    MainAppShell(
                        sessionKey = sessionKey,
                        isSignedIn = isSignedIn,
                        userEmail = user?.email,
                        userName = user?.displayName,
                        prefsStore = prefsStore,
                        isDarkTheme = isDarkTheme,
                        onSignedIn = { currentUser = Firebase.auth.currentUser }
                    )
                }
            }
        }
    }
}

@Composable
private fun MainAppShell(
    sessionKey: String,
    isSignedIn: Boolean,
    userEmail: String?,
    userName: String?,
    prefsStore: LocalPrefsStore,
    isDarkTheme: Boolean,
    onSignedIn: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val cloudSyncRepo = remember(sessionKey, isSignedIn) {
        CloudSyncRepository(
            appContext = context.applicationContext,
            prefsStore = prefsStore,
            isCloudMode = isSignedIn
        )
    }
    val indexerRepo = remember { IndexerAggregatorRepository() }

    val searchVm: SearchViewModel = viewModel(
        key = "search_$sessionKey",
        factory = SearchViewModel.Factory(indexerRepo, cloudSyncRepo)
    )
    val vaultVm: VaultViewModel = viewModel(
        key = "vault_$sessionKey",
        factory = VaultViewModel.Factory(cloudSyncRepo)
    )
    val settingsVm: SettingsViewModel = viewModel(
        key = "settings_$sessionKey",
        factory = SettingsViewModel.Factory(prefsStore, cloudSyncRepo, indexerRepo)
    )

    val searchState by searchVm.uiState.collectAsStateWithLifecycle()
    var activeDestination by remember { mutableStateOf(AppDestination.SEARCH) }

    BackHandler(enabled = activeDestination != AppDestination.SEARCH) {
        activeDestination = AppDestination.SEARCH
    }

    fun showMessage(msg: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Collect MagnetReady & snackbar events at shell level so quick actions work from both Search and Vault tabs
    LaunchedEffect(searchVm) {
        searchVm.events.collect { event ->
            when (event) {
                is SearchUiEvent.ShowMessage -> showMessage(event.message)
                is SearchUiEvent.MagnetReady -> {
                    IntentActions.copyToClipboard(context, event.title, event.magnetUri)
                    if (!event.openBrowserUrl.isNullOrBlank()) {
                        val opened = IntentActions.openUrlInBrowser(context, event.openBrowserUrl)
                        val label = event.targetLabel ?: "browser"
                        showMessage(
                            if (opened) "Magnet copied — opening $label in browser"
                            else "Magnet copied to clipboard"
                        )
                    } else {
                        showMessage("Magnet link copied to clipboard")
                    }
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 680.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        AppDestination.entries.forEach { dest ->
                            val selected = activeDestination == dest
                            NavigationBarItem(
                                selected = selected,
                                onClick = { activeDestination = dest },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes), style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
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
                if (useNavRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        AppDestination.entries.forEach { dest ->
                            val selected = activeDestination == dest
                            NavigationRailItem(
                                selected = selected,
                                onClick = { activeDestination = dest },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes), style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 760.dp)
                    ) {
                        when (activeDestination) {
                            AppDestination.SEARCH -> SearchScreen(
                                viewModel = searchVm,
                                isSignedIn = isSignedIn,
                                isDarkTheme = isDarkTheme,
                                onToggleTheme = { settingsVm.setDarkTheme(!isDarkTheme) },
                                onOpenSettings = { activeDestination = AppDestination.SETTINGS },
                                onShowMessage = ::showMessage
                            )
                            AppDestination.VAULT -> VaultScreen(
                                viewModel = vaultVm,
                                isSignedIn = isSignedIn,
                                resolvingActionTorrentId = searchState.resolvingActionTorrentId,
                                onInspectTorrent = { torrent ->
                                    activeDestination = AppDestination.SEARCH
                                    searchVm.openTorrentDetail(torrent)
                                },
                                onCopyMagnet = { torrent ->
                                    searchVm.copyMagnetAndOptionallyOpen(torrent)
                                },
                                onOpenSeedr = { torrent ->
                                    searchVm.copyMagnetAndOptionallyOpen(
                                        torrent = torrent,
                                        openBrowserUrl = IntentActions.SEEDR_WEB_URL,
                                        targetLabel = "Seedr.cc"
                                    )
                                },
                                onOpenTorBox = { torrent ->
                                    searchVm.copyMagnetAndOptionallyOpen(
                                        torrent = torrent,
                                        openBrowserUrl = IntentActions.TORBOX_WEB_URL,
                                        targetLabel = "TorBox"
                                    )
                                },
                                onReplaySearch = { query, cat ->
                                    activeDestination = AppDestination.SEARCH
                                    searchVm.runSearchFromHistory(query, cat)
                                },
                                onShowMessage = ::showMessage
                            )
                            AppDestination.SETTINGS -> SettingsScreen(
                                viewModel = settingsVm,
                                isSignedIn = isSignedIn,
                                userEmail = userEmail,
                                userName = userName,
                                onSignInSuccess = onSignedIn,
                                onSignOut = {
                                    scope.launch { prefsStore.setGuestModeEnabled(false) }
                                    GoogleAuthManager.signOut(credentialManager, scope)
                                },
                                onShowMessage = ::showMessage
                            )
                        }
                    }
                }
            }
        }
    }
}
