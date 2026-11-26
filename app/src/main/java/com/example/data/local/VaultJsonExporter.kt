package com.example.data.local

import com.example.data.local.db.BookmarkWithCollections
import com.example.data.local.db.CollectionWithCount
import com.example.data.local.db.CustomSiteEntity
import com.example.data.local.db.SearchHistoryEntity
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.nio.charset.StandardCharsets

data class ExportSelection(
    val includeHistory: Boolean = false,
    val includeCustomSites: Boolean = false,
    val includeMirrors: Boolean = false
)

data class VaultExportInput(
    val bookmarks: List<BookmarkWithCollections>,
    val collections: List<CollectionWithCount>,
    val history: List<SearchHistoryEntity>,
    val customSites: List<CustomSiteEntity>,
    val mirrors: Map<IndexerSource, MirrorConfig>
)

data class ExportPreviewCounts(
    val bookmarksCount: Int,
    val collectionsCount: Int,
    val historyCount: Int,
    val customSitesCount: Int,
    val mirrorsCount: Int
)

object VaultJsonExporter {
    const val SCHEMA_VERSION = 2
    const val FORMAT_IDENTIFIER = "magnetvault.vault.export"

    fun computePreviewCounts(input: VaultExportInput): ExportPreviewCounts = ExportPreviewCounts(
        bookmarksCount = input.bookmarks.size,
        collectionsCount = input.collections.size,
        historyCount = input.history.size,
        customSitesCount = input.customSites.size,
        mirrorsCount = input.mirrors.size
    )

    /**
     * Serializes the Vault into a versioned portable JSON document.
     * Deliberately excludes account identifiers, Firebase tokens, sync outbox/revision metadata,
     * Seedr/TorBox credentials, and WebView cookies.
     */
    fun generateJson(
        input: VaultExportInput,
        selection: ExportSelection,
        exportedAtEpochMs: Long = System.currentTimeMillis()
    ): String {
        val root = JSONObject()
        root.put("format", FORMAT_IDENTIFIER)
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("exportedAtEpochMs", exportedAtEpochMs)

        val includedSections = mutableListOf("bookmarks", "collections")
        if (selection.includeHistory) includedSections += "history"
        if (selection.includeCustomSites) includedSections += "customSites"
        if (selection.includeMirrors) includedSections += "mirrors"
        root.put("includedSections", JSONArray(includedSections))

        val bookmarkIdsByCollection = LinkedHashMap<String, MutableList<String>>()
        val bookmarksArr = JSONArray()
        input.bookmarks.forEach { item ->
            val b = item.bookmark
            val collIds = item.collections.map { it.collectionId }
            collIds.forEach { cid ->
                bookmarkIdsByCollection.getOrPut(cid) { mutableListOf() }.add(b.localId)
            }
            val bObj = JSONObject()
            bObj.put("localId", b.localId)
            bObj.put("canonicalKey", b.canonicalKey)
            bObj.put("title", b.title)
            bObj.put("source", b.source)
            bObj.put("category", b.category)
            bObj.put("sizeLabel", b.sizeLabel)
            bObj.put("sizeBytes", b.sizeBytes)
            bObj.put("seeders", b.seeders)
            bObj.put("leechers", b.leechers)
            bObj.put("uploadDateLabel", b.uploadDateLabel)
            bObj.put("magnetUri", b.magnetUri)
            bObj.put("infoHash", b.canonicalHashHex.ifBlank { b.infoHash })
            bObj.put("detailPageUrl", b.detailPageUrl)
            bObj.put("qualityTags", JSONArray(b.qualityTagNames()))
            bObj.put("collectionIds", JSONArray(collIds))
            bObj.put("createdAtEpochMs", b.createdAtEpochMs)
            bObj.put("updatedAtEpochMs", b.updatedAtEpochMs)
            bookmarksArr.put(bObj)
        }
        root.put("bookmarks", bookmarksArr)

        val collectionsArr = JSONArray()
        input.collections.forEach { col ->
            val cObj = JSONObject()
            cObj.put("collectionId", col.collectionId)
            cObj.put("name", col.name)
            cObj.put("createdAtEpochMs", col.createdAtEpochMs)
            cObj.put("updatedAtEpochMs", col.updatedAtEpochMs)
            cObj.put("bookmarkIds", JSONArray(bookmarkIdsByCollection[col.collectionId].orEmpty()))
            collectionsArr.put(cObj)
        }
        root.put("collections", collectionsArr)

        if (selection.includeHistory) {
            val histArr = JSONArray()
            input.history.forEach { h ->
                val hObj = JSONObject()
                hObj.put("entryId", h.entryId)
                hObj.put("query", h.query)
                hObj.put("category", h.category)
                hObj.put("createdAtEpochMs", h.createdAtEpochMs)
                hObj.put("updatedAtEpochMs", h.updatedAtEpochMs)
                histArr.put(hObj)
            }
            root.put("searchHistory", histArr)
        }

        if (selection.includeCustomSites) {
            val sitesArr = JSONArray()
            input.customSites.forEach { s ->
                val sObj = JSONObject()
                sObj.put("siteId", s.siteId)
                sObj.put("name", s.name)
                sObj.put("url", s.url)
                sObj.put("createdAtEpochMs", s.createdAtEpochMs)
                sitesArr.put(sObj)
            }
            root.put("customSites", sitesArr)
        }

        if (selection.includeMirrors) {
            val mirrorsObj = JSONObject()
            IndexerSource.entries.forEach { source ->
                val cfg = input.mirrors[source] ?: MirrorConfig(provider = source)
                val mObj = JSONObject()
                mObj.put("enabled", cfg.enabled)
                mObj.put("activeMirrorUrl", cfg.activeMirrorUrl)
                mObj.put("mirrors", JSONArray(cfg.mirrors))
                mirrorsObj.put(source.name, mObj)
            }
            root.put("mirrorConfigs", mirrorsObj)
        }

        return root.toString(2)
    }

    fun writeJsonToStream(outputStream: OutputStream, jsonContent: String): Result<Unit> = runCatching {
        outputStream.use { stream ->
            stream.write(jsonContent.toByteArray(StandardCharsets.UTF_8))
            stream.flush()
        }
    }
}
