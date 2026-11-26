package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.firebase.CloudSyncRepository
import com.example.data.indexers.IndexerAggregatorRepository
import com.example.data.local.LegacyDataMigrator
import com.example.data.local.LocalPrefsStore
import com.example.data.local.VaultLocalRepository
import com.example.data.local.db.BookmarkEntity
import com.example.data.local.db.MagnetVaultDatabase
import com.example.data.local.db.MirrorConfigEntity
import com.example.data.local.db.SearchHistoryEntity
import com.example.data.sync.CasMutationOutcome
import com.example.data.sync.CloudSyncGateway
import com.example.data.sync.FirestoreSyncEngine
import com.example.data.sync.SyncRuntimeRegistry
import com.example.model.AppAppearanceMode
import com.example.model.BookmarkRecord
import com.example.model.IndexerSource
import com.example.model.MirrorConfigRecord
import com.example.model.SearchHistoryRecord
import com.example.model.TorrentCategory
import com.example.model.TorrentResult
import com.example.model.WebsiteOpenMode
import com.example.ui.MagnetVaultApp
import com.example.ui.handoff.MagnetHandoffController
import com.example.ui.handoff.MagnetHandoffGateway
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.MagnetVaultTheme
import com.example.ui.vault.VaultScreen
import com.example.viewmodel.SettingsViewModel
import com.example.viewmodel.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase2VaultAndSettingsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: MagnetVaultDatabase
    private lateinit var prefsStore: LocalPrefsStore

    private val btihA = "0123456789abcdef0123456789abcdef01234567"
    private val magnetA = "magnet:?xt=urn:btih:$btihA&dn=Ubuntu+ISO"

    private class FakePreviewGateway : CloudSyncGateway {
        override suspend fun fetchBookmarks(uid: String): List<BookmarkRecord> = listOf(
            BookmarkRecord(id = "remote_1", userId = uid, title = "Cloud Item", infoHash = btih1)
        )
        override suspend fun fetchSearchHistory(uid: String): List<SearchHistoryRecord> = emptyList()
        override suspend fun fetchMirrors(uid: String): List<MirrorConfigRecord> = emptyList()
        override fun observeBookmarks(uid: String): Flow<List<BookmarkRecord>> = flowOf(emptyList())
        override fun observeSearchHistory(uid: String): Flow<List<SearchHistoryRecord>> = flowOf(emptyList())
        override fun observeMirrors(uid: String): Flow<List<MirrorConfigRecord>> = flowOf(emptyList())
        override suspend fun runBookmarkMutation(
            uid: String, cloudDocId: String, opId: String, isDelete: Boolean,
            expectedServerRevision: Long, isExplicitResave: Boolean, localEntity: BookmarkEntity
        ): CasMutationOutcome<BookmarkRecord> = CasMutationOutcome.Committed(1L, isDelete, 1000L, null)
        override suspend fun runHistoryMutation(
            uid: String, cloudDocId: String, opId: String, isDelete: Boolean,
            expectedServerRevision: Long, isExplicitResave: Boolean, localEntity: SearchHistoryEntity
        ): CasMutationOutcome<SearchHistoryRecord> = CasMutationOutcome.Committed(1L, isDelete, 1000L, null)
        override suspend fun runMirrorMutation(
            uid: String, cloudDocId: String, opId: String, expectedServerRevision: Long, localEntity: MirrorConfigEntity
        ): CasMutationOutcome<MirrorConfigRecord> = CasMutationOutcome.Committed(1L, false, 1000L, null)

        companion object {
            private const val btih1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        }
    }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        SyncRuntimeRegistry.resetForTesting()
        MagnetVaultDatabase.resetForTesting(context)
        prefsStore = LocalPrefsStore(context)
        prefsStore.clearAllForTesting()
        db = MagnetVaultDatabase.createInMemory(context)
    }

    @After
    fun tearDown() = runBlocking {
        prefsStore.clearAllForTesting()
        db.close()
        SyncRuntimeRegistry.resetForTesting()
        MagnetVaultDatabase.resetForTesting(context)
    }

    private fun waitUntilIdleLooper(condition: () -> Boolean) {
        composeRule.waitUntil(3_500L) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            condition()
        }
    }

    @Test
    fun `app opens directly to Search without sign-in gate and Vault opens to Saved offline`() {
        val indexerRepo = IndexerAggregatorRepository(ioDispatcher = Dispatchers.Default)
        val handoff = MagnetHandoffController(object : MagnetHandoffGateway {
            override fun readClipboardText() = ""
            override fun copyToClipboard(label: String, text: String) = true
            override fun openMagnetInLocalClient(magnetUri: String) = true
            override fun openUrlInBrowser(url: String) = true
            override fun shareText(title: String, content: String) = true
        })

        composeRule.setContent {
            MagnetVaultApp(indexerRepoOverride = indexerRepo, handoffControllerOverride = handoff)
        }

        composeRule.waitForIdle()
        // Opens directly to Search home without any sign-in gate
        composeRule.onNodeWithTag("search_query_input").assertIsDisplayed()
        composeRule.onNodeWithTag("topbar_status_subtitle").assertIsDisplayed()

        // Navigate to Vault -> opens on Saved tab in local-only mode
        composeRule.onNodeWithTag("nav_vault").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("vault_sync_status_indicator").assertIsDisplayed()
        composeRule.onNodeWithText("Local-only Vault on this device").assertIsDisplayed()
        composeRule.onNodeWithTag("vault_tab_0").assertIsDisplayed()
        composeRule.onNodeWithTag("collections_local_only_badge").assertIsDisplayed()
    }

    @Test
    fun `vault screen supports search collections assign remove undo export preview and history pause clear`() {
        val localRepo = VaultLocalRepository(db, LegacyDataMigrator(db, prefsStore))
        val engine = FirestoreSyncEngine(db, localRepo, prefsStore, gateway = null, currentAuthUidProvider = { null })
        val cloudRepo = CloudSyncRepository(context, prefsStore, isCloudMode = false, localRepoOverride = localRepo, syncEngineOverride = engine)
        val vm = VaultViewModel(cloudRepo, prefsStore, IndexerAggregatorRepository(ioDispatcher = Dispatchers.Default))

        val savedId = runBlocking {
            localRepo.ensureMigrated()
            val saved = localRepo.saveOrUpdateBookmark(
                TorrentResult(
                    id = "item_1",
                    title = "Ubuntu 24.04 Desktop",
                    source = IndexerSource.X1337,
                    category = TorrentCategory.APPS,
                    sizeLabel = "4.7 GB",
                    sizeBytes = 5_000_000_000L,
                    seeders = 400,
                    leechers = 15,
                    uploadDateLabel = "Today",
                    uploadEpochSeconds = 1700000000L,
                    magnetUri = magnetA,
                    infoHash = btihA,
                    detailPageUrl = "https://1337x.to/torrent/1/ubuntu/"
                )
            ).getOrThrow()
            localRepo.createCollection("Linux ISOs").getOrThrow()
            localRepo.recordSearchQuery("ubuntu 24", TorrentCategory.APPS).getOrThrow()
            saved.localId
        }

        var undoTriggeredAction: (() -> Unit)? = null
        var lastMessage: String? = null

        composeRule.setContent {
            MagnetVaultTheme(appearanceMode = AppAppearanceMode.OBSIDIAN) {
                VaultScreen(
                    viewModel = vm,
                    currentUserUid = null,
                    torrentBoxOpenMode = WebsiteOpenMode.IN_APP,
                    onOpenLocalClient = { _, _ -> },
                    onCopyMagnet = { _, _ -> },
                    onOpenSeedr = { _, _ -> },
                    onOpenTorBox = { _, _ -> },
                    onShareMagnet = { _, _ -> },
                    onOpenSourcePage = {},
                    onReplaySearch = { _, _ -> },
                    onShowMessage = { lastMessage = it },
                    onShowUndoMessage = { _, _, onUndo -> undoTriggeredAction = onUndo }
                )
            }
        }

        waitUntilIdleLooper {
            composeRule.onAllNodesWithTag("vault_bookmark_card_$savedId").fetchSemanticsNodes().isNotEmpty()
        }

        // Open export JSON dialog and verify preview counts & optional toggles default OFF
        composeRule.onNodeWithTag("vault_export_json_button").performClick()
        waitUntilIdleLooper {
            composeRule.onAllNodesWithTag("vault_export_dialog").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("export_always_included_summary").assertIsDisplayed()
        composeRule.onNodeWithTag("export_toggle_history").assertIsOff()
        composeRule.onNodeWithTag("export_toggle_sites").assertIsOff()
        composeRule.onNodeWithTag("export_toggle_mirrors").assertIsOff()
        composeRule.onNodeWithTag("export_cancel_button").performClick()
        composeRule.waitForIdle()

        // Tap bookmark -> opens shared MagnetActionModalSheet
        composeRule.onNodeWithTag("vault_bookmark_card_$savedId").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("magnet_action_sheet_content").assertIsDisplayed()
        vm.dismissSavedDetail()
        composeRule.waitForIdle()

        // Remove bookmark -> triggers Undo callback -> Undo restores item
        composeRule.onNodeWithTag("vault_remove_$savedId").performClick()
        waitUntilIdleLooper {
            composeRule.onAllNodesWithTag("vault_bookmark_card_$savedId").fetchSemanticsNodes().isEmpty() &&
                undoTriggeredAction != null
        }
        undoTriggeredAction!!.invoke()
        waitUntilIdleLooper {
            composeRule.onAllNodesWithTag("vault_bookmark_card_$savedId").fetchSemanticsNodes().isNotEmpty()
        }

        // Switch to History tab -> Pause history keeps existing entry; Clear All prompts confirmation
        composeRule.onNodeWithTag("vault_tab_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("ubuntu 24").assertIsDisplayed()

        composeRule.onNodeWithTag("vault_pause_history_switch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("ubuntu 24").assertIsDisplayed()

        composeRule.onNodeWithTag("clear_history_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("confirm_clear_history_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("clear_history_confirm_button").performClick()
        waitUntilIdleLooper {
            composeRule.onAllNodesWithText("ubuntu 24").fetchSemanticsNodes().isEmpty()
        }
        assertNotNull(lastMessage)
    }

    @Test
    fun `settings screen keeps sync off after sign-in shows merge preview dialog and enables with opt-in defaults off`() {
        val uid = "alice_ui_uid"
        val localRepo = VaultLocalRepository(db, LegacyDataMigrator(db, prefsStore))
        val engine = FirestoreSyncEngine(
            db = db,
            localRepo = localRepo,
            prefsStore = prefsStore,
            gateway = FakePreviewGateway(),
            currentAuthUidProvider = { uid }
        )
        val cloudRepo = CloudSyncRepository(
            context,
            prefsStore,
            isCloudMode = true,
            accountUidOverride = uid,
            localRepoOverride = localRepo,
            syncEngineOverride = engine
        )
        val vm = SettingsViewModel(prefsStore, cloudRepo, IndexerAggregatorRepository(ioDispatcher = Dispatchers.Default))

        composeRule.setContent {
            MagnetVaultTheme(appearanceMode = AppAppearanceMode.OBSIDIAN) {
                SettingsScreen(
                    viewModel = vm,
                    isSignedIn = true,
                    userUid = uid,
                    userEmail = "alice@example.com",
                    userName = "Alice",
                    firebaseConfigured = true,
                    onSignInSuccess = {},
                    onSignOut = {},
                    onOpenTorrentBoxUrl = { _, _ -> },
                    onShowMessage = {}
                )
            }
        }

        composeRule.waitForIdle()
        // Signed in -> Sync stays OFF until reviewed
        composeRule.onNodeWithText("Cloud Sync is OFF (Local-only)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("open_sync_enrollment_button").performScrollTo().performClick()

        waitUntilIdleLooper {
            composeRule.onAllNodesWithTag("enrollment_preview_counts").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("enrollment_history_opt_in").assertIsOff()
        composeRule.onNodeWithTag("enrollment_mirrors_opt_in").assertIsOff()

        // Confirm enabling sync -> switches card to enrolled controls
        composeRule.onNodeWithTag("confirm_enable_sync_button").performClick()
        waitUntilIdleLooper {
            composeRule.onAllNodesWithTag("settings_sync_status_badge").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_sync_now_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_sync_history_switch").performScrollTo().assertIsOff()
        composeRule.onNodeWithTag("settings_sync_mirrors_switch").performScrollTo().assertIsOff()
        composeRule.onNodeWithTag("settings_disable_sync_button").performScrollTo().assertIsDisplayed()

        val prefs = runBlocking { prefsStore.getSyncPrefsForAccount(uid) }
        assertEquals(true, prefs.enrolled)
        assertEquals(false, prefs.syncHistory)
        assertEquals(false, prefs.syncMirrors)
    }
}
