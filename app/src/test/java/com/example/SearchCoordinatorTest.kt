package com.example

import com.example.data.indexers.ProviderAdapter
import com.example.data.indexers.SearchCoordinator
import com.example.data.network.HttpResponseData
import com.example.data.network.HttpStatusException
import com.example.data.network.HttpTransport
import com.example.data.network.OkHttpTransport
import com.example.data.network.RateLimitedException
import com.example.data.network.SearchNetworkLimits
import com.example.domain.magnet.CanonicalIdentityKey
import com.example.domain.magnet.TorrentHashFamily
import com.example.model.IndexerSource
import com.example.model.MagnetResolutionState
import com.example.model.MirrorConfig
import com.example.model.ProviderListing
import com.example.model.ProviderTerminalState
import com.example.model.SortOption
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchCoordinatorTest {

    // Same 20-byte BTIH encoded as 40-char hex and RFC 4648 Base32
    private val sharedHex = "0123456789abcdef0123456789abcdef01234567"
    private val sharedBase32 = "AERUKZ4JVPG66AJDIVTYTK6N54ASGRLH"
    private val btmhV2 = "1220" + "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

    private class FakeTransport(
        private val onGet: suspend (url: String, maxBytes: Long) -> String = { _, _ -> "" },
        private val onPost: suspend (url: String, body: String, maxBytes: Long) -> String = { _, _, _ -> "" }
    ) : HttpTransport {
        override suspend fun get(url: String, headers: Map<String, String>, maxResponseBytes: Long): String =
            onGet(url, maxResponseBytes)

        override suspend fun postJson(
            url: String,
            jsonBody: String,
            headers: Map<String, String>,
            maxResponseBytes: Long
        ): String = onPost(url, jsonBody, maxResponseBytes)

        override suspend fun probe(url: String, postJsonBody: String?, maxResponseBytes: Long): HttpResponseData =
            HttpResponseData(200, "ok")
    }

    private fun enabledMirrors(vararg enabledSources: IndexerSource): Map<IndexerSource, MirrorConfig> =
        IndexerSource.entries.associateWith { src ->
            MirrorConfig(
                provider = src,
                enabled = src in enabledSources,
                mirrors = listOf("https://mock.${src.name.lowercase()}.test"),
                activeMirrorUrl = "https://mock.${src.name.lowercase()}.test"
            )
        }

    @Test
    fun `progressive delivery publishes fast provider results before slow provider finishes`() = runBlocking {
        val slowGate = CompletableDeferred<Unit>()
        val transport = FakeTransport(
            onGet = { url, _ ->
                when {
                    url.contains("tpb") -> """
                        [{"id":"1","name":"Fast TPB Release 1080p","info_hash":"$sharedHex","seeders":"120","leechers":"15","size":"2147483648","added":"1700000000","category":"201"}]
                    """.trimIndent()
                    url.contains("yts") -> {
                        slowGate.await()
                        """{"status":"ok","data":{"movie_count":1,"movies":[{"id":2,"title_long":"Slow YTS Movie (2025)","url":"https://yts.mx/m/2","torrents":[{"hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","quality":"1080p","type":"web","seeds":45,"peers":5,"size":"1.50 GB","size_bytes":1610612736,"date_uploaded_unix":1700000100}]}]}}"""
                    }
                    else -> ""
                }
            }
        )

        val coordinator = SearchCoordinator(
            transport = transport,
            limits = SearchNetworkLimits(wholeSearchDeadlineMs = 5_000L, defaultAttemptTimeoutMs = 4_000L),
            ioDispatcher = Dispatchers.Default
        )

        val sessionId = coordinator.startSearch(
            query = "ubuntu",
            category = TorrentCategory.ALL,
            sortOption = SortOption.SEEDERS,
            mirrorConfigs = enabledMirrors(IndexerSource.TPB, IndexerSource.YTS)
        )

        // Wait until TPB completes while YTS is still loading
        val progressiveSnap = withTimeout(3_000L) {
            coordinator.snapshots.first { snap ->
                snap.sessionId == sessionId &&
                    snap.groups.any { it.primarySource == IndexerSource.TPB } &&
                    snap.isSearching
            }
        }
        assertEquals(1, progressiveSnap.groups.size)
        assertEquals(ProviderTerminalState.MATCHES, progressiveSnap.statuses.first { it.source == IndexerSource.TPB }.terminalState)
        assertEquals(ProviderTerminalState.LOADING, progressiveSnap.statuses.first { it.source == IndexerSource.YTS }.terminalState)

        // Unblock YTS and confirm completion
        slowGate.complete(Unit)
        val completedSnap = withTimeout(3_000L) {
            coordinator.snapshots.first { snap ->
                snap.sessionId == sessionId && !snap.isSearching
            }
        }
        assertEquals(2, completedSnap.groups.size)
        assertEquals(ProviderTerminalState.MATCHES, completedSnap.statuses.first { it.source == IndexerSource.YTS }.terminalState)
    }

    @Test
    fun `partial failures retain results classify terminal states and single-source retry preserves other results`() = runBlocking {
        val ytsAttempts = AtomicInteger(0)
        val allowYtsRetry = AtomicBoolean(false)
        val ytsRetryGate = CompletableDeferred<Unit>()
        val transport = FakeTransport(
            onGet = { url, _ ->
                when {
                    url.contains("tpb") -> """
                        [{"id":"1","name":"Healthy TPB Release","info_hash":"$sharedHex","seeders":"250","leechers":"20","size":"1073741824","added":"1700000000","category":"201"}]
                    """.trimIndent()
                    url.contains("yts") -> {
                        ytsAttempts.incrementAndGet()
                        if (!allowYtsRetry.get()) {
                            throw RateLimitedException(host = "mock.yts.test", retryAfterMs = 10_000L)
                        } else {
                            ytsRetryGate.await()
                            """{"status":"ok","data":{"movie_count":1,"movies":[{"id":9,"title_long":"Recovered YTS Movie","url":"https://yts.mx/m/9","torrents":[{"hash":"cccccccccccccccccccccccccccccccccccccccc","quality":"1080p","type":"web","seeds":90,"peers":12,"size":"1.80 GB","size_bytes":1932735283,"date_uploaded_unix":1700000200}]}]}}"""
                        }
                    }
                    url.contains("nyaa") -> "<rss><broken-xml" // triggers PARSER_ERROR
                    url.contains("limetorrents") -> throw HttpStatusException(503, "mock.limetorrents.test")
                    url.contains("eztv") -> {
                        delay(2_000L) // exceeds 350ms attempt timeout -> TIMEOUT
                        "{}"
                    }
                    else -> ""
                }
            }
        )

        val coordinator = SearchCoordinator(
            transport = transport,
            limits = SearchNetworkLimits(
                wholeSearchDeadlineMs = 3_000L,
                defaultAttemptTimeoutMs = 350L,
                fastFailoverAttemptTimeoutMs = 350L,
                maxRateLimitDelayMs = 200L
            ),
            ioDispatcher = Dispatchers.Default
        )

        val configs = enabledMirrors(
            IndexerSource.TPB,
            IndexerSource.YTS,
            IndexerSource.NYAA,
            IndexerSource.LIMETORRENTS,
            IndexerSource.EZTV
        )
        val sessionId = coordinator.startSearch("matrix", TorrentCategory.ALL, SortOption.SEEDERS, configs)

        val snap = withTimeout(4_000L) {
            coordinator.snapshots.first { it.sessionId == sessionId && !it.isSearching }
        }
        assertEquals(1, snap.groups.size)
        assertEquals(ProviderTerminalState.MATCHES, snap.statuses.first { it.source == IndexerSource.TPB }.terminalState)
        assertEquals(ProviderTerminalState.RATE_LIMITED, snap.statuses.first { it.source == IndexerSource.YTS }.terminalState)
        assertEquals(ProviderTerminalState.PARSER_ERROR, snap.statuses.first { it.source == IndexerSource.NYAA }.terminalState)
        assertEquals(ProviderTerminalState.NETWORK_ERROR, snap.statuses.first { it.source == IndexerSource.LIMETORRENTS }.terminalState)
        assertEquals(ProviderTerminalState.TIMEOUT, snap.statuses.first { it.source == IndexerSource.EZTV }.terminalState)

        // Retry only YTS; invoke twice concurrently to prove overlapping duplicate retry is ignored
        allowYtsRetry.set(true)
        coordinator.retryProvider(IndexerSource.YTS, SortOption.SEEDERS, configs)
        coordinator.retryProvider(IndexerSource.YTS, SortOption.SEEDERS, configs)
        ytsRetryGate.complete(Unit)

        val afterRetry = withTimeout(3_000L) {
            coordinator.snapshots.first { s ->
                s.sessionId == sessionId &&
                    s.statuses.first { it.source == IndexerSource.YTS }.terminalState == ProviderTerminalState.MATCHES
            }
        }
        assertEquals(2, afterRetry.groups.size)
        assertTrue(afterRetry.groups.any { it.title == "Healthy TPB Release" })
        assertTrue(afterRetry.groups.any { it.title.startsWith("Recovered YTS Movie") })
        assertEquals(2, ytsAttempts.get())
    }

    @Test
    fun `canonical grouping merges hex and base32 without summing swarm keeps btmh separate and upgrades unresolved alias`() = runBlocking {
        val x1337ListingHtml = """
            <table class="table-list"><tbody>
              <tr>
                <td class="coll-1 name"><a href="/torrent/777001/Shared-Movie-1080p/">Shared Movie 1080p (1337x)</a></td>
                <td class="coll-2 seeds">60</td>
                <td class="coll-3 leeches">10</td>
                <td class="coll-date">Nov. 10th '26</td>
                <td class="coll-4 size">2.00 GB</td>
              </tr>
              <tr>
                <td class="coll-1 name"><a href="/torrent/777002/Unrelated-Unresolved/">Unrelated Unresolved Listing</a></td>
                <td class="coll-2 seeds">30</td>
                <td class="coll-3 leeches">5</td>
                <td class="coll-date">Nov. 11th '26</td>
                <td class="coll-4 size">1.00 GB</td>
              </tr>
            </tbody></table>
        """.trimIndent()
        val x1337DetailHtml = """<a href="magnet:?xt=urn:btih:$sharedHex&dn=Shared+Movie+1080p">Magnet</a>"""

        val tpbBase32Adapter = object : ProviderAdapter {
            override val source = IndexerSource.TPB
            override val supportedCategories: Set<TorrentCategory> = TorrentCategory.entries.toSet()
            override fun buildWebSearchUrl(mirrors: List<String>, query: String) = "https://tpb.test/?q=$query"
            override suspend fun searchOnMirror(
                mirrorBaseUrl: String,
                query: String,
                category: TorrentCategory,
                sortOption: SortOption,
                transport: HttpTransport,
                limits: SearchNetworkLimits
            ): List<TorrentResult> = listOf(
                TorrentResult(
                    id = "tpb_1",
                    title = "Shared Movie 1080p (TPB Base32)",
                    source = IndexerSource.TPB,
                    category = TorrentCategory.MOVIES,
                    sizeLabel = "2.00 GB",
                    sizeBytes = 2_147_483_648L,
                    seeders = 150,
                    leechers = 25,
                    uploadDateLabel = "Today",
                    uploadEpochSeconds = 1700000500L,
                    magnetUri = "magnet:?xt=urn:btih:$sharedBase32&dn=Shared+Movie",
                    infoHash = sharedBase32,
                    detailPageUrl = "https://thepiratebay.org/description.php?id=1",
                    knownSizeBytes = 2_147_483_648L,
                    knownSeeders = 150,
                    knownLeechers = 25
                ),
                TorrentResult(
                    id = "tpb_v2",
                    title = "BitTorrent v2 Only Release",
                    source = IndexerSource.TPB,
                    category = TorrentCategory.OTHER,
                    sizeLabel = "500.0 MB",
                    sizeBytes = 524_288_000L,
                    seeders = 40,
                    leechers = 2,
                    uploadDateLabel = "Today",
                    uploadEpochSeconds = 1700000600L,
                    magnetUri = "magnet:?xt=urn:btmh:$btmhV2&dn=V2+Release",
                    infoHash = btmhV2,
                    detailPageUrl = "https://thepiratebay.org/description.php?id=2",
                    knownSizeBytes = 524_288_000L,
                    knownSeeders = 40,
                    knownLeechers = 2
                )
            )
        }

        val ytsHexAdapter = object : ProviderAdapter {
            override val source = IndexerSource.YTS
            override val supportedCategories: Set<TorrentCategory> = TorrentCategory.entries.toSet()
            override fun buildWebSearchUrl(mirrors: List<String>, query: String) = "https://yts.test/?q=$query"
            override suspend fun searchOnMirror(
                mirrorBaseUrl: String,
                query: String,
                category: TorrentCategory,
                sortOption: SortOption,
                transport: HttpTransport,
                limits: SearchNetworkLimits
            ): List<TorrentResult> = listOf(
                TorrentResult(
                    id = "yts_9",
                    title = "Shared Movie 1080p (YTS Hex)",
                    source = IndexerSource.YTS,
                    category = TorrentCategory.MOVIES,
                    sizeLabel = "2.00 GB",
                    sizeBytes = 2_147_483_648L,
                    seeders = 80,
                    leechers = 12,
                    uploadDateLabel = "Yesterday",
                    uploadEpochSeconds = 1700000400L,
                    magnetUri = "magnet:?xt=urn:btih:${sharedHex.uppercase()}&dn=Shared+Movie",
                    infoHash = sharedHex.uppercase(),
                    detailPageUrl = "https://yts.mx/movies/shared-movie",
                    knownSizeBytes = 2_147_483_648L,
                    knownSeeders = 80,
                    knownLeechers = 12
                )
            )
        }

        val transport = FakeTransport(
            onGet = { url, _ ->
                when {
                    url.contains("/torrent/777001/") -> x1337DetailHtml
                    url.contains("/search/") -> x1337ListingHtml
                    else -> ""
                }
            }
        )

        val adapters = SearchCoordinator.defaultAdapters().toMutableMap().apply {
            put(IndexerSource.TPB, tpbBase32Adapter)
            put(IndexerSource.YTS, ytsHexAdapter)
        }
        val coordinator = SearchCoordinator(
            adapters = adapters,
            transport = transport,
            ioDispatcher = Dispatchers.Default
        )

        val configs = mapOf(
            IndexerSource.TPB to MirrorConfig(IndexerSource.TPB, enabled = true, mirrors = listOf("https://tpb.test"), activeMirrorUrl = "https://tpb.test"),
            IndexerSource.YTS to MirrorConfig(IndexerSource.YTS, enabled = true, mirrors = listOf("https://yts.test"), activeMirrorUrl = "https://yts.test"),
            IndexerSource.X1337 to MirrorConfig(IndexerSource.X1337, enabled = true, mirrors = listOf("https://x1337x.cc"), activeMirrorUrl = "https://x1337x.cc"),
            IndexerSource.EZTV to MirrorConfig(IndexerSource.EZTV, enabled = false),
            IndexerSource.NYAA to MirrorConfig(IndexerSource.NYAA, enabled = false),
            IndexerSource.LIMETORRENTS to MirrorConfig(IndexerSource.LIMETORRENTS, enabled = false)
        )

        val sessionId = coordinator.startSearch("shared movie", TorrentCategory.ALL, SortOption.SEEDERS, configs)
        val snap = withTimeout(3_000L) {
            coordinator.snapshots.first { it.sessionId == sessionId && !it.isSearching }
        }

        // Expect 4 groups: 1 merged BTIH (TPB+YTS), 1 distinct BTMH v2, and 2 distinct unresolved 1337x listings
        assertEquals(4, snap.groups.size)

        val mergedBtih = snap.findGroup("btih:$sharedHex")
        assertNotNull(mergedBtih)
        assertEquals(2, mergedBtih!!.listings.size)
        // Representative is TPB (150 seeders > YTS 80 seeders); NEVER summed to 230!
        assertEquals(IndexerSource.TPB, mergedBtih.primarySource)
        assertEquals(150, mergedBtih.knownSeeders)
        assertEquals(25, mergedBtih.knownLeechers)
        assertTrue(mergedBtih.listings.any { it.source == IndexerSource.YTS && it.knownSeeders == 80 && it.detailPageUrl == "https://yts.mx/movies/shared-movie" })

        val btmhGroup = snap.findGroup("btmh:$btmhV2")
        assertNotNull(btmhGroup)
        assertEquals(TorrentHashFamily.BTMH, (btmhGroup!!.canonicalIdentity as CanonicalIdentityKey.VerifiedHash).family)

        val unresolvedFirst = snap.groups.first { it.title.contains("1337x") }
        val unresolvedKey = unresolvedFirst.groupKey
        assertTrue(unresolvedKey.startsWith("listing:X1337:"))

        // Lazily resolve the 1337x listing -> discovers sharedHex -> aliases and merges with TPB+YTS group!
        coordinator.resolveMagnetForSelection(sessionId, unresolvedKey, configs)
        val mergedSnap = withTimeout(3_000L) {
            coordinator.snapshots.first { s ->
                s.sessionId == sessionId && s.resolveGroupKey(unresolvedKey) == "btih:$sharedHex"
            }
        }
        assertEquals(3, mergedSnap.groups.size)
        val upgraded = mergedSnap.findGroup(unresolvedKey)!!
        assertEquals("btih:$sharedHex", upgraded.groupKey)
        assertEquals(3, upgraded.listings.size)
        assertEquals(150, upgraded.knownSeeders) // Still not summed!
        assertTrue(upgraded.magnetState is MagnetResolutionState.Available)
    }

    @Test
    fun `loopback okhttp transport cancels superseded query ignores stale responses and enforces byte limit`() = runBlocking {
        val queryAStarted = CountDownLatch(1)
        val queryACancelledOnServer = AtomicBoolean(false)
        val queryARelease = CountDownLatch(1)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/q.php") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            if (query.contains("alpha")) {
                queryAStarted.countDown()
                // Hold open briefly; when OkHttp cancels call, client socket closes
                queryARelease.await(1200, TimeUnit.MILLISECONDS)
                val body = """[{"id":"999","name":"STALE ALPHA RESULT","info_hash":"$sharedHex","seeders":"999","leechers":"1","size":"1000","added":"1700000000","category":"201"}]""".toByteArray()
                try {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } catch (_: IOException) {
                    queryACancelledOnServer.set(true)
                }
            } else if (query.contains("beta")) {
                val body = """[{"id":"222","name":"FRESH BETA RESULT","info_hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","seeders":"50","leechers":"3","size":"2000","added":"1700000001","category":"201"}]""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                // Oversized response > maxResponseBytes (4096 bytes)
                val big = ByteArray(16_384) { 'A'.code.toByte() }
                exchange.sendResponseHeaders(200, big.size.toLong())
                exchange.responseBody.use { it.write(big) }
            }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val plainClient = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val transport = OkHttpTransport(client = plainClient, pingClient = plainClient)
            val coordinator = SearchCoordinator(
                transport = transport,
                limits = SearchNetworkLimits(
                    wholeSearchDeadlineMs = 5_000L,
                    defaultAttemptTimeoutMs = 4_000L,
                    maxResponseBytes = 4_096L
                ),
                ioDispatcher = Dispatchers.IO
            )
            val configs = IndexerSource.entries.associateWith { src ->
                MirrorConfig(
                    provider = src,
                    enabled = src == IndexerSource.TPB,
                    mirrors = listOf(baseUrl),
                    activeMirrorUrl = baseUrl
                )
            }

            val sessionA = coordinator.startSearch("alpha", TorrentCategory.ALL, SortOption.SEEDERS, configs)
            assertTrue("Query A request should reach loopback server", queryAStarted.await(3, TimeUnit.SECONDS))

            // Supersede with Query B while Query A is in-flight
            val sessionB = coordinator.startSearch("beta", TorrentCategory.ALL, SortOption.SEEDERS, configs)
            assertTrue(sessionB > sessionA)

            val betaSnap = withTimeout(3_000L) {
                coordinator.snapshots.first { it.sessionId == sessionB && !it.isSearching }
            }
            assertEquals(1, betaSnap.groups.size)
            assertEquals("FRESH BETA RESULT", betaSnap.groups.first().title)

            // Let Query A finish on server side and confirm stale A NEVER contaminates B
            queryARelease.countDown()
            delay(200L)
            val afterStale = coordinator.snapshots.value
            assertEquals(sessionB, afterStale.sessionId)
            assertFalse(afterStale.groups.any { it.title.contains("STALE ALPHA") })

            // Verify byte cap enforcement maps to PARSER_ERROR
            val sessionBig = coordinator.startSearch("oversized", TorrentCategory.ALL, SortOption.SEEDERS, configs)
            val bigSnap = withTimeout(3_000L) {
                coordinator.snapshots.first { it.sessionId == sessionBig && !it.isSearching }
            }
            assertEquals(
                ProviderTerminalState.PARSER_ERROR,
                bigSnap.statuses.first { it.source == IndexerSource.TPB }.terminalState
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `disabled sources and unsupported categories reach immediate clean terminal states`() = runBlocking {
        val coordinator = SearchCoordinator(transport = FakeTransport(), ioDispatcher = Dispatchers.Default)

        // All sources disabled
        val allDisabled = IndexerSource.entries.associateWith { MirrorConfig(it, enabled = false) }
        val s1 = coordinator.startSearch("linux", TorrentCategory.ALL, SortOption.SEEDERS, allDisabled)
        val snap1 = coordinator.snapshots.value
        assertEquals(s1, snap1.sessionId)
        assertFalse(snap1.isSearching)
        assertTrue(snap1.statuses.all { it.terminalState == ProviderTerminalState.DISABLED })

        // Category ANIME marks YTS and EZTV as UNSUPPORTED_CATEGORY
        val ytsAndEztvOnly = enabledMirrors(IndexerSource.YTS, IndexerSource.EZTV)
        val s2 = coordinator.startSearch("spirited away", TorrentCategory.ANIME, SortOption.SEEDERS, ytsAndEztvOnly)
        val snap2 = coordinator.snapshots.value
        assertEquals(s2, snap2.sessionId)
        assertFalse(snap2.isSearching)
        assertEquals(ProviderTerminalState.UNSUPPORTED_CATEGORY, snap2.statuses.first { it.source == IndexerSource.YTS }.terminalState)
        assertEquals(ProviderTerminalState.UNSUPPORTED_CATEGORY, snap2.statuses.first { it.source == IndexerSource.EZTV }.terminalState)
    }

    @Test
    fun `cancelling selection magnet resolution prevents stale result from mutating new selection`() = runBlocking {
        val firstDetailGate = CompletableDeferred<Unit>()
        val slowX1337 = object : ProviderAdapter {
            override val source = IndexerSource.X1337
            override val supportedCategories: Set<TorrentCategory> = TorrentCategory.entries.toSet()
            override fun buildWebSearchUrl(mirrors: List<String>, query: String) = "https://x1337x.cc/search/$query/1/"
            override suspend fun searchOnMirror(
                mirrorBaseUrl: String,
                query: String,
                category: TorrentCategory,
                sortOption: SortOption,
                transport: HttpTransport,
                limits: SearchNetworkLimits
            ): List<TorrentResult> = listOf(
                TorrentResult(
                    id = "x1", title = "First Unresolved", source = IndexerSource.X1337,
                    category = TorrentCategory.OTHER, sizeLabel = "1.0 GB", sizeBytes = 1000L,
                    seeders = 20, leechers = 2, uploadDateLabel = "Today", uploadEpochSeconds = 100L,
                    magnetUri = null, infoHash = "", detailPageUrl = "https://x1337x.cc/torrent/1/a/",
                    knownSizeBytes = 1000L, knownSeeders = 20, knownLeechers = 2
                ),
                TorrentResult(
                    id = "x2", title = "Second Unresolved", source = IndexerSource.X1337,
                    category = TorrentCategory.OTHER, sizeLabel = "2.0 GB", sizeBytes = 2000L,
                    seeders = 10, leechers = 1, uploadDateLabel = "Today", uploadEpochSeconds = 100L,
                    magnetUri = null, infoHash = "", detailPageUrl = "https://x1337x.cc/torrent/2/b/",
                    knownSizeBytes = 2000L, knownSeeders = 10, knownLeechers = 1
                )
            )

            override suspend fun resolveMagnet(
                listing: ProviderListing,
                orderedMirrors: List<String>,
                transport: HttpTransport,
                limits: SearchNetworkLimits
            ): String {
                if (listing.listingId == "x1") {
                    firstDetailGate.await()
                    return "magnet:?xt=urn:btih:$sharedHex&dn=First"
                }
                return "magnet:?xt=urn:btih:dddddddddddddddddddddddddddddddddddddddd&dn=Second"
            }
        }

        val adapters = SearchCoordinator.defaultAdapters().toMutableMap().apply {
            put(IndexerSource.X1337, slowX1337)
        }
        val coordinator = SearchCoordinator(adapters = adapters, transport = FakeTransport(), ioDispatcher = Dispatchers.Default)
        val configs = enabledMirrors(IndexerSource.X1337)
        val sessionId = coordinator.startSearch("test", TorrentCategory.ALL, SortOption.SEEDERS, configs)
        val snap = withTimeout(3_000L) { coordinator.snapshots.first { it.sessionId == sessionId && !it.isSearching } }

        val key1 = snap.groups.first { it.title == "First Unresolved" }.groupKey
        val key2 = snap.groups.first { it.title == "Second Unresolved" }.groupKey

        // Start resolving x1, then immediately switch to x2
        coordinator.resolveMagnetForSelection(sessionId, key1, configs)
        coordinator.resolveMagnetForSelection(sessionId, key2, configs)
        firstDetailGate.complete(Unit)

        val resolvedSnap = withTimeout(3_000L) {
            coordinator.snapshots.first { s ->
                s.sessionId == sessionId &&
                    s.findGroup(key2)?.magnetState is MagnetResolutionState.Available
            }
        }
        assertEquals(
            "dddddddddddddddddddddddddddddddddddddddd",
            (resolvedSnap.findGroup(key2)?.magnetState as MagnetResolutionState.Available).identity.canonicalHex
        )
        // First listing must NOT have been mutated by its cancelled resolution
        assertFalse(resolvedSnap.findGroup(key1)?.magnetState is MagnetResolutionState.Available)
    }
}
