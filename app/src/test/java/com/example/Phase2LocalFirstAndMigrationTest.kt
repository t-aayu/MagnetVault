package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ExportSelection
import com.example.data.local.LegacyDataMigrator
import com.example.data.local.LocalPrefsStore
import com.example.data.local.VaultJsonExporter
import com.example.data.local.VaultLocalRepository
import com.example.data.local.db.MagnetVaultDatabase
import com.example.data.local.db.ScopeConstants
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.QualityTag
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase2LocalFirstAndMigrationTest {

    private lateinit var context: Context
    private lateinit var db: MagnetVaultDatabase
    private lateinit var prefsStore: LocalPrefsStore

    private val btihA = "0123456789abcdef0123456789abcdef01234567"
    private val btihB = "fedcba9876543210fedcba9876543210fedcba98"
    private val magnetA = "magnet:?xt=urn:btih:$btihA&dn=Ubuntu+24.04"
    private val magnetB = "magnet:?xt=urn:btih:$btihB&dn=Fedora+41"

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        prefsStore = LocalPrefsStore(context)
        prefsStore.clearAllForTesting()
        db = MagnetVaultDatabase.createInMemory(context)
    }

    @After
    fun tearDown() = runBlocking {
        prefsStore.clearAllForTesting()
        db.close()
    }

    @Test
    fun `legacy migration imports valid fixtures deduplicates isolates malformed records and commits atomically`() = runBlocking {
        // Fixture with:
        // 1) two duplicate bookmarks sharing canonical btihA (one without magnet, one with magnet)
        // 2) one unresolved listing without hash/magnet
        // 3) two malformed entries (one missing title/hash/url, one non-object)
        val bookmarksRaw = """
            [
              {
                "id": "bm_dup_old",
                "title": "Ubuntu 24.04 Old",
                "source": "TPB",
                "category": "APPS",
                "sizeLabel": "4.0 GB",
                "sizeBytes": 4294967296,
                "seeders": 40,
                "leechers": 5,
                "uploadDateLabel": "Yesterday",
                "magnetUri": "",
                "infoHash": "$btihA",
                "detailPageUrl": "https://tpb.party/1",
                "qualityTags": ["1080p"],
                "createdEpoch": 1700000100
              },
              {
                "id": "bm_$btihA",
                "title": "Ubuntu 24.04 Complete",
                "source": "X1337",
                "category": "APPS",
                "sizeLabel": "4.0 GB",
                "sizeBytes": 4294967296,
                "seeders": 320,
                "leechers": 12,
                "uploadDateLabel": "Today",
                "magnetUri": "$magnetA",
                "infoHash": "$btihA",
                "detailPageUrl": "https://x1337x.cc/torrent/101/ubuntu/",
                "qualityTags": ["1080p"],
                "createdEpoch": 1700000200
              },
              {
                "id": "bm_X1337_555444",
                "title": "Unresolved Release Pending Magnet",
                "source": "X1337",
                "category": "MOVIES",
                "sizeLabel": "1.5 GB",
                "sizeBytes": 1610612736,
                "seeders": 88,
                "leechers": 4,
                "uploadDateLabel": "Today",
                "magnetUri": "",
                "infoHash": "",
                "detailPageUrl": "https://x1337x.cc/torrent/555444/movie/",
                "qualityTags": ["1080p"],
                "createdEpoch": 1700000300
              },
              {
                "id": "bad_empty",
                "title": "",
                "magnetUri": "",
                "infoHash": "",
                "detailPageUrl": "not_a_url"
              },
              "corrupted_scalar_secret_payload"
            ]
        """.trimIndent()

        val historyRaw = """
            [
              {"id": "q_1", "query": "ubuntu 24", "category": "APPS", "updatedEpoch": 1700000500},
              {"id": "q_dup", "query": "UBUNTU 24", "category": "ALL", "updatedEpoch": 1700000600},
              {"id": "q_bad", "query": "   ", "category": "ALL"}
            ]
        """.trimIndent()

        val sitesRaw = """
            [
              {"id": "s1", "name": "Archive", "url": "https://archive.org"},
              {"id": "s_bad", "name": "BadJS", "url": "javascript:alert(1)"}
            ]
        """.trimIndent()

        val mirrorsRaw = """
            {
              "X1337": {"enabled": false, "mirrors": ["https://1337x.to"], "activeMirrorUrl": "https://1337x.to"},
              "UNKNOWN_PROVIDER": {"enabled": true, "mirrors": ["https://bad.example"]}
            }
        """.trimIndent()

        prefsStore.writeRawLegacySnapshotForTesting(
            bookmarksRaw = bookmarksRaw,
            historyRaw = historyRaw,
            mirrorsRaw = mirrorsRaw,
            customSitesRaw = sitesRaw
        )

        val migrator = LegacyDataMigrator(db = db, prefsStore = prefsStore, clockMs = { 1_750_000_000_000L })
        val status1 = migrator.ensureMigrated()

        assertTrue(status1.legacyImportCompleted)
        assertEquals(2, status1.importedBookmarksCount) // btihA merged + unresolved listing
        assertEquals(1, status1.importedHistoryCount)   // deduplicated case-insensitively
        assertEquals(1, status1.importedSitesCount)     // rejected javascript: scheme
        assertEquals(1, status1.importedMirrorsCount)   // skipped UNKNOWN_PROVIDER
        assertEquals(2, status1.malformedBookmarksCount)
        assertEquals(1, status1.malformedHistoryCount)
        assertEquals(1, status1.malformedSitesCount)
        assertEquals(1, status1.malformedMirrorsCount)
        assertFalse(status1.lastErrorCategory?.contains("corrupted_scalar_secret_payload") == true)

        // Verify deduplicated winner retained resolved magnet & listings from both duplicates
        val importedBookmarks = db.bookmarkDao().getActiveBookmarksWithCollectionsOnce(null)
        assertEquals(2, importedBookmarks.size)
        val ubuntu = importedBookmarks.first { it.bookmark.canonicalKey == "btih:$btihA" }
        assertEquals(magnetA, ubuntu.bookmark.magnetUri)
        val listings = db.bookmarkDao().getProviderListingsForBookmark(ubuntu.bookmark.localId)
        assertEquals(2, listings.size)
        val aliases = db.bookmarkDao().getAliasesForBookmark(ubuntu.bookmark.localId).map { it.aliasKey }
        assertTrue(aliases.contains("legacy:bm_dup_old"))
        assertTrue(aliases.contains("legacy:bm_$btihA"))

        // Idempotent re-run produces identical row counts
        val status2 = migrator.ensureMigrated()
        assertEquals(status1.completedAtEpochMs, status2.completedAtEpochMs)
        assertEquals(2, db.bookmarkDao().getActiveBookmarksWithCollectionsOnce(null).size)

        // Original DataStore legacy snapshot remains untouched (read-only migration)
        val legacySnapshot = prefsStore.readLegacySnapshot()
        assertNotNull(legacySnapshot.bookmarksRaw)
    }

    @Test
    fun `failed migration rolls back transaction cleanly and recovers via retryMigration`() = runBlocking {
        prefsStore.addLocalBookmark(
            docId = "bm_$btihA",
            torrent = TorrentResult(
                id = "bm_$btihA",
                title = "Debian 12 ISO",
                source = IndexerSource.TPB,
                category = TorrentCategory.APPS,
                sizeLabel = "650 MB",
                sizeBytes = 681574400L,
                seeders = 150,
                leechers = 10,
                uploadDateLabel = "Today",
                uploadEpochSeconds = 1700000000L,
                magnetUri = magnetA,
                infoHash = btihA,
                detailPageUrl = "https://tpb.party/1"
            )
        )

        val failingMigrator = LegacyDataMigrator(
            db = db,
            prefsStore = prefsStore,
            beforeTransactionCommitHook = { throw IllegalStateException("Simulated disk error") }
        )
        val failedStatus = failingMigrator.ensureMigrated()
        assertFalse(failedStatus.legacyImportCompleted)
        assertEquals("MIGRATION_TX_FAILED", failedStatus.lastErrorCategory)
        // Atomic rollback means zero half-imported bookmarks
        assertEquals(0, db.bookmarkDao().getActiveDeviceBookmarks().size)

        // Recover with healthy migrator retry
        val recoveringMigrator = LegacyDataMigrator(db = db, prefsStore = prefsStore)
        val recovered = recoveringMigrator.retryMigration()
        assertTrue(recovered.legacyImportCompleted)
        assertEquals(1, recovered.importedBookmarksCount)
        assertEquals(1, db.bookmarkDao().getActiveDeviceBookmarks().size)
    }

    @Test
    fun `unresolved listing upgrades in place preserving localId collections and aliases while deduplicating collisions`() = runBlocking {
        val repo = VaultLocalRepository(db, LegacyDataMigrator(db, prefsStore), clockMs = { 1000L })
        repo.ensureMigrated()

        val unresolved = TorrentResult(
            id = "555444",
            title = "Linux Distro 1080p",
            source = IndexerSource.X1337,
            category = TorrentCategory.APPS,
            sizeLabel = "1.50 GB",
            sizeBytes = 1_610_612_736L,
            seeders = 90,
            leechers = 5,
            uploadDateLabel = "Today",
            uploadEpochSeconds = 1700000000L,
            magnetUri = null,
            infoHash = "",
            detailPageUrl = "https://x1337x.cc/torrent/555444/distro/",
            listingId = "555444"
        )
        val savedOutcome = repo.saveOrUpdateBookmark(unresolved).getOrThrow()
        assertEquals("listing:X1337:555444", savedOutcome.canonicalKey)
        assertFalse(savedOutcome.upgradedFromUnresolved)

        val linuxCol = repo.createCollection("Linux ISOs").getOrThrow()
        repo.setBookmarkCollections(savedOutcome.localId, setOf(linuxCol.collectionId)).getOrThrow()

        // Resolve magnet later and upgrade in place using existingLocalIdHint
        val resolved = unresolved.copy(
            magnetUri = magnetA,
            infoHash = btihA,
            canonicalHashHex = btihA
        )
        val upgradedOutcome = repo.saveOrUpdateBookmark(resolved, existingLocalIdHint = savedOutcome.localId).getOrThrow()
        assertTrue(upgradedOutcome.upgradedFromUnresolved)
        assertEquals(savedOutcome.localId, upgradedOutcome.localId)
        assertEquals("btih:$btihA", upgradedOutcome.canonicalKey)

        // Collection membership preserved & only 1 active row exists
        val active = repo.observeBookmarks(null).first()
        assertEquals(1, active.size)
        assertEquals(savedOutcome.localId, active.first().bookmark.localId)
        assertEquals(listOf("Linux ISOs"), active.first().collections.map { it.name })
        // Unresolved listing key remains in matchKeys so search/detail still recognize it
        val matchKeys = repo.observeActiveMatchKeys(null).first()
        assertTrue(matchKeys.contains("btih:$btihA"))
        assertTrue(matchKeys.contains("listing:X1337:555444"))

        // Remove & Undo restores bookmark AND collection membership
        assertTrue(repo.removeBookmark(savedOutcome.localId).getOrThrow())
        assertEquals(0, repo.observeBookmarks(null).first().size)
        assertTrue(repo.undoRemoveBookmark(savedOutcome.localId).getOrThrow())
        val restored = repo.observeBookmarks(null).first()
        assertEquals(1, restored.size)
        assertEquals(listOf("Linux ISOs"), restored.first().collections.map { it.name })
    }

    @Test
    fun `collections CRUD is local-only rejects duplicate names and deleting collection keeps bookmarks`() = runBlocking {
        val repo = VaultLocalRepository(db, LegacyDataMigrator(db, prefsStore))
        repo.ensureMigrated()

        val c1 = repo.createCollection("Action Movies").getOrThrow()
        val dup = repo.createCollection("  action movies  ")
        assertTrue(dup.isFailure)

        val torrent = TorrentResult(
            id = "res_b",
            title = "Fedora 41 Workstation",
            source = IndexerSource.TPB,
            category = TorrentCategory.APPS,
            sizeLabel = "2.1 GB",
            sizeBytes = 2_254_857_830L,
            seeders = 200,
            leechers = 15,
            uploadDateLabel = "Today",
            uploadEpochSeconds = 1700000000L,
            magnetUri = magnetB,
            infoHash = btihB,
            detailPageUrl = "https://tpb.party/b",
            qualityTags = listOf(QualityTag.FHD_1080P)
        )
        val saved = repo.saveOrUpdateBookmark(torrent).getOrThrow()
        repo.setBookmarkCollections(saved.localId, setOf(c1.collectionId)).getOrThrow()
        repo.renameCollection(c1.collectionId, "4K & Linux").getOrThrow()

        val collections = repo.observeCollections(null).first()
        assertEquals(1, collections.size)
        assertEquals("4K & Linux", collections.first().name)
        assertEquals(1, collections.first().bookmarkCount)

        // Delete collection -> bookmark stays intact
        repo.deleteCollection(c1.collectionId).getOrThrow()
        assertEquals(0, repo.observeCollections(null).first().size)
        val remainingBookmarks = repo.observeBookmarks(null).first()
        assertEquals(1, remainingBookmarks.size)
        assertTrue(remainingBookmarks.first().collections.isEmpty())
    }

    @Test
    fun `vault json exporter v2 honors optional toggles and omits sensitive sync or account metadata`() = runBlocking {
        val repo = VaultLocalRepository(db, LegacyDataMigrator(db, prefsStore))
        repo.ensureMigrated()

        val bm = repo.saveOrUpdateBookmark(
            TorrentResult(
                id = "bm_1",
                title = "Arch Linux 2026",
                source = IndexerSource.TPB,
                category = TorrentCategory.APPS,
                sizeLabel = "1.1 GB",
                sizeBytes = 1_181_116_006L,
                seeders = 500,
                leechers = 20,
                uploadDateLabel = "Today",
                uploadEpochSeconds = 1700000000L,
                magnetUri = magnetA,
                infoHash = btihA,
                detailPageUrl = "https://tpb.party/arch"
            )
        ).getOrThrow()
        val col = repo.createCollection("Distros").getOrThrow()
        repo.setBookmarkCollections(bm.localId, setOf(col.collectionId)).getOrThrow()
        repo.recordSearchQuery("arch linux", TorrentCategory.APPS).getOrThrow()
        repo.addCustomSite("My Indexer", "https://my-indexer.example.org").getOrThrow()
        repo.saveMirrorConfig(MirrorConfig(IndexerSource.TPB, enabled = true, mirrors = listOf("https://apibay.org"), activeMirrorUrl = "https://apibay.org")).getOrThrow()

        val input = repo.buildExportInput(null)
        val counts = VaultJsonExporter.computePreviewCounts(input)
        assertEquals(1, counts.bookmarksCount)
        assertEquals(1, counts.collectionsCount)
        assertEquals(1, counts.historyCount)
        assertEquals(1, counts.customSitesCount)
        assertEquals(IndexerSource.entries.size, counts.mirrorsCount)

        // Default export (all optional sections OFF)
        val minimalJsonStr = VaultJsonExporter.generateJson(input, ExportSelection(), exportedAtEpochMs = 1750000000000L)
        val minimalJson = JSONObject(minimalJsonStr)
        assertEquals(VaultJsonExporter.FORMAT_IDENTIFIER, minimalJson.getString("format"))
        assertEquals(VaultJsonExporter.SCHEMA_VERSION, minimalJson.getInt("schemaVersion"))
        assertEquals(1, minimalJson.getJSONArray("bookmarks").length())
        assertEquals(1, minimalJson.getJSONArray("collections").length())
        assertFalse(minimalJson.has("searchHistory"))
        assertFalse(minimalJson.has("customSites"))
        assertFalse(minimalJson.has("mirrorConfigs"))

        // Full export (optional sections ON)
        val fullJsonStr = VaultJsonExporter.generateJson(
            input,
            ExportSelection(includeHistory = true, includeCustomSites = true, includeMirrors = true),
            exportedAtEpochMs = 1750000000000L
        )
        val fullJson = JSONObject(fullJsonStr)
        assertTrue(fullJson.has("searchHistory"))
        assertTrue(fullJson.has("customSites"))
        assertTrue(fullJson.has("mirrorConfigs"))

        // Stream write succeeds and excludes sync/account/credential fields
        val out = ByteArrayOutputStream()
        VaultJsonExporter.writeJsonToStream(out, fullJsonStr).getOrThrow()
        val written = out.toString("UTF-8")
        assertFalse(written.contains("accountUid"))
        assertFalse(written.contains("lastOpId"))
        assertFalse(written.contains("originScope"))
        assertFalse(written.contains("localRevision"))
        assertFalse(written.contains("deletedAtEpochMs"))
        assertFalse(written.contains("seedr"))
        assertFalse(written.contains("torbox"))
    }
}
