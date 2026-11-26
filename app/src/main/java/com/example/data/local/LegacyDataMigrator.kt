package com.example.data.local

import androidx.room.withTransaction
import com.example.data.local.db.BookmarkEntity
import com.example.data.local.db.CustomSiteEntity
import com.example.data.local.db.IdentityAliasEntity
import com.example.data.local.db.MagnetVaultDatabase
import com.example.data.local.db.MigrationStatusEntity
import com.example.data.local.db.MirrorConfigEntity
import com.example.data.local.db.ProviderListingEntity
import com.example.data.local.db.ScopeConstants
import com.example.data.local.db.SearchHistoryEntity
import com.example.domain.magnet.CanonicalIdentityKey
import com.example.domain.magnet.MagnetParser
import com.example.model.IndexerSource
import com.example.model.QualityAndHealthParser
import com.example.model.TorrentCategory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID

/**
 * Performs an atomic, idempotent one-time import of legacy DataStore payloads into Room.
 *
 * Guarantees:
 * - Legacy DataStore keys are read-only during migration (original payloads preserved on device).
 * - Import and authoritative completion marker commit in a single Room transaction.
 * - Malformed records are isolated and counted by category without logging or surfacing raw contents.
 * - Duplicate records merge deterministically and never duplicate on retry or process restart.
 */
class LegacyDataMigrator(
    private val db: MagnetVaultDatabase,
    private val prefsStore: LocalPrefsStore,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val beforeTransactionCommitHook: (() -> Unit)? = null
) {
    private val mutex = Mutex()

    suspend fun ensureMigrated(): MigrationStatusEntity {
        val existing = db.migrationStatusDao().getStatus()
        if (existing?.legacyImportCompleted == true) {
            return existing
        }
        return mutex.withLock {
            val recheck = db.migrationStatusDao().getStatus()
            if (recheck?.legacyImportCompleted == true) {
                return@withLock recheck
            }
            runImportInternal()
        }
    }

    suspend fun retryMigration(): MigrationStatusEntity = mutex.withLock {
        val current = db.migrationStatusDao().getStatus()
        if (current?.legacyImportCompleted == true) {
            return@withLock current
        }
        runImportInternal()
    }

    private suspend fun runImportInternal(): MigrationStatusEntity {
        val nowMs = clockMs()
        val snapshot = prefsStore.readLegacySnapshot()
        val parsed = parseSnapshot(snapshot, nowMs)

        return try {
            var committedStatus: MigrationStatusEntity? = null
            db.withTransaction {
                val insideCheck = db.migrationStatusDao().getStatus()
                if (insideCheck?.legacyImportCompleted == true) {
                    committedStatus = insideCheck
                    return@withTransaction
                }

                parsed.bookmarks.forEach { item ->
                    db.bookmarkDao().upsertBookmark(item.entity)
                    item.aliases.forEach { aliasKey ->
                        db.bookmarkDao().upsertIdentityAlias(
                            IdentityAliasEntity(
                                originScope = ScopeConstants.SCOPE_DEVICE,
                                aliasKey = aliasKey,
                                bookmarkLocalId = item.entity.localId,
                                createdAtEpochMs = nowMs
                            )
                        )
                    }
                    item.listings.forEach { listing ->
                        db.bookmarkDao().upsertProviderListing(listing)
                    }
                }

                parsed.history.forEach { entry ->
                    db.searchHistoryDao().upsertHistory(entry)
                }

                parsed.customSites.forEach { site ->
                    db.customSiteDao().upsertCustomSite(site)
                }

                parsed.mirrors.forEach { mirror ->
                    db.mirrorConfigDao().upsertMirrorConfig(mirror)
                }

                beforeTransactionCommitHook?.invoke()

                val status = MigrationStatusEntity(
                    id = 1,
                    legacyImportCompleted = true,
                    legacyImportVersion = 1,
                    completedAtEpochMs = nowMs,
                    importedBookmarksCount = parsed.bookmarks.size,
                    importedHistoryCount = parsed.history.size,
                    importedSitesCount = parsed.customSites.size,
                    importedMirrorsCount = parsed.mirrors.size,
                    malformedBookmarksCount = parsed.malformedBookmarksCount,
                    malformedHistoryCount = parsed.malformedHistoryCount,
                    malformedSitesCount = parsed.malformedSitesCount,
                    malformedMirrorsCount = parsed.malformedMirrorsCount,
                    lastErrorCategory = null
                )
                db.migrationStatusDao().upsertStatus(status)
                committedStatus = status
            }
            committedStatus ?: db.migrationStatusDao().getStatus() ?: MigrationStatusEntity(legacyImportCompleted = true)
        } catch (t: Throwable) {
            val category = classifyMigrationFailure(t)
            val failedStatus = MigrationStatusEntity(
                id = 1,
                legacyImportCompleted = false,
                legacyImportVersion = 1,
                completedAtEpochMs = null,
                importedBookmarksCount = 0,
                importedHistoryCount = 0,
                importedSitesCount = 0,
                importedMirrorsCount = 0,
                malformedBookmarksCount = parsed.malformedBookmarksCount,
                malformedHistoryCount = parsed.malformedHistoryCount,
                malformedSitesCount = parsed.malformedSitesCount,
                malformedMirrorsCount = parsed.malformedMirrorsCount,
                lastErrorCategory = category
            )
            runCatching { db.migrationStatusDao().upsertStatus(failedStatus) }
            failedStatus
        }
    }

    private fun classifyMigrationFailure(t: Throwable): String {
        val msg = t.javaClass.simpleName.lowercase(Locale.US)
        return when {
            msg.contains("sqlite") || msg.contains("database") -> "DATABASE_WRITE_ERROR"
            t is java.io.IOException || msg == "ioexception" || msg.endsWith("ioerror") -> "STORAGE_IO_ERROR"
            else -> "MIGRATION_TX_FAILED"
        }
    }

    private data class ParsedBookmark(
        val entity: BookmarkEntity,
        val aliases: Set<String>,
        val listings: List<ProviderListingEntity>
    )

    private data class ParsedLegacySnapshot(
        val bookmarks: List<ParsedBookmark>,
        val history: List<SearchHistoryEntity>,
        val customSites: List<CustomSiteEntity>,
        val mirrors: List<MirrorConfigEntity>,
        val malformedBookmarksCount: Int,
        val malformedHistoryCount: Int,
        val malformedSitesCount: Int,
        val malformedMirrorsCount: Int
    )

    private fun parseSnapshot(snapshot: LegacyDataStoreSnapshot, nowMs: Long): ParsedLegacySnapshot {
        val (bookmarks, malformedBookmarks) = parseBookmarks(snapshot.bookmarksRaw, nowMs)
        val (history, malformedHistory) = parseHistory(snapshot.historyRaw, nowMs)
        val (sites, malformedSites) = parseCustomSites(snapshot.customSitesRaw, nowMs)
        val (mirrors, malformedMirrors) = parseMirrors(snapshot.mirrorsRaw, nowMs)
        return ParsedLegacySnapshot(
            bookmarks = bookmarks,
            history = history,
            customSites = sites,
            mirrors = mirrors,
            malformedBookmarksCount = malformedBookmarks,
            malformedHistoryCount = malformedHistory,
            malformedSitesCount = malformedSites,
            malformedMirrorsCount = malformedMirrors
        )
    }

    private fun parseBookmarks(raw: String?, nowMs: Long): Pair<List<ParsedBookmark>, Int> {
        if (raw.isNullOrBlank()) return emptyList<ParsedBookmark>() to 0
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList<ParsedBookmark>() to 1

        var malformedCount = 0
        val mergedByKey = LinkedHashMap<String, ParsedBookmark>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            if (obj == null) {
                malformedCount++
                continue
            }
            val title = obj.optString("title", "").trim()
            val magnetUri = obj.optString("magnetUri", "").trim()
            val infoHash = obj.optString("infoHash", "").trim()
            val detailPageUrl = obj.optString("detailPageUrl", "").trim()
            val legacyId = obj.optString("id", "").trim()
            val source = IndexerSource.fromNameSafe(obj.optString("source", IndexerSource.X1337.name))
            val category = TorrentCategory.fromNameSafe(obj.optString("category", TorrentCategory.ALL.name))

            val hasUsableUrl = detailPageUrl.startsWith("http://", true) || detailPageUrl.startsWith("https://", true)
            val verifiedFromMagnet = MagnetParser.extractVerifiedIdentities(magnetUri).firstOrNull()
            val verifiedFromField = MagnetParser.normalizeHash(infoHash)
            val verifiedHash = verifiedFromMagnet ?: verifiedFromField

            val isValid = title.isNotBlank() || verifiedHash != null || hasUsableUrl
            if (!isValid) {
                malformedCount++
                continue
            }

            val safeTitle = title.ifBlank { "Untitled Torrent" }.take(500)
            val listingSeed = legacyId
                .removePrefix("bm_${source.name}_")
                .removePrefix("bm_")
                .ifBlank {
                    detailPageUrl.ifBlank {
                        UUID.nameUUIDFromBytes("$safeTitle|${source.name}".toByteArray()).toString()
                    }
                }
            val canonicalKey = verifiedHash?.storageKey
                ?: CanonicalIdentityKey.UnresolvedListing(source, listingSeed).storageKey

            val createdEpochSec = obj.optLong("createdEpoch", 0L)
            val createdMs = if (createdEpochSec > 0L) createdEpochSec * 1000L else nowMs

            val tagsArr = obj.optJSONArray("qualityTags")
            val tags = buildList {
                if (tagsArr != null) {
                    for (j in 0 until tagsArr.length()) {
                        val t = tagsArr.optString(j).trim()
                        if (t.isNotEmpty()) add(t)
                    }
                }
            }

            val sizeBytes = obj.optLong("sizeBytes", 0L).coerceAtLeast(0L)
            val sizeLabel = obj.optString("sizeLabel", "").trim()
                .ifBlank { QualityAndHealthParser.formatKnownBytes(sizeBytes.takeIf { it > 0L }) }
            val seeders = obj.optLong("seeders", 0L).coerceAtLeast(0L).toInt()
            val leechers = obj.optLong("leechers", 0L).coerceAtLeast(0L).toInt()
            val uploadDateLabel = obj.optString("uploadDateLabel", "").trim().ifBlank { "Unknown" }

            val candidateLocalId = deterministicDeviceBookmarkId(canonicalKey)
            val aliases = buildSet {
                add(canonicalKey)
                add(CanonicalIdentityKey.UnresolvedListing(source, listingSeed).storageKey)
                if (legacyId.isNotBlank()) {
                    add("legacy:$legacyId")
                }
            }
            val candidateListing = ProviderListingEntity(
                bookmarkLocalId = candidateLocalId,
                source = source.name,
                listingId = listingSeed,
                detailPageUrl = detailPageUrl.take(1000),
                sizeBytes = sizeBytes,
                seeders = seeders,
                leechers = leechers,
                uploadDateLabel = uploadDateLabel.take(64),
                updatedAtEpochMs = createdMs
            )

            val candidateEntity = BookmarkEntity(
                localId = candidateLocalId,
                originScope = ScopeConstants.SCOPE_DEVICE,
                canonicalKey = canonicalKey,
                hashFamily = verifiedHash?.family?.name,
                canonicalHashHex = verifiedHash?.canonicalHex ?: infoHash.take(128),
                legacyDocId = legacyId.ifBlank {
                    QualityAndHealthParser.sanitizeDocumentId(
                        "bm_${verifiedHash?.canonicalHex ?: "${source.name}_$listingSeed"}"
                    )
                },
                title = safeTitle,
                source = source.name,
                category = category.name,
                sizeLabel = sizeLabel.take(64),
                sizeBytes = sizeBytes,
                knownSizeBytes = sizeBytes.takeIf { it > 0L },
                seeders = seeders,
                knownSeeders = seeders.takeIf { obj.has("seeders") },
                leechers = leechers,
                knownLeechers = leechers.takeIf { obj.has("leechers") },
                uploadDateLabel = uploadDateLabel.take(64),
                magnetUri = magnetUri.take(4000),
                infoHash = verifiedHash?.canonicalHex ?: infoHash.take(128),
                detailPageUrl = detailPageUrl.take(1000),
                listingId = listingSeed,
                qualityTagsJson = JSONArray(tags.take(10)).toString(),
                createdAtEpochMs = createdMs,
                updatedAtEpochMs = createdMs,
                localRevision = 1L,
                isDeleted = false,
                deletedAtEpochMs = null
            )

            val existing = mergedByKey[canonicalKey]
            if (existing == null) {
                mergedByKey[canonicalKey] = ParsedBookmark(
                    entity = candidateEntity,
                    aliases = aliases,
                    listings = listOf(candidateListing)
                )
            } else {
                val existingHasMagnet = existing.entity.magnetUri.isNotBlank()
                val candidateHasMagnet = candidateEntity.magnetUri.isNotBlank()
                val winner = when {
                    !existingHasMagnet && candidateHasMagnet -> candidateEntity
                    existingHasMagnet && !candidateHasMagnet -> existing.entity
                    candidateEntity.seeders > existing.entity.seeders -> candidateEntity
                    else -> existing.entity
                }.copy(
                    localId = existing.entity.localId,
                    createdAtEpochMs = minOf(existing.entity.createdAtEpochMs, candidateEntity.createdAtEpochMs),
                    updatedAtEpochMs = maxOf(existing.entity.updatedAtEpochMs, candidateEntity.updatedAtEpochMs),
                    magnetUri = existing.entity.magnetUri.ifBlank { candidateEntity.magnetUri },
                    detailPageUrl = existing.entity.detailPageUrl.ifBlank { candidateEntity.detailPageUrl }
                )
                val combinedListings = (existing.listings + candidateListing.copy(bookmarkLocalId = existing.entity.localId))
                    .distinctBy { "${it.source}:${it.listingId}" }
                mergedByKey[canonicalKey] = ParsedBookmark(
                    entity = winner,
                    aliases = existing.aliases + aliases,
                    listings = combinedListings
                )
            }
        }
        return mergedByKey.values.toList() to malformedCount
    }

    private fun parseHistory(raw: String?, nowMs: Long): Pair<List<SearchHistoryEntity>, Int> {
        if (raw.isNullOrBlank()) return emptyList<SearchHistoryEntity>() to 0
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList<SearchHistoryEntity>() to 1

        var malformedCount = 0
        val merged = LinkedHashMap<String, SearchHistoryEntity>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            if (obj == null) {
                malformedCount++
                continue
            }
            val query = obj.optString("query", "").trim().take(200)
            if (query.isBlank()) {
                malformedCount++
                continue
            }
            val normalizedQuery = query.lowercase(Locale.US)
            val category = TorrentCategory.fromNameSafe(obj.optString("category", TorrentCategory.ALL.name))
            val updatedEpochSec = obj.optLong("updatedEpoch", 0L)
            val updatedMs = if (updatedEpochSec > 0L) updatedEpochSec * 1000L else (nowMs - i.toLong())
            val legacyId = obj.optString("id", "").trim().ifBlank {
                QualityAndHealthParser.sanitizeDocumentId("q_${normalizedQuery.hashCode().toUInt()}")
            }
            val entryId = UUID.nameUUIDFromBytes("device:hist:$normalizedQuery".toByteArray()).toString()
            val candidate = SearchHistoryEntity(
                entryId = entryId,
                originScope = ScopeConstants.SCOPE_DEVICE,
                cloudDocId = legacyId,
                query = query,
                normalizedQuery = normalizedQuery,
                category = category.name,
                createdAtEpochMs = updatedMs,
                updatedAtEpochMs = updatedMs,
                localRevision = 1L,
                isDeleted = false,
                deletedAtEpochMs = null
            )

            val prev = merged[normalizedQuery]
            if (prev == null || candidate.updatedAtEpochMs > prev.updatedAtEpochMs) {
                merged[normalizedQuery] = if (prev != null) {
                    candidate.copy(
                        entryId = prev.entryId,
                        createdAtEpochMs = minOf(prev.createdAtEpochMs, candidate.createdAtEpochMs)
                    )
                } else {
                    candidate
                }
            }
        }
        return merged.values.toList() to malformedCount
    }

    private fun parseCustomSites(raw: String?, nowMs: Long): Pair<List<CustomSiteEntity>, Int> {
        if (raw.isNullOrBlank()) return emptyList<CustomSiteEntity>() to 0
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList<CustomSiteEntity>() to 1

        var malformedCount = 0
        val merged = LinkedHashMap<String, CustomSiteEntity>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            if (obj == null) {
                malformedCount++
                continue
            }
            val url = obj.optString("url", "").trim()
            if (!isValidHttpUrl(url)) {
                malformedCount++
                continue
            }
            val normalizedUrl = url.lowercase(Locale.US).trimEnd('/')
            val name = obj.optString("name", "").trim().take(40).ifBlank {
                runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { url.take(30) }
            }
            val siteId = obj.optString("id", "").trim().ifBlank {
                QualityAndHealthParser.sanitizeDocumentId("site_${normalizedUrl.hashCode().toUInt()}")
            }
            val createdMs = nowMs - i.toLong()
            val candidate = CustomSiteEntity(
                siteId = siteId,
                name = name,
                url = url,
                normalizedUrl = normalizedUrl,
                createdAtEpochMs = createdMs,
                updatedAtEpochMs = createdMs
            )
            if (!merged.containsKey(normalizedUrl)) {
                merged[normalizedUrl] = candidate
            }
        }
        return merged.values.toList() to malformedCount
    }

    private fun parseMirrors(raw: String?, nowMs: Long): Pair<List<MirrorConfigEntity>, Int> {
        if (raw.isNullOrBlank()) return emptyList<MirrorConfigEntity>() to 0
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList<MirrorConfigEntity>() to 1

        var malformedCount = 0
        val results = mutableListOf<MirrorConfigEntity>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val source = IndexerSource.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
            val obj = root.optJSONObject(key)
            if (source == null || obj == null) {
                malformedCount++
                continue
            }
            val arr = obj.optJSONArray("mirrors")
            val parsedMirrors = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val u = arr.optString(i).trim().trimEnd('/')
                        if (isValidHttpUrl(u)) add(u)
                    }
                }
            }.distinct().take(12).ifEmpty { source.defaultMirrors }

            val rawActive = obj.optString("activeMirrorUrl", "").trim().trimEnd('/')
            val active = rawActive.takeIf { it in parsedMirrors } ?: parsedMirrors.first()
            results += MirrorConfigEntity(
                provider = source.name,
                enabled = obj.optBoolean("enabled", true),
                mirrorsJson = JSONArray(parsedMirrors).toString(),
                activeMirrorUrl = active,
                updatedAtEpochMs = nowMs,
                localRevision = 1L
            )
        }
        return results to malformedCount
    }

    private fun isValidHttpUrl(raw: String): Boolean {
        val lower = raw.lowercase(Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        return host.contains('.') && uri.userInfo.isNullOrEmpty()
    }

    companion object {
        fun deterministicDeviceBookmarkId(canonicalKey: String): String =
            UUID.nameUUIDFromBytes("device:$canonicalKey".toByteArray()).toString()
    }
}
