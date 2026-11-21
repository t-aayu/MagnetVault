package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties

@Keep
@IgnoreExtraProperties
data class BookmarkRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val source: String = IndexerSource.X1337.name,
    val category: String = TorrentCategory.ALL.name,
    val sizeLabel: String = "",
    val sizeBytes: Long = 0L,
    val seeders: Long = 0L,
    val leechers: Long = 0L,
    val uploadDateLabel: String = "",
    val magnetUri: String = "",
    val infoHash: String = "",
    val detailPageUrl: String = "",
    val qualityTags: List<String> = emptyList(),
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun toTorrentResult(): TorrentResult {
        val parsedTags = qualityTags.mapNotNull { QualityTag.fromLabelOrName(it) }
        return TorrentResult(
            id = id,
            title = title,
            source = IndexerSource.fromNameSafe(source),
            category = TorrentCategory.fromNameSafe(category),
            sizeLabel = sizeLabel.ifBlank { QualityAndHealthParser.formatBytes(sizeBytes) },
            sizeBytes = sizeBytes,
            seeders = seeders.toInt().coerceAtLeast(0),
            leechers = leechers.toInt().coerceAtLeast(0),
            uploadDateLabel = uploadDateLabel,
            uploadEpochSeconds = createdAt?.seconds ?: 0L,
            magnetUri = magnetUri.ifBlank { null },
            infoHash = infoHash,
            detailPageUrl = detailPageUrl,
            qualityTags = if (parsedTags.isNotEmpty()) parsedTags else QualityAndHealthParser.extractQualityTags(title)
        )
    }

    companion object {
        fun toCreateMap(userId: String, torrent: TorrentResult): Map<String, Any> = mapOf(
            "userId" to userId.take(128),
            "title" to torrent.title.trim().ifBlank { "Untitled Torrent" }.take(500),
            "source" to torrent.source.name,
            "category" to torrent.category.name,
            "sizeLabel" to torrent.sizeLabel.take(64),
            "sizeBytes" to torrent.sizeBytes.coerceAtLeast(0L),
            "seeders" to torrent.seeders.toLong().coerceAtLeast(0L),
            "leechers" to torrent.leechers.toLong().coerceAtLeast(0L),
            "uploadDateLabel" to torrent.uploadDateLabel.take(64),
            "magnetUri" to (torrent.magnetUri ?: "").take(4000),
            "infoHash" to torrent.infoHash.take(128),
            "detailPageUrl" to torrent.detailPageUrl.take(1000),
            "qualityTags" to torrent.qualityTags.map { it.label }.take(10),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }
}

@Keep
@IgnoreExtraProperties
data class SearchHistoryRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val query: String = "",
    val category: String = TorrentCategory.ALL.name,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    companion object {
        fun toCreateMap(userId: String, query: String, category: TorrentCategory): Map<String, Any> = mapOf(
            "userId" to userId.take(128),
            "query" to query.trim().take(200),
            "category" to category.name,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }
}

@Keep
@IgnoreExtraProperties
data class MirrorConfigRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val provider: String = IndexerSource.X1337.name,
    val enabled: Boolean = true,
    val mirrors: List<String> = emptyList(),
    val activeMirrorUrl: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun toMirrorConfig(): MirrorConfig {
        val src = IndexerSource.fromNameSafe(provider)
        val sanitizedMirrors = mirrors.filter { it.startsWith("http") }.ifEmpty { src.defaultMirrors }
        val active = activeMirrorUrl.takeIf { it in sanitizedMirrors } ?: sanitizedMirrors.first()
        return MirrorConfig(
            provider = src,
            enabled = enabled,
            mirrors = sanitizedMirrors,
            activeMirrorUrl = active
        )
    }

    companion object {
        fun toCreateMap(userId: String, config: MirrorConfig): Map<String, Any> {
            val cleanMirrors = config.mirrors
                .map { it.trim().trimEnd('/') }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
                .take(12)
                .ifEmpty { config.provider.defaultMirrors }
            val active = config.activeMirrorUrl.trim().trimEnd('/')
                .takeIf { it in cleanMirrors } ?: cleanMirrors.first()
            return mapOf(
                "userId" to userId.take(128),
                "provider" to config.provider.name,
                "enabled" to config.enabled,
                "mirrors" to cleanMirrors,
                "activeMirrorUrl" to active.take(500),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        }

        fun toUpdateMap(config: MirrorConfig): Map<String, Any> {
            val cleanMirrors = config.mirrors
                .map { it.trim().trimEnd('/') }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
                .take(12)
                .ifEmpty { config.provider.defaultMirrors }
            val active = config.activeMirrorUrl.trim().trimEnd('/')
                .takeIf { it in cleanMirrors } ?: cleanMirrors.first()
            return mapOf(
                "enabled" to config.enabled,
                "mirrors" to cleanMirrors,
                "activeMirrorUrl" to active.take(500),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        }
    }
}
