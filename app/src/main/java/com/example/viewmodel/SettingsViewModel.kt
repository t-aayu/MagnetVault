package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.firebase.CloudSyncRepository
import com.example.data.indexers.IndexerAggregatorRepository
import com.example.data.local.LocalPrefsStore
import com.example.data.local.SyncAccountPrefs
import com.example.data.network.CustomAppDns
import com.example.data.network.CustomDnsTestResult
import com.example.data.network.DnsDiagnosticsSnapshot
import com.example.data.sync.EnrollmentMergePreview
import com.example.data.sync.SyncSessionStatus
import com.example.model.AppAppearanceMode
import com.example.model.CustomDnsConfig
import com.example.model.CustomDnsPreset
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.MirrorHealthResult
import com.example.model.WebsiteOpenMode
import com.example.ui.browser.BrowserUrlPolicy
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsTransientState(
    val mirrorHealthMap: Map<String, MirrorHealthResult> = emptyMap(),
    val isPingingAll: Boolean = false,
    val dnsTestResult: CustomDnsTestResult? = null,
    val isTestingDns: Boolean = false,
    val isLoadingPreview: Boolean = false,
    val enrollmentPreview: EnrollmentMergePreview? = null,
    val enrollmentPreviewError: String? = null,
    val isApplyingEnrollment: Boolean = false
)

class SettingsViewModel(
    private val prefsStore: LocalPrefsStore,
    private val cloudSyncRepo: CloudSyncRepository,
    private val indexerRepo: IndexerAggregatorRepository
) : ViewModel() {

    private val _transient = MutableStateFlow(SettingsTransientState())
    val transient: StateFlow<SettingsTransientState> = _transient.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val dnsDiagnostics: StateFlow<DnsDiagnosticsSnapshot> = CustomAppDns.diagnostics

    val appearanceMode: StateFlow<AppAppearanceMode> = prefsStore.appearanceModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), AppAppearanceMode.OBSIDIAN)

    val isDarkTheme: StateFlow<Boolean> = prefsStore.darkThemeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), true)

    val searchHistoryEnabled: StateFlow<Boolean> = prefsStore.searchHistoryEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), true)

    val websiteOpenMode: StateFlow<WebsiteOpenMode> = prefsStore.websiteOpenModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), WebsiteOpenMode.IN_APP)

    val torrentBoxOpenMode: StateFlow<WebsiteOpenMode> = prefsStore.torrentBoxOpenModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), WebsiteOpenMode.IN_APP)

    val customDnsConfig: StateFlow<CustomDnsConfig> = prefsStore.customDnsConfigFlow
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000L),
            CustomDnsConfig(preset = CustomDnsPreset.ADGUARD)
        )

    val syncStatus: StateFlow<SyncSessionStatus> = cloudSyncRepo.syncStatus

    fun observeSyncPrefs(uid: String?): Flow<SyncAccountPrefs> =
        prefsStore.observeSyncPrefsForAccount(uid)

    val mirrorConfigs: StateFlow<Map<IndexerSource, MirrorConfig>> =
        cloudSyncRepo.observeMirrorConfigs()
            .catch { error ->
                val fsErr = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsErr == null && error is CancellationException) throw error
                emit(IndexerSource.entries.associateWith { MirrorConfig(provider = it) })
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = IndexerSource.entries.associateWith { MirrorConfig(provider = it) }
            )

    fun loadEnrollmentPreview(accountUid: String) {
        viewModelScope.launch {
            _transient.update {
                it.copy(
                    isLoadingPreview = true,
                    enrollmentPreview = null,
                    enrollmentPreviewError = null
                )
            }
            val result = withContext(Dispatchers.IO) {
                cloudSyncRepo.syncEngine.fetchEnrollmentPreview(accountUid)
            }
            result.onSuccess { preview ->
                _transient.update {
                    it.copy(isLoadingPreview = false, enrollmentPreview = preview)
                }
            }.onFailure { err ->
                _transient.update {
                    it.copy(
                        isLoadingPreview = false,
                        enrollmentPreviewError = err.localizedMessage ?: "Unable to fetch cloud preview"
                    )
                }
            }
        }
    }

    fun confirmEnableSync(
        accountUid: String,
        syncHistory: Boolean,
        syncMirrors: Boolean,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _transient.update { it.copy(isApplyingEnrollment = true) }
            val result = withContext(Dispatchers.IO) {
                cloudSyncRepo.syncEngine.enrollAccountAndSync(
                    accountUid = accountUid,
                    syncHistory = syncHistory,
                    syncMirrors = syncMirrors
                )
            }
            _transient.update { it.copy(isApplyingEnrollment = false) }
            result.onSuccess {
                _messages.emit("Cloud sync enabled for this account")
                onComplete()
            }.onFailure { err ->
                _messages.emit("Could not enable sync: ${err.localizedMessage ?: "error"}")
            }
        }
    }

    fun setHistorySyncOptIn(accountUid: String, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                cloudSyncRepo.syncEngine.setHistorySyncOptIn(accountUid, enabled)
            }
            _messages.emit(if (enabled) "Search history sync enabled" else "Search history sync paused")
        }
    }

    fun setMirrorsSyncOptIn(accountUid: String, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                cloudSyncRepo.syncEngine.setMirrorsSyncOptIn(accountUid, enabled)
            }
            _messages.emit(if (enabled) "Mirror configuration sync enabled" else "Mirror configuration sync paused")
        }
    }

    fun disableSync(accountUid: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                cloudSyncRepo.syncEngine.disableSyncForAccount(accountUid)
            }
            _messages.emit("Cloud sync turned off (local Vault retained)")
        }
    }

    fun syncNowOrRetry(accountUid: String) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                cloudSyncRepo.syncEngine.syncNowOrRetry(accountUid)
            }
            val msg = when {
                res.actionNeededCategory != null -> "Sync requires attention (${res.actionNeededCategory})"
                res.hadTransientFailure -> "Queued ${res.remainingPendingCount} item(s) for retry when online"
                else -> "Cloud sync completed"
            }
            _messages.emit(msg)
        }
    }

    fun setAppearanceMode(mode: AppAppearanceMode) {
        viewModelScope.launch {
            prefsStore.setAppearanceMode(mode)
            _messages.emit("Appearance set to ${mode.label}")
        }
    }

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setDarkTheme(enabled) }
    }

    fun setSearchHistoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsStore.setSearchHistoryEnabled(enabled)
            _messages.emit(
                if (enabled) "Recent searches enabled" else "Recent searches paused"
            )
        }
    }

    fun setWebsiteOpenMode(mode: WebsiteOpenMode) {
        viewModelScope.launch {
            prefsStore.setWebsiteOpenMode(mode)
            _messages.emit("Indexer websites will open in ${mode.label}")
        }
    }

    fun setTorrentBoxOpenMode(mode: WebsiteOpenMode) {
        viewModelScope.launch {
            prefsStore.setTorrentBoxOpenMode(mode)
            _messages.emit("Seedr.cc & TorBox will open in ${mode.label}")
        }
    }

    fun selectDnsPreset(preset: CustomDnsPreset) {
        viewModelScope.launch {
            val current = customDnsConfig.value
            val updated = current.copy(preset = preset)
            prefsStore.setCustomDnsConfig(updated)
            CustomAppDns.configure(updated)
            _transient.update { it.copy(dnsTestResult = null) }
            val msg = when (preset) {
                CustomDnsPreset.SYSTEM -> "Switched to System Default DNS"
                CustomDnsPreset.CUSTOM -> if (updated.customEndpoint.isBlank()) {
                    "Enter your custom HTTPS DoH URL or hostname below"
                } else {
                    "App DNS set to Custom DoH"
                }
                else -> "App DNS set to ${preset.displayName}"
            }
            _messages.emit(msg)
        }
    }

    fun saveCustomDnsEndpoint(rawEndpoint: String) {
        val normalized = CustomAppDns.validateAndNormalizeDohEndpoint(rawEndpoint)
        if (normalized == null) {
            viewModelScope.launch {
                _messages.emit("Enter a valid public https:// DoH URL or hostname (e.g. dns.nextdns.io/abc123)")
            }
            return
        }
        viewModelScope.launch {
            val updated = CustomDnsConfig(
                preset = CustomDnsPreset.CUSTOM,
                customEndpoint = normalized
            )
            prefsStore.setCustomDnsConfig(updated)
            CustomAppDns.configure(updated)
            _transient.update { it.copy(dnsTestResult = null) }
            _messages.emit("Custom DNS saved: $normalized")
            testActiveDns()
        }
    }

    fun testActiveDns() {
        val config = customDnsConfig.value
        viewModelScope.launch {
            _transient.update { it.copy(isTestingDns = true) }
            val result = withContext(Dispatchers.IO) {
                CustomAppDns.testEndpoint(config)
            }
            _transient.update { it.copy(isTestingDns = false, dnsTestResult = result) }
        }
    }

    // --- LIVE MIRROR EDITOR & HEALTH CHECKER ---
    fun toggleProviderEnabled(source: IndexerSource, enabled: Boolean) {
        viewModelScope.launch {
            val current = mirrorConfigs.value[source] ?: MirrorConfig(provider = source)
            cloudSyncRepo.saveMirrorConfig(current.copy(enabled = enabled)).onFailure { err ->
                _messages.emit(err.localizedMessage ?: "Failed to update provider")
            }
        }
    }

    fun addMirrorUrl(source: IndexerSource, rawUrl: String) {
        val normalized = normalizeUrl(rawUrl)
        if (normalized == null) {
            viewModelScope.launch { _messages.emit("Enter a valid https:// mirror URL") }
            return
        }
        viewModelScope.launch {
            val current = mirrorConfigs.value[source] ?: MirrorConfig(provider = source)
            val updatedMirrors = (listOf(normalized) + current.mirrors).distinct().take(12)
            val updated = current.copy(mirrors = updatedMirrors, activeMirrorUrl = normalized)
            cloudSyncRepo.saveMirrorConfig(updated).onSuccess {
                _messages.emit("Added & set primary mirror: $normalized")
                pingMirror(normalized, sourceHint = source)
            }.onFailure { err ->
                _messages.emit(err.localizedMessage ?: "Failed to save mirror")
            }
        }
    }

    fun removeMirrorUrl(source: IndexerSource, url: String) {
        viewModelScope.launch {
            val current = mirrorConfigs.value[source] ?: MirrorConfig(provider = source)
            val remaining = current.mirrors.filterNot { it == url }
            if (remaining.isEmpty()) {
                _messages.emit("At least one mirror must remain for ${source.displayName}")
                return@launch
            }
            val nextActive = if (current.activeMirrorUrl == url) remaining.first() else current.activeMirrorUrl
            cloudSyncRepo.saveMirrorConfig(current.copy(mirrors = remaining, activeMirrorUrl = nextActive))
        }
    }

    fun setPrimaryMirror(source: IndexerSource, url: String) {
        viewModelScope.launch {
            val current = mirrorConfigs.value[source] ?: MirrorConfig(provider = source)
            val reordered = listOf(url) + current.mirrors.filterNot { it == url }
            cloudSyncRepo.saveMirrorConfig(current.copy(mirrors = reordered, activeMirrorUrl = url))
                .onSuccess { _messages.emit("${source.displayName} primary set to $url") }
        }
    }

    fun resetMirrorsToDefaults() {
        viewModelScope.launch {
            cloudSyncRepo.resetAllMirrorsToDefault().onSuccess {
                _messages.emit("All mirror lists restored to latest defaults")
            }.onFailure { err ->
                _messages.emit(err.localizedMessage ?: "Reset failed")
            }
        }
    }

    fun pingMirror(url: String, sourceHint: IndexerSource? = null) {
        val inferredSource = sourceHint ?: inferSourceForMirror(url)
        viewModelScope.launch {
            _transient.update { state ->
                val prev = state.mirrorHealthMap[url]
                state.copy(
                    mirrorHealthMap = state.mirrorHealthMap + (
                        url to MirrorHealthResult(
                            url = url,
                            isChecking = true,
                            parserStatus = prev?.parserStatus ?: com.example.model.ParserCheckStatus.NEVER_RUN,
                            parserDetail = prev?.parserDetail
                        )
                    )
                )
            }
            val res = indexerRepo.checkMirrorHealth(
                url = url,
                sourceHint = inferredSource,
                verifyParser = false
            )
            _transient.update { state ->
                state.copy(mirrorHealthMap = state.mirrorHealthMap + (url to res))
            }
        }
    }

    fun verifyMirrorParser(source: IndexerSource, url: String) {
        viewModelScope.launch {
            _transient.update { state ->
                val prev = state.mirrorHealthMap[url]
                state.copy(
                    mirrorHealthMap = state.mirrorHealthMap + (
                        url to (prev?.copy(isChecking = true) ?: MirrorHealthResult(url = url, isChecking = true))
                    )
                )
            }
            val res = indexerRepo.checkMirrorHealth(
                url = url,
                sourceHint = source,
                verifyParser = true
            )
            _transient.update { state ->
                state.copy(mirrorHealthMap = state.mirrorHealthMap + (url to res))
            }
        }
    }

    fun pingAllMirrors() {
        viewModelScope.launch {
            val urlToSource = buildMap {
                mirrorConfigs.value.forEach { (source, cfg) ->
                    cfg.mirrors.forEach { url -> putIfAbsent(url, source) }
                }
            }
            val allUrls = urlToSource.keys.toList()
            _transient.update { state ->
                val loadingEntries = allUrls.associateWith { MirrorHealthResult(url = it, isChecking = true) }
                state.copy(isPingingAll = true, mirrorHealthMap = state.mirrorHealthMap + loadingEntries)
            }
            coroutineScope {
                allUrls.map { url ->
                    async {
                        val res = indexerRepo.checkMirrorHealth(
                            url = url,
                            sourceHint = urlToSource[url],
                            verifyParser = false
                        )
                        _transient.update { state ->
                            state.copy(mirrorHealthMap = state.mirrorHealthMap + (url to res))
                        }
                    }
                }.awaitAll()
            }
            _transient.update { it.copy(isPingingAll = false) }
            _messages.emit("Completed HTTP reachability check on ${allUrls.size} mirrors")
        }
    }

    fun promoteFastestHealthyMirror(source: IndexerSource) {
        val cfg = mirrorConfigs.value[source] ?: return
        val health = _transient.value.mirrorHealthMap
        val fastest = cfg.mirrors
            .mapNotNull { url -> health[url]?.takeIf { it.isReachable == true && it.latencyMs != null } }
            .minByOrNull { it.latencyMs ?: Long.MAX_VALUE }
        if (fastest != null) {
            setPrimaryMirror(source, fastest.url)
        } else {
            viewModelScope.launch { _messages.emit("Run Ping All Mirrors first to detect the fastest mirror.") }
        }
    }

    private fun inferSourceForMirror(url: String): IndexerSource? =
        mirrorConfigs.value.entries.firstOrNull { (_, cfg) -> url in cfg.mirrors }?.key

    private fun normalizeUrl(raw: String): String? =
        BrowserUrlPolicy.validateAndNormalizeWebUrl(raw.trim().trimEnd('/'))?.trimEnd('/')

    class Factory(
        private val prefsStore: LocalPrefsStore,
        private val cloudSyncRepo: CloudSyncRepository,
        private val indexerRepo: IndexerAggregatorRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(prefsStore, cloudSyncRepo, indexerRepo) as T
    }
}
