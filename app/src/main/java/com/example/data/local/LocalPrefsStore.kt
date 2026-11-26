package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AppAppearanceMode
import com.example.model.BookmarkRecord
import com.example.model.CustomDnsConfig
import com.example.model.CustomDnsPreset
import com.example.model.CustomSite
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.QualityAndHealthParser
import com.example.model.SearchHistoryRecord
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import com.example.model.WebsiteOpenMode
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val Context.magnetVaultDataStore by preferencesDataStore(name = "magnetvault_local_prefs")

data class SyncAccountPrefs(
    val enrolled: Boolean = false,
    val syncHistory: Boolean = false,
    val syncMirrors: Boolean = false
)

data class LegacyDataStoreSnapshot(
    val bookmarksRaw: String?,
    val historyRaw: String?,
    val mirrorsRaw: String?,
    val customSitesRaw: String?
) {
    val hasAnyPayloads: Boolean
        get() = !bookmarksRaw.isNullOrBlank() ||
            !historyRaw.isNullOrBlank() ||
            !mirrorsRaw.isNullOrBlank() ||
            !customSitesRaw.isNullOrBlank()
}

class LocalPrefsStore(private val context: Context) {
    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val APPEARANCE_MODE = stringPreferencesKey("appearance_mode")
        val SEARCH_HISTORY_ENABLED = booleanPreferencesKey("search_history_enabled")
        val GUEST_MODE_ENABLED = booleanPreferencesKey("guest_mode_enabled")
        val WEBSITE_OPEN_MODE = stringPreferencesKey("website_open_mode")
        val TORRENT_BOX_OPEN_MODE = stringPreferencesKey("torrent_box_open_mode")
        val CUSTOM_DNS_PRESET = stringPreferencesKey("custom_dns_preset")
        val CUSTOM_DNS_ENDPOINT = stringPreferencesKey("custom_dns_endpoint")
        val CUSTOM_SITES_JSON = stringPreferencesKey("custom_sites_json")
        val LOCAL_BOOKMARKS_JSON = stringPreferencesKey("local_bookmarks_json")
        val LOCAL_HISTORY_JSON = stringPreferencesKey("local_history_json")
        val LOCAL_MIRRORS_JSON = stringPreferencesKey("local_mirrors_json")
        val SYNC_ACCOUNT_PREFS_JSON = stringPreferencesKey("sync_account_prefs_json")
        val ACTIVE_SYNC_UID = stringPreferencesKey("active_sync_uid")
    }

    val appearanceModeFlow: Flow<AppAppearanceMode> = context.magnetVaultDataStore.data.map { prefs ->
        val rawMode = prefs[Keys.APPEARANCE_MODE]
        when {
            !rawMode.isNullOrBlank() -> AppAppearanceMode.fromNameSafe(rawMode)
            prefs.contains(Keys.DARK_THEME) -> {
                if (prefs[Keys.DARK_THEME] == true) AppAppearanceMode.OBSIDIAN else AppAppearanceMode.ALABASTER
            }
            else -> AppAppearanceMode.OBSIDIAN
        }
    }

    val darkThemeFlow: Flow<Boolean> = appearanceModeFlow.map { mode ->
        mode != AppAppearanceMode.ALABASTER
    }

    val searchHistoryEnabledFlow: Flow<Boolean> = context.magnetVaultDataStore.data.map { prefs ->
        prefs[Keys.SEARCH_HISTORY_ENABLED] ?: true
    }

    val guestModeEnabledFlow: Flow<Boolean> = context.magnetVaultDataStore.data.map { prefs ->
        prefs[Keys.GUEST_MODE_ENABLED] ?: false
    }

    val websiteOpenModeFlow: Flow<WebsiteOpenMode> = context.magnetVaultDataStore.data.map { prefs ->
        WebsiteOpenMode.fromNameSafe(prefs[Keys.WEBSITE_OPEN_MODE] ?: WebsiteOpenMode.IN_APP.name)
    }

    val torrentBoxOpenModeFlow: Flow<WebsiteOpenMode> = context.magnetVaultDataStore.data.map { prefs ->
        WebsiteOpenMode.fromNameSafe(prefs[Keys.TORRENT_BOX_OPEN_MODE] ?: WebsiteOpenMode.IN_APP.name)
    }

    val customDnsConfigFlow: Flow<CustomDnsConfig> = context.magnetVaultDataStore.data.map { prefs ->
        CustomDnsConfig(
            preset = CustomDnsPreset.fromNameSafe(prefs[Keys.CUSTOM_DNS_PRESET] ?: CustomDnsPreset.ADGUARD.name),
            customEndpoint = prefs[Keys.CUSTOM_DNS_ENDPOINT] ?: ""
        )
    }

    val activeSyncUidFlow: Flow<String?> = context.magnetVaultDataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_SYNC_UID]?.takeIf { it.isNotBlank() }
    }

    val syncAccountPrefsMapFlow: Flow<Map<String, SyncAccountPrefs>> = context.magnetVaultDataStore.data.map { prefs ->
        decodeSyncAccountPrefs(prefs[Keys.SYNC_ACCOUNT_PREFS_JSON])
    }

    fun observeSyncPrefsForAccount(uid: String?): Flow<SyncAccountPrefs> =
        syncAccountPrefsMapFlow.map { map ->
            if (uid.isNullOrBlank()) SyncAccountPrefs() else map[uid] ?: SyncAccountPrefs()
        }

    suspend fun getSyncPrefsForAccount(uid: String): SyncAccountPrefs =
        observeSyncPrefsForAccount(uid).first()

    suspend fun setActiveSyncUid(uid: String?) {
        context.magnetVaultDataStore.edit { prefs ->
            if (uid.isNullOrBlank()) {
                prefs.remove(Keys.ACTIVE_SYNC_UID)
            } else {
                prefs[Keys.ACTIVE_SYNC_UID] = uid
            }
        }
    }

    suspend fun setSyncEnrollment(
        uid: String,
        enrolled: Boolean,
        syncHistory: Boolean = false,
        syncMirrors: Boolean = false
    ) {
        if (uid.isBlank()) return
        context.magnetVaultDataStore.edit { prefs ->
            val current = decodeSyncAccountPrefs(prefs[Keys.SYNC_ACCOUNT_PREFS_JSON]).toMutableMap()
            current[uid] = SyncAccountPrefs(
                enrolled = enrolled,
                syncHistory = if (enrolled) syncHistory else false,
                syncMirrors = if (enrolled) syncMirrors else false
            )
            prefs[Keys.SYNC_ACCOUNT_PREFS_JSON] = encodeSyncAccountPrefs(current)
            if (enrolled) {
                prefs[Keys.ACTIVE_SYNC_UID] = uid
            } else if (prefs[Keys.ACTIVE_SYNC_UID] == uid) {
                prefs.remove(Keys.ACTIVE_SYNC_UID)
            }
        }
    }

    suspend fun setHistorySyncOptIn(uid: String, enabled: Boolean) {
        if (uid.isBlank()) return
        context.magnetVaultDataStore.edit { prefs ->
            val current = decodeSyncAccountPrefs(prefs[Keys.SYNC_ACCOUNT_PREFS_JSON]).toMutableMap()
            val prev = current[uid] ?: SyncAccountPrefs()
            current[uid] = prev.copy(syncHistory = enabled && prev.enrolled)
            prefs[Keys.SYNC_ACCOUNT_PREFS_JSON] = encodeSyncAccountPrefs(current)
        }
    }

    suspend fun setMirrorsSyncOptIn(uid: String, enabled: Boolean) {
        if (uid.isBlank()) return
        context.magnetVaultDataStore.edit { prefs ->
            val current = decodeSyncAccountPrefs(prefs[Keys.SYNC_ACCOUNT_PREFS_JSON]).toMutableMap()
            val prev = current[uid] ?: SyncAccountPrefs()
            current[uid] = prev.copy(syncMirrors = enabled && prev.enrolled)
            prefs[Keys.SYNC_ACCOUNT_PREFS_JSON] = encodeSyncAccountPrefs(current)
        }
    }

    suspend fun readLegacySnapshot(): LegacyDataStoreSnapshot {
        val prefs = context.magnetVaultDataStore.data.first()
        return LegacyDataStoreSnapshot(
            bookmarksRaw = prefs[Keys.LOCAL_BOOKMARKS_JSON],
            historyRaw = prefs[Keys.LOCAL_HISTORY_JSON],
            mirrorsRaw = prefs[Keys.LOCAL_MIRRORS_JSON],
            customSitesRaw = prefs[Keys.CUSTOM_SITES_JSON]
        )
    }

    internal suspend fun writeRawLegacySnapshotForTesting(
        bookmarksRaw: String? = null,
        historyRaw: String? = null,
        mirrorsRaw: String? = null,
        customSitesRaw: String? = null
    ) {
        context.magnetVaultDataStore.edit { prefs ->
            if (bookmarksRaw != null) prefs[Keys.LOCAL_BOOKMARKS_JSON] = bookmarksRaw else prefs.remove(Keys.LOCAL_BOOKMARKS_JSON)
            if (historyRaw != null) prefs[Keys.LOCAL_HISTORY_JSON] = historyRaw else prefs.remove(Keys.LOCAL_HISTORY_JSON)
            if (mirrorsRaw != null) prefs[Keys.LOCAL_MIRRORS_JSON] = mirrorsRaw else prefs.remove(Keys.LOCAL_MIRRORS_JSON)
            if (customSitesRaw != null) prefs[Keys.CUSTOM_SITES_JSON] = customSitesRaw else prefs.remove(Keys.CUSTOM_SITES_JSON)
        }
    }

    internal suspend fun clearAllForTesting() {
        context.magnetVaultDataStore.edit { it.clear() }
    }

    val customSitesFlow: Flow<List<CustomSite>> = context.magnetVaultDataStore.data.map { prefs ->
        decodeCustomSites(prefs[Keys.CUSTOM_SITES_JSON])
    }

    suspend fun setAppearanceMode(mode: AppAppearanceMode) {
        context.magnetVaultDataStore.edit { prefs ->
            prefs[Keys.APPEARANCE_MODE] = mode.name
            if (mode != AppAppearanceMode.SYSTEM) {
                prefs[Keys.DARK_THEME] = (mode == AppAppearanceMode.OBSIDIAN)
            }
        }
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.magnetVaultDataStore.edit { prefs ->
            prefs[Keys.DARK_THEME] = enabled
            prefs[Keys.APPEARANCE_MODE] = if (enabled) AppAppearanceMode.OBSIDIAN.name else AppAppearanceMode.ALABASTER.name
        }
    }

    suspend fun setSearchHistoryEnabled(enabled: Boolean) {
        context.magnetVaultDataStore.edit { it[Keys.SEARCH_HISTORY_ENABLED] = enabled }
    }

    suspend fun setGuestModeEnabled(enabled: Boolean) {
        context.magnetVaultDataStore.edit { it[Keys.GUEST_MODE_ENABLED] = enabled }
    }

    suspend fun setWebsiteOpenMode(mode: WebsiteOpenMode) {
        context.magnetVaultDataStore.edit { it[Keys.WEBSITE_OPEN_MODE] = mode.name }
    }

    suspend fun setTorrentBoxOpenMode(mode: WebsiteOpenMode) {
        context.magnetVaultDataStore.edit { it[Keys.TORRENT_BOX_OPEN_MODE] = mode.name }
    }

    suspend fun setCustomDnsConfig(config: CustomDnsConfig) {
        context.magnetVaultDataStore.edit { prefs ->
            prefs[Keys.CUSTOM_DNS_PRESET] = config.preset.name
            prefs[Keys.CUSTOM_DNS_ENDPOINT] = config.customEndpoint
        }
    }

    suspend fun addCustomSite(name: String, normalizedUrl: String) {
        val cleanName = name.trim().take(40).ifBlank {
            normalizedUrl.removePrefix("https://").removePrefix("http://").substringBefore("/").take(30)
        }
        val siteId = QualityAndHealthParser.sanitizeDocumentId("site_${normalizedUrl.lowercase(Locale.US).hashCode().toUInt()}")
        context.magnetVaultDataStore.edit { prefs ->
            val current = decodeCustomSites(prefs[Keys.CUSTOM_SITES_JSON]).toMutableList()
            current.removeAll { it.url.equals(normalizedUrl, ignoreCase = true) || it.id == siteId }
            current.add(0, CustomSite(id = siteId, name = cleanName, url = normalizedUrl, isCustom = true))
            prefs[Keys.CUSTOM_SITES_JSON] = encodeCustomSites(current.take(25))
        }
    }

    suspend fun removeCustomSite(siteId: String) {
        context.magnetVaultDataStore.edit { prefs ->
            val updated = decodeCustomSites(prefs[Keys.CUSTOM_SITES_JSON]).filterNot { it.id == siteId }
            prefs[Keys.CUSTOM_SITES_JSON] = encodeCustomSites(updated)
        }
    }

    // --- LOCAL BOOKMARKS (used when running without Sign-In) ---
    val localBookmarksFlow: Flow<List<BookmarkRecord>> = context.magnetVaultDataStore.data.map { prefs ->
        decodeBookmarks(prefs[Keys.LOCAL_BOOKMARKS_JSON])
    }

    suspend fun addLocalBookmark(docId: String, torrent: TorrentResult, replaceDocId: String? = null) {
        context.magnetVaultDataStore.edit { prefs ->
            val current = decodeBookmarks(prefs[Keys.LOCAL_BOOKMARKS_JSON]).toMutableList()
            val preservedCreatedAt = current.firstOrNull {
                it.id == docId || (replaceDocId != null && it.id == replaceDocId)
            }?.createdAt ?: Timestamp.now()
            current.removeAll { it.id == docId || (replaceDocId != null && it.id == replaceDocId) }
            val nowTs = Timestamp.now()
            current.add(
                0,
                BookmarkRecord(
                    id = docId,
                    userId = "local",
                    title = torrent.title.trim().ifBlank { "Untitled Torrent" }.take(500),
                    source = torrent.source.name,
                    category = torrent.category.name,
                    sizeLabel = torrent.sizeLabel.take(64),
                    sizeBytes = torrent.sizeBytes.coerceAtLeast(0L),
                    seeders = torrent.seeders.toLong().coerceAtLeast(0L),
                    leechers = torrent.leechers.toLong().coerceAtLeast(0L),
                    uploadDateLabel = torrent.uploadDateLabel.take(64),
                    magnetUri = (torrent.magnetUri ?: "").take(4000),
                    infoHash = torrent.infoHash.take(128),
                    detailPageUrl = torrent.detailPageUrl.take(1000),
                    qualityTags = torrent.qualityTags.map { it.label }.take(10),
                    createdAt = preservedCreatedAt,
                    updatedAt = nowTs
                )
            )
            prefs[Keys.LOCAL_BOOKMARKS_JSON] = encodeBookmarks(current.take(200))
        }
    }

    suspend fun removeLocalBookmark(bookmarkId: String) {
        context.magnetVaultDataStore.edit { prefs ->
            val current = decodeBookmarks(prefs[Keys.LOCAL_BOOKMARKS_JSON])
                .filterNot { it.id == bookmarkId }
            prefs[Keys.LOCAL_BOOKMARKS_JSON] = encodeBookmarks(current)
        }
    }

    // --- LOCAL SEARCH HISTORY ---
    val localSearchHistoryFlow: Flow<List<SearchHistoryRecord>> = context.magnetVaultDataStore.data.map { prefs ->
        decodeHistory(prefs[Keys.LOCAL_HISTORY_JSON])
    }

    suspend fun recordLocalSearchQuery(query: String, category: TorrentCategory) {
        val trimmed = query.trim().take(200)
        if (trimmed.isBlank()) return
        val entryId = QualityAndHealthParser.sanitizeDocumentId(
            "q_${trimmed.lowercase(Locale.US).hashCode().toUInt()}"
        )
        context.magnetVaultDataStore.edit { prefs ->
            val current = decodeHistory(prefs[Keys.LOCAL_HISTORY_JSON]).toMutableList()
            current.removeAll { it.id == entryId }
            val nowTs = Timestamp.now()
            current.add(
                0,
                SearchHistoryRecord(
                    id = entryId,
                    userId = "local",
                    query = trimmed,
                    category = category.name,
                    createdAt = nowTs,
                    updatedAt = nowTs
                )
            )
            prefs[Keys.LOCAL_HISTORY_JSON] = encodeHistory(current.take(20))
        }
    }

    suspend fun deleteLocalSearchHistory(entryId: String) {
        context.magnetVaultDataStore.edit { prefs ->
            val updated = decodeHistory(prefs[Keys.LOCAL_HISTORY_JSON]).filterNot { it.id == entryId }
            prefs[Keys.LOCAL_HISTORY_JSON] = encodeHistory(updated)
        }
    }

    suspend fun clearAllLocalSearchHistory() {
        context.magnetVaultDataStore.edit { prefs ->
            prefs.remove(Keys.LOCAL_HISTORY_JSON)
        }
    }

    // --- LOCAL MIRROR CONFIGS ---
    val localMirrorConfigsFlow: Flow<Map<IndexerSource, MirrorConfig>> = context.magnetVaultDataStore.data.map { prefs ->
        decodeMirrorConfigs(prefs[Keys.LOCAL_MIRRORS_JSON])
    }

    suspend fun saveLocalMirrorConfig(config: MirrorConfig) {
        context.magnetVaultDataStore.edit { prefs ->
            val current = decodeMirrorConfigs(prefs[Keys.LOCAL_MIRRORS_JSON]).toMutableMap()
            val cleanMirrors = config.mirrors
                .map { it.trim().trimEnd('/') }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
                .take(12)
                .ifEmpty { config.provider.defaultMirrors }
            val active = config.activeMirrorUrl.trim().trimEnd('/')
                .takeIf { it in cleanMirrors } ?: cleanMirrors.first()
            current[config.provider] = config.copy(mirrors = cleanMirrors, activeMirrorUrl = active)
            prefs[Keys.LOCAL_MIRRORS_JSON] = encodeMirrorConfigs(current)
        }
    }

    suspend fun resetLocalMirrorsToDefault() {
        context.magnetVaultDataStore.edit { prefs ->
            prefs.remove(Keys.LOCAL_MIRRORS_JSON)
        }
    }

    // --- JSON SERIALIZERS ---
    private fun encodeBookmarks(list: List<BookmarkRecord>): String {
        val arr = JSONArray()
        list.forEach { b ->
            val obj = JSONObject()
            obj.put("id", b.id)
            obj.put("title", b.title)
            obj.put("source", b.source)
            obj.put("category", b.category)
            obj.put("sizeLabel", b.sizeLabel)
            obj.put("sizeBytes", b.sizeBytes)
            obj.put("seeders", b.seeders)
            obj.put("leechers", b.leechers)
            obj.put("uploadDateLabel", b.uploadDateLabel)
            obj.put("magnetUri", b.magnetUri)
            obj.put("infoHash", b.infoHash)
            obj.put("detailPageUrl", b.detailPageUrl)
            obj.put("qualityTags", JSONArray(b.qualityTags))
            obj.put("createdEpoch", b.createdAt?.seconds ?: 0L)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun decodeBookmarks(raw: String?): List<BookmarkRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val tagsArr = o.optJSONArray("qualityTags")
                    val tags = buildList {
                        if (tagsArr != null) {
                            for (j in 0 until tagsArr.length()) {
                                add(tagsArr.optString(j))
                            }
                        }
                    }
                    val epoch = o.optLong("createdEpoch", 0L)
                    val ts = if (epoch > 0L) Timestamp(epoch, 0) else null
                    add(
                        BookmarkRecord(
                            id = o.optString("id"),
                            userId = "local",
                            title = o.optString("title"),
                            source = o.optString("source", IndexerSource.X1337.name),
                            category = o.optString("category", TorrentCategory.ALL.name),
                            sizeLabel = o.optString("sizeLabel"),
                            sizeBytes = o.optLong("sizeBytes", 0L),
                            seeders = o.optLong("seeders", 0L),
                            leechers = o.optLong("leechers", 0L),
                            uploadDateLabel = o.optString("uploadDateLabel"),
                            magnetUri = o.optString("magnetUri"),
                            infoHash = o.optString("infoHash"),
                            detailPageUrl = o.optString("detailPageUrl"),
                            qualityTags = tags,
                            createdAt = ts,
                            updatedAt = ts
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeHistory(list: List<SearchHistoryRecord>): String {
        val arr = JSONArray()
        list.forEach { h ->
            val obj = JSONObject()
            obj.put("id", h.id)
            obj.put("query", h.query)
            obj.put("category", h.category)
            obj.put("updatedEpoch", h.updatedAt?.seconds ?: 0L)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun decodeHistory(raw: String?): List<SearchHistoryRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val epoch = o.optLong("updatedEpoch", 0L)
                    val ts = if (epoch > 0L) Timestamp(epoch, 0) else null
                    add(
                        SearchHistoryRecord(
                            id = o.optString("id"),
                            userId = "local",
                            query = o.optString("query"),
                            category = o.optString("category", TorrentCategory.ALL.name),
                            createdAt = ts,
                            updatedAt = ts
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeMirrorConfigs(map: Map<IndexerSource, MirrorConfig>): String {
        val root = JSONObject()
        map.forEach { (source, cfg) ->
            val obj = JSONObject()
            obj.put("enabled", cfg.enabled)
            obj.put("activeMirrorUrl", cfg.activeMirrorUrl)
            obj.put("mirrors", JSONArray(cfg.mirrors))
            root.put(source.name, obj)
        }
        return root.toString()
    }

    private fun decodeMirrorConfigs(raw: String?): Map<IndexerSource, MirrorConfig> {
        val defaults = IndexerSource.entries.associateWith { MirrorConfig(provider = it) }
        if (raw.isNullOrBlank()) return defaults
        return runCatching {
            val root = JSONObject(raw)
            IndexerSource.entries.associateWith { source ->
                val o = root.optJSONObject(source.name)
                if (o == null) {
                    MirrorConfig(provider = source)
                } else {
                    val arr = o.optJSONArray("mirrors")
                    val savedMirrors = buildList {
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val u = arr.optString(i).trim().trimEnd('/')
                                if (u.startsWith("http")) add(u)
                            }
                        }
                    }.ifEmpty { source.defaultMirrors }
                    val active = o.optString("activeMirrorUrl", "")
                        .takeIf { it in savedMirrors } ?: savedMirrors.first()
                    MirrorConfig(
                        provider = source,
                        enabled = o.optBoolean("enabled", true),
                        mirrors = savedMirrors,
                        activeMirrorUrl = active
                    )
                }
            }
        }.getOrDefault(defaults)
    }

    private fun encodeCustomSites(list: List<CustomSite>): String {
        val arr = JSONArray()
        list.forEach { site ->
            val obj = JSONObject()
            obj.put("id", site.id)
            obj.put("name", site.name)
            obj.put("url", site.url)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun decodeCustomSites(raw: String?): List<CustomSite> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url", "").trim()
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        add(
                            CustomSite(
                                id = o.optString("id").ifBlank { "site_$i" },
                                name = o.optString("name").ifBlank { url },
                                url = url,
                                isCustom = true
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeSyncAccountPrefs(map: Map<String, SyncAccountPrefs>): String {
        val root = JSONObject()
        map.forEach { (uid, prefs) ->
            val obj = JSONObject()
            obj.put("enrolled", prefs.enrolled)
            obj.put("syncHistory", prefs.syncHistory)
            obj.put("syncMirrors", prefs.syncMirrors)
            root.put(uid, obj)
        }
        return root.toString()
    }

    private fun decodeSyncAccountPrefs(raw: String?): Map<String, SyncAccountPrefs> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val uid = keys.next()
                    val obj = root.optJSONObject(uid) ?: continue
                    put(
                        uid,
                        SyncAccountPrefs(
                            enrolled = obj.optBoolean("enrolled", false),
                            syncHistory = obj.optBoolean("syncHistory", false),
                            syncMirrors = obj.optBoolean("syncMirrors", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }
}
