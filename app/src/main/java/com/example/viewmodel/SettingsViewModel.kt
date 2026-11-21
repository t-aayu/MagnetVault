package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.firebase.CloudSyncRepository
import com.example.data.indexers.IndexerAggregatorRepository
import com.example.data.local.LocalPrefsStore
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.MirrorHealthResult
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

data class SettingsTransientState(
    val mirrorHealthMap: Map<String, MirrorHealthResult> = emptyMap(),
    val isPingingAll: Boolean = false
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

    val isDarkTheme: StateFlow<Boolean> = prefsStore.darkThemeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), true)

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

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setDarkTheme(enabled) }
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
                pingMirror(normalized)
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

    fun pingMirror(url: String) {
        viewModelScope.launch {
            _transient.update { state ->
                state.copy(mirrorHealthMap = state.mirrorHealthMap + (url to MirrorHealthResult(url = url, isChecking = true)))
            }
            val res = indexerRepo.checkMirrorHealth(url)
            _transient.update { state ->
                state.copy(mirrorHealthMap = state.mirrorHealthMap + (url to res))
            }
        }
    }

    fun pingAllMirrors() {
        viewModelScope.launch {
            val allUrls = mirrorConfigs.value.values.flatMap { it.mirrors }.distinct()
            _transient.update { state ->
                val loadingEntries = allUrls.associateWith { MirrorHealthResult(url = it, isChecking = true) }
                state.copy(isPingingAll = true, mirrorHealthMap = state.mirrorHealthMap + loadingEntries)
            }
            coroutineScope {
                allUrls.map { url ->
                    async {
                        val res = indexerRepo.checkMirrorHealth(url)
                        _transient.update { state ->
                            state.copy(mirrorHealthMap = state.mirrorHealthMap + (url to res))
                        }
                    }
                }.awaitAll()
            }
            _transient.update { it.copy(isPingingAll = false) }
            _messages.emit("Completed health check on ${allUrls.size} mirrors")
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

    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return if (withScheme.length in 10..480 && withScheme.contains('.')) withScheme else null
    }

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
