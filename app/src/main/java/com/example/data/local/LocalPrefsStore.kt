package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.BookmarkRecord
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.QualityAndHealthParser
import com.example.model.SearchHistoryRecord
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val Context.magnetVaultDataStore by preferencesDataStore(name = "magnetvault_local_prefs")

class LocalPrefsStore(private val context: Context) {
    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val GUEST_MODE_ENABLED = booleanPreferencesKey("guest_mode_enabled")
        val LOCAL_BOOKMARKS_JSON = stringPreferencesKey("local_bookmarks_json")
        val LOCAL_HISTORY_JSON = stringPreferencesKey("local_history_json")
        val LOCAL_MIRRORS_JSON = stringPreferencesKey("local_mirrors_json")
    }

    val darkThemeFlow: Flow<Boolean> = context.magnetVaultDataStore.data.map { prefs ->
        prefs[Keys.DARK_THEME] ?: true // Dark-first default
    }

    val guestModeEnabledFlow: Flow<Boolean> = context.magnetVaultDataStore.data.map { prefs ->
        prefs[Keys.GUEST_MODE_ENABLED] ?: false
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.magnetVaultDataStore.edit { it[Keys.DARK_THEME] = enabled }
    }

    suspend fun setGuestModeEnabled(enabled: Boolean) {
        context.magnetVaultDataStore.edit { it[Keys.GUEST_MODE_ENABLED] = enabled }
    }

    // --- LOCAL BOOKMARKS (used when running without Sign-In) ---
    val localBookmarksFlow: Flow<List<BookmarkRecord>> = context.magnetVaultDataStore.data.map { prefs ->
        decodeBookmarks(prefs[Keys.LOCAL_BOOKMARKS_JSON])
    }

    suspend fun addLocalBookmark(docId: String, torrent: TorrentResult) {
        context.magnetVaultDataStore.edit { prefs ->
            val current = decodeBookmarks(prefs[Keys.LOCAL_BOOKMARKS_JSON]).toMutableList()
            current.removeAll { it.id == docId }
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
                    createdAt = nowTs,
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
}
