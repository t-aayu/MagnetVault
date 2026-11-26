package com.example

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.data.firebase.CloudSyncRepository
import com.example.data.indexers.IndexerAggregatorRepository
import com.example.data.local.LegacyDataMigrator
import com.example.data.local.LocalPrefsStore
import com.example.data.local.VaultLocalRepository
import com.example.data.local.db.MagnetVaultDatabase
import com.example.data.network.HttpResponseData
import com.example.data.network.HttpStatusException
import com.example.data.network.HttpTransport
import com.example.data.network.SearchNetworkLimits
import com.example.data.sync.FirestoreSyncEngine
import com.example.data.sync.SyncRuntimeRegistry
import com.example.model.AppAppearanceMode
import com.example.model.CanonicalReleaseGroup
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.SearchContentPhase
import com.example.model.SortOption
import com.example.model.TorrentCategory
import com.example.model.WebsiteOpenMode
import com.example.ui.MagnetVaultApp
import com.example.ui.handoff.MagnetHandoffController
import com.example.ui.handoff.MagnetHandoffGateway
import com.example.ui.search.SearchScreen
import com.example.ui.theme.MagnetVaultTheme
import com.example.viewmodel.SearchViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchUiAndHandoffTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val validBtih = "0123456789abcdef0123456789abcdef01234567"
    private val validMagnet = "magnet:?xt=urn:btih:$validBtih&dn=Ubuntu+24.04+LTS"

    private class ScriptedTransport(
        private val onGet: suspend (String) -> String = { "" },
        private val onPost: suspend (String, String) -> String = { _, _ -> "" }
    ) : HttpTransport {
        val getCalls = AtomicInteger(0)
        val postCalls = AtomicInteger(0)

        override suspend fun get(url: String, headers: Map<String, String>, maxResponseBytes: Long): String {
            getCalls.incrementAndGet()
            return onGet(url)
        }

        override suspend fun postJson(
            url: String,
            jsonBody: String,
            headers: Map<String, String>,
            maxResponseBytes: Long
        ): String {
            postCalls.incrementAndGet()
            return onPost(url, jsonBody)
        }

        override suspend fun probe(url: String, postJsonBody: String?, maxResponseBytes: Long): HttpResponseData =
            HttpResponseData(200, "ok")
    }

    private class FakeHandoffGateway(
        var clipboard: String = "",
        var localClientInstalled: Boolean = true
    ) : MagnetHandoffGateway {
        var lastCopiedText: String? = null
        var lastOpenedMagnet: String? = null
        var lastOpenedUrl: String? = null
        var lastSharedContent: String? = null

        override fun readClipboardText(): String = clipboard
        override fun copyToClipboard(label: String, text: String): Boolean {
            lastCopiedText = text
            clipboard = text
            return true
        }

        override fun openMagnetInLocalClient(magnetUri: String): Boolean {
            lastOpenedMagnet = magnetUri
            return localClientInstalled
        }

        override fun openUrlInBrowser(url: String): Boolean {
            lastOpenedUrl = url
            return true
        }

        override fun shareText(title: String, content: String): Boolean {
            lastSharedContent = content
            return true
        }
    }

    private var inMemoryDb: MagnetVaultDatabase? = null

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SyncRuntimeRegistry.resetForTesting()
        MagnetVaultDatabase.resetForTesting(context)
        LocalPrefsStore(context).clearAllForTesting()
    }

    @After
    fun tearDown() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        inMemoryDb?.close()
        inMemoryDb = null
        LocalPrefsStore(context).clearAllForTesting()
        SyncRuntimeRegistry.resetForTesting()
        MagnetVaultDatabase.resetForTesting(context)
    }

    private fun createLocalRepo(): Pair<LocalPrefsStore, CloudSyncRepository> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = LocalPrefsStore(context)
        val db = MagnetVaultDatabase.createInMemory(context).also { inMemoryDb = it }
        val localRepo = VaultLocalRepository(db, LegacyDataMigrator(db, prefs))
        val engine = FirestoreSyncEngine(db, localRepo, prefs, gateway = null, currentAuthUidProvider = { null })
        val cloud = CloudSyncRepository(
            appContext = context,
            prefsStore = prefs,
            isCloudMode = false,
            localRepoOverride = localRepo,
            syncEngineOverride = engine
        )
        return prefs to cloud
    }

    private fun waitUntilWithMainLooper(timeoutMillis: Long = 3_000L, condition: () -> Boolean) {
        composeRule.waitUntil(timeoutMillis) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            condition()
        }
    }

    @Test
    fun `search home does not auto-fetch trending and paste magnet validates locally without submitting invalid input`() {
        val transport = ScriptedTransport(
            onGet = { url ->
                when {
                    url.contains("data_top100") -> """[{"id":"7","name":"Trending Linux 1080p","info_hash":"$validBtih","seeders":"400","leechers":"20","size":"1073741824","added":"1700000000","category":"201"}]"""
                    url.contains("apibay") -> """[{"id":"8","name":"Explicit Search Match","info_hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","seeders":"90","leechers":"5","size":"1073741824","added":"1700000000","category":"201"}]"""
                    else -> throw HttpStatusException(404, "unused")
                }
            }
        )
        val (_, cloud) = createLocalRepo()
        runBlocking {
            IndexerSource.entries.forEach { src ->
                cloud.saveMirrorConfig(MirrorConfig(src, enabled = src == IndexerSource.TPB)).getOrThrow()
            }
        }
        val indexerRepo = IndexerAggregatorRepository(transport = transport, ioDispatcher = Dispatchers.Default)
        val vm = SearchViewModel(indexerRepo, cloud)
        val gateway = FakeHandoffGateway(clipboard = "not a magnet link")

        composeRule.setContent {
            MagnetVaultTheme(appearanceMode = AppAppearanceMode.OBSIDIAN) {
                SearchScreen(
                    viewModel = vm,
                    websiteOpenMode = WebsiteOpenMode.IN_APP,
                    torrentBoxOpenMode = WebsiteOpenMode.IN_APP,
                    onReadClipboard = { gateway.readClipboardText() },
                    onOpenSettings = {},
                    onOpenLocalClient = { _, _ -> },
                    onCopyMagnet = { _, _ -> },
                    onOpenSeedr = { _, _ -> },
                    onOpenTorBox = { _, _ -> },
                    onShareMagnet = { _, _ -> },
                    onOpenSourcePage = {},
                    onShowMessage = {}
                )
            }
        }

        composeRule.waitForIdle()
        // Opening Search must NOT auto-fetch trending
        assertEquals(0, transport.getCalls.get())
        assertEquals(0, transport.postCalls.get())
        composeRule.onNodeWithTag("search_home_idle_card").assertIsDisplayed()

        // Invalid paste magnet shows clear error and NEVER submits search automatically
        composeRule.onNodeWithTag("paste_magnet_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("paste_magnet_error_card").assertIsDisplayed()
        assertFalse(vm.uiState.value.hasSearched)
        assertEquals(0, transport.getCalls.get())

        // Valid paste magnet opens shared action sheet locally with zero network calls
        gateway.clipboard = validMagnet
        composeRule.onNodeWithTag("paste_magnet_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("magnet_action_sheet_content").assertIsDisplayed()
        composeRule.onNodeWithTag("action_open_with_button").assertIsEnabled()
        composeRule.onNodeWithTag("copy_magnet_button").assertIsEnabled()
        composeRule.onNodeWithTag("action_save_button").assertIsEnabled()
        assertEquals(0, transport.getCalls.get())
        vm.dismissPastedMagnetSheet()
        composeRule.waitForIdle()

        // Explicit Trending toggle fetches trending on demand
        composeRule.onNodeWithTag("trending_toggle_button").performClick()
        composeRule.waitUntil(3_000L) {
            composeRule.onAllNodesWithText("Trending Linux 1080p").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(transport.getCalls.get() >= 1)
    }

    @Test
    fun `search states local sort without requery and filtered-out reset work accurately`() {
        val transport = ScriptedTransport(
            onGet = { url ->
                when {
                    url.contains("zeroquery") -> """[{"id":"0","name":"No results returned"}]"""
                    url.contains("failquery") -> throw HttpStatusException(503, "apibay.org")
                    url.contains("apibay") -> """
                        [
                          {"id":"1","name":"Small High Seed 720p","info_hash":"1111111111111111111111111111111111111111","seeders":"500","leechers":"10","size":"524288000","added":"1700000100","category":"201"},
                          {"id":"2","name":"Huge Low Seed 2160p 4K","info_hash":"2222222222222222222222222222222222222222","seeders":"20","leechers":"5","size":"8589934592","added":"1700000200","category":"201"}
                        ]
                    """.trimIndent()
                    else -> throw HttpStatusException(503, "other")
                }
            }
        )
        val (_, cloud) = createLocalRepo()
        runBlocking {
            IndexerSource.entries.forEach { src ->
                cloud.saveMirrorConfig(MirrorConfig(src, enabled = src == IndexerSource.TPB)).getOrThrow()
            }
        }
        val indexerRepo = IndexerAggregatorRepository(transport = transport, ioDispatcher = Dispatchers.Default)
        val vm = SearchViewModel(indexerRepo, cloud)

        composeRule.setContent {
            MagnetVaultTheme(appearanceMode = AppAppearanceMode.ALABASTER) {
                SearchScreen(
                    viewModel = vm,
                    websiteOpenMode = WebsiteOpenMode.IN_APP,
                    torrentBoxOpenMode = WebsiteOpenMode.IN_APP,
                    onReadClipboard = { "" },
                    onOpenSettings = {},
                    onOpenLocalClient = { _, _ -> },
                    onCopyMagnet = { _, _ -> },
                    onOpenSeedr = { _, _ -> },
                    onOpenTorBox = { _, _ -> },
                    onShareMagnet = { _, _ -> },
                    onOpenSourcePage = {},
                    onShowMessage = {}
                )
            }
        }

        // Wait for DataStore mirror config (TPB-only) to settle before searching
        waitUntilWithMainLooper {
            vm.mirrorConfigs.value[IndexerSource.TPB]?.enabled == true &&
                vm.mirrorConfigs.value[IndexerSource.YTS]?.enabled == false
        }

        // 1. Submit search and verify results
        composeRule.onNodeWithTag("search_query_input").performTextInput("movies")
        composeRule.onNodeWithTag("search_submit_button").performClick()
        waitUntilWithMainLooper {
            vm.displayedGroups.value.size == 2 && !vm.uiState.value.isSearching
        }
        assertEquals("Small High Seed 720p", vm.displayedGroups.value.first().title)
        val callsAfterSearch = transport.getCalls.get()

        // 2. Change Sort to SIZE -> reorders locally WITHOUT calling transport again
        vm.selectSort(SortOption.SIZE)
        composeRule.waitForIdle()
        assertEquals("Huge Low Seed 2160p 4K", vm.displayedGroups.value.first().title)
        assertEquals(callsAfterSearch, transport.getCalls.get())

        // 3. Apply unmatched quality filter -> FILTERED_OUT state with Reset Filters
        vm.toggleQualityTag(com.example.model.QualityTag.REMUX)
        composeRule.waitForIdle()
        assertEquals(SearchContentPhase.FILTERED_OUT, vm.uiState.value.contentPhase)
        composeRule.onNodeWithTag("search_state_filtered_out").assertIsDisplayed()
        composeRule.onNodeWithTag("active_filters_reset_button").performClick()
        composeRule.waitForIdle()
        assertEquals(2, vm.displayedGroups.value.size)

        // 4. Zero matches state
        vm.executeSearch("zeroquery", recordHistory = false)
        waitUntilWithMainLooper { vm.uiState.value.contentPhase == SearchContentPhase.ZERO_MATCHES }
        composeRule.onNodeWithTag("search_state_zero_matches").assertIsDisplayed()

        // 5. Total failure state
        vm.executeSearch("failquery", recordHistory = false)
        waitUntilWithMainLooper { vm.uiState.value.contentPhase == SearchContentPhase.TOTAL_FAILURE }
        composeRule.onNodeWithTag("search_state_total_failure").assertIsDisplayed()

        // 6. All sources disabled state
        runBlocking {
            IndexerSource.entries.forEach { src ->
                cloud.saveMirrorConfig(MirrorConfig(src, enabled = false)).getOrThrow()
            }
        }
        waitUntilWithMainLooper {
            vm.mirrorConfigs.value.values.none { it.enabled }
        }
        vm.executeSearch("anything", recordHistory = false)
        waitUntilWithMainLooper { vm.uiState.value.contentPhase == SearchContentPhase.ALL_SOURCES_DISABLED }
        composeRule.onNodeWithTag("search_state_all_disabled").assertIsDisplayed()
    }

    @Test
    fun `unresolved listing allows saving fails cleanly retries in place and upgrades bookmark without duplicates`() {
        val detailAttempts = AtomicInteger(0)
        val searchCalls = AtomicInteger(0)
        val listHtml = """
            <table class="table-list"><tbody>
              <tr>
                <td class="coll-1 name"><a href="/torrent/555444/Unresolved-Linux-ISO/">Unresolved Linux ISO 1080p</a></td>
                <td class="coll-2 seeds">88</td>
                <td class="coll-3 leeches">9</td>
                <td class="coll-date">Today</td>
                <td class="coll-4 size">1.50 GB</td>
              </tr>
            </tbody></table>
        """.trimIndent()

        val transport = ScriptedTransport(
            onGet = { url ->
                when {
                    url.contains("/torrent/555444/") -> {
                        if (detailAttempts.incrementAndGet() == 1) {
                            throw HttpStatusException(502, "x1337x.cc")
                        } else {
                            """<a href="$validMagnet">Magnet</a>"""
                        }
                    }
                    url.contains("/search/") -> {
                        searchCalls.incrementAndGet()
                        listHtml
                    }
                    else -> throw HttpStatusException(404, "unused")
                }
            },
            onPost = { _, _ -> throw HttpStatusException(503, "api.knaben.org") }
        )

        val (_, cloud) = createLocalRepo()
        runBlocking {
            cloud.observeBookmarks().first().forEach { cloud.removeBookmark(it.id) }
            IndexerSource.entries.forEach { src ->
                cloud.saveMirrorConfig(
                    MirrorConfig(
                        provider = src,
                        enabled = src == IndexerSource.X1337,
                        mirrors = listOf("https://x1337x.cc"),
                        activeMirrorUrl = "https://x1337x.cc"
                    )
                ).getOrThrow()
            }
        }
        val indexerRepo = IndexerAggregatorRepository(
            transport = transport,
            limits = SearchNetworkLimits(fastFailoverAttemptTimeoutMs = 2_000L, magnetResolveTimeoutMs = 2_000L),
            ioDispatcher = Dispatchers.Default
        )
        val vm = SearchViewModel(indexerRepo, cloud)

        composeRule.setContent {
            MagnetVaultTheme(appearanceMode = AppAppearanceMode.OBSIDIAN) {
                SearchScreen(
                    viewModel = vm,
                    websiteOpenMode = WebsiteOpenMode.IN_APP,
                    torrentBoxOpenMode = WebsiteOpenMode.IN_APP,
                    onReadClipboard = { "" },
                    onOpenSettings = {},
                    onOpenLocalClient = { _, _ -> },
                    onCopyMagnet = { _, _ -> },
                    onOpenSeedr = { _, _ -> },
                    onOpenTorBox = { _, _ -> },
                    onShareMagnet = { _, _ -> },
                    onOpenSourcePage = {},
                    onShowMessage = {}
                )
            }
        }

        waitUntilWithMainLooper {
            vm.mirrorConfigs.value[IndexerSource.X1337]?.enabled == true &&
                vm.mirrorConfigs.value[IndexerSource.TPB]?.enabled == false
        }

        vm.executeSearch("linux", recordHistory = false)
        waitUntilWithMainLooper { vm.displayedGroups.value.size == 1 && !vm.uiState.value.isSearching }
        val initialSearchCalls = searchCalls.get()

        // Tap unresolved row -> first resolution attempt fails -> Save stays enabled while magnet actions stay disabled
        val group = vm.displayedGroups.value.first()
        composeRule.onNodeWithTag("release_row_${group.groupKey}").performClick()
        waitUntilWithMainLooper {
            composeRule.onAllNodesWithTag("magnet_state_failed").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("action_open_with_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("copy_magnet_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("action_save_button").assertIsEnabled()

        // Save unresolved listing
        composeRule.onNodeWithTag("action_save_button").performClick()
        waitUntilWithMainLooper { vm.bookmarkedIds.value.size == 1 }

        // Retry resolution in place -> succeeds WITHOUT rerunning search and upgrades bookmark without duplicates
        composeRule.onNodeWithTag("retry_magnet_resolve_button").performClick()
        waitUntilWithMainLooper {
            composeRule.onAllNodesWithTag("magnet_state_available").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("action_open_with_button").assertIsEnabled()
        composeRule.onNodeWithTag("copy_magnet_button").assertIsEnabled()
        assertEquals(initialSearchCalls, searchCalls.get())

        waitUntilWithMainLooper {
            vm.bookmarkedIds.value.contains("bm_$validBtih")
        }
        assertEquals(1, vm.bookmarkedIds.value.size)
    }

    @Test
    fun `handoff controller provides actionable fallback when no local torrent client is installed`() {
        val gateway = FakeHandoffGateway(localClientInstalled = false)
        val controller = MagnetHandoffController(gateway)

        val feedback = controller.openLocalClient("Ubuntu ISO", validMagnet)
        assertFalse(feedback.success)
        assertTrue(feedback.message.contains("No torrent client installed", ignoreCase = true))
        assertTrue(feedback.message.contains("copied", ignoreCase = true))
        assertEquals(validMagnet, gateway.lastCopiedText)

        gateway.localClientInstalled = true
        val ok = controller.openLocalClient("Ubuntu ISO", validMagnet)
        assertTrue(ok.success)
        assertEquals(validMagnet, gateway.lastOpenedMagnet)
    }

    @Test
    fun `saved state handle restores submitted query filters and selection without storing result lists`() {
        val (_, cloud) = createLocalRepo()
        runBlocking {
            IndexerSource.entries.forEach { src ->
                cloud.saveMirrorConfig(MirrorConfig(src, enabled = src == IndexerSource.TPB)).getOrThrow()
            }
        }
        val transport = ScriptedTransport(
            onGet = {
                """[{"id":"42","name":"Restored Release 1080p","info_hash":"$validBtih","seeders":"75","leechers":"4","size":"1073741824","added":"1700000000","category":"201"}]"""
            }
        )
        val handle = SavedStateHandle()
        val vm1 = SearchViewModel(IndexerAggregatorRepository(transport = transport, ioDispatcher = Dispatchers.Default), cloud, handle)
        vm1.selectSort(SortOption.SIZE)
        vm1.executeSearch("restored query", recordHistory = false)
        val latestGroups = AtomicReference<List<CanonicalReleaseGroup>>(emptyList())
        val collectJob = CoroutineScope(Dispatchers.Default).launch {
            vm1.displayedGroups.collect { latestGroups.set(it) }
        }
        waitUntilWithMainLooper(timeoutMillis = 5_000L) {
            latestGroups.get().isNotEmpty()
        }
        val group = latestGroups.get().first()
        collectJob.cancel()
        vm1.selectGroup(group)

        // Confirm saved state contains lightweight keys only (no result list objects)
        assertEquals("restored query", handle.get<String>("mv_submitted_query"))
        assertEquals(SortOption.SIZE.name, handle.get<String>("mv_sort"))
        assertEquals("btih:$validBtih", handle.get<String>("mv_selected_group_key"))

        // Recreate ViewModel from same SavedStateHandle -> restores query, filters, and selection
        val vm2 = SearchViewModel(IndexerAggregatorRepository(transport = transport, ioDispatcher = Dispatchers.Default), cloud, handle)
        assertEquals("restored query", vm2.uiState.value.submittedQuery)
        assertEquals(SortOption.SIZE, vm2.uiState.value.filters.sort)
        assertEquals("btih:$validBtih", vm2.uiState.value.selectedGroupKey)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w1024dp-h800dp")
    fun `responsive shell switches bottom bar to nav rail supports large text and keeps local mode navigable`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = SyncRuntimeRegistry.getContainer(context)
        runBlocking {
            IndexerSource.entries.forEach { src ->
                container.localRepo.saveMirrorConfig(MirrorConfig(src, enabled = src == IndexerSource.TPB)).getOrThrow()
            }
        }
        val transport = ScriptedTransport(
            onGet = {
                """[{"id":"1","name":"Wide Screen Movie 1080p","info_hash":"$validBtih","seeders":"100","leechers":"5","size":"2147483648","added":"1700000000","category":"201"}]"""
            }
        )
        val indexerRepo = IndexerAggregatorRepository(transport = transport, ioDispatcher = Dispatchers.Default)
        val handoff = MagnetHandoffController(FakeHandoffGateway())

        // Expanded tablet width (1024dp) + large fontScale (1.5f) -> NavigationRail & side detail pane
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = 1.5f)) {
                MagnetVaultApp(
                    indexerRepoOverride = indexerRepo,
                    handoffControllerOverride = handoff
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("side_nav_rail").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_search").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_vault").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_sites").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_settings").assertIsDisplayed()

        // Navigate to Settings from top app bar and return cleanly
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_back_button").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_back_button").performClick()
        composeRule.waitForIdle()

        // Navigate to Vault and back to Search
        composeRule.onNodeWithTag("nav_vault").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_search").performClick()
        composeRule.waitForIdle()

        // Search and open expanded side detail pane
        composeRule.onNodeWithTag("search_query_input").performTextInput("wide")
        composeRule.onNodeWithTag("search_submit_button").performClick()
        composeRule.waitUntil(3_000L) {
            composeRule.onAllNodesWithTag("release_row_btih:$validBtih").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("release_row_btih:$validBtih").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("expanded_detail_pane").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h740dp")
    fun `compact phone layout renders bottom navigation bar`() {
        val indexerRepo = IndexerAggregatorRepository(transport = ScriptedTransport(), ioDispatcher = Dispatchers.Default)
        val handoff = MagnetHandoffController(FakeHandoffGateway())

        composeRule.setContent {
            MagnetVaultApp(
                indexerRepoOverride = indexerRepo,
                handoffControllerOverride = handoff
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("bottom_nav_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_search").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_vault").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_sites").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_settings").assertIsDisplayed()
    }
}
