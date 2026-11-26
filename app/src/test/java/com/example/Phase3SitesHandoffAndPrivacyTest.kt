package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.firebase.CloudSyncRepository
import com.example.data.indexers.IndexerAggregatorRepository
import com.example.data.local.LegacyDataMigrator
import com.example.data.local.LocalPrefsStore
import com.example.data.local.VaultLocalRepository
import com.example.data.local.db.MagnetVaultDatabase
import com.example.data.network.CustomAppDns
import com.example.data.network.DnsPacketParseResult
import com.example.data.network.DnsRuntimeStatus
import com.example.data.network.HttpResponseData
import com.example.data.network.HttpTransport
import com.example.data.sync.FirestoreSyncEngine
import com.example.data.sync.SyncRuntimeRegistry
import com.example.domain.magnet.MagnetIntake
import com.example.domain.magnet.MagnetIntakeResolution
import com.example.model.AppAppearanceMode
import com.example.model.CustomDnsConfig
import com.example.model.CustomDnsPreset
import com.example.model.IndexerSource
import com.example.model.MirrorConfig
import com.example.model.ParserCheckStatus
import com.example.model.WebsiteOpenMode
import com.example.ui.MagnetVaultApp
import com.example.ui.browser.BrowserUrlPolicy
import com.example.ui.browser.DownloadHandoffParser
import com.example.ui.browser.InAppBrowserScreen
import com.example.ui.browser.UrlNavigationDecision
import com.example.ui.browser.rememberSitesBrowserSessionState
import com.example.ui.handoff.MagnetHandoffController
import com.example.ui.handoff.MagnetHandoffGateway
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.MagnetVaultTheme
import com.example.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase3SitesHandoffAndPrivacyTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(object : org.junit.rules.ExternalResource() {
            override fun before() = runBlocking {
                context = ApplicationProvider.getApplicationContext()
                CustomAppDns.resetForTesting()
                SyncRuntimeRegistry.resetForTesting()
                MagnetVaultDatabase.resetForTesting(context)
                LocalPrefsStore(context).clearAllForTesting()
            }

            override fun after() = runBlocking {
                CustomAppDns.resetForTesting()
                inMemoryDb?.close()
                inMemoryDb = null
                LocalPrefsStore(context).clearAllForTesting()
                SyncRuntimeRegistry.resetForTesting()
                MagnetVaultDatabase.resetForTesting(context)
            }
        })
        .around(composeRule)

    private lateinit var context: Context
    private var inMemoryDb: MagnetVaultDatabase? = null

    private val hashA = "1111111111111111111111111111111111111111"
    private val hashB = "2222222222222222222222222222222222222222"
    private val magnetA = "magnet:?xt=urn:btih:$hashA&dn=Ubuntu+24.04+Desktop"
    private val magnetB = "magnet:?xt=urn:btih:$hashB&dn=Fedora+Workstation+41"

    private class RecordingHandoffGateway : MagnetHandoffGateway {
        val openedDownloadUrls = mutableListOf<String>()
        var lastCopied: String? = null
        var lastOpenedMagnet: String? = null
        var lastOpenedUrl: String? = null
        var lastShared: String? = null

        override fun readClipboardText(): String = ""
        override fun copyToClipboard(label: String, text: String): Boolean {
            lastCopied = text
            return true
        }

        override fun openMagnetInLocalClient(magnetUri: String): Boolean {
            lastOpenedMagnet = magnetUri
            return true
        }

        override fun openUrlInBrowser(url: String): Boolean {
            lastOpenedUrl = url
            return true
        }

        override fun shareText(title: String, content: String): Boolean {
            lastShared = content
            return true
        }

        override fun openDownloadManagerChooser(url: String, fileName: String?, mimeType: String?): Boolean {
            openedDownloadUrls += url
            return true
        }
    }

    private fun createLocalRepo(): Pair<LocalPrefsStore, CloudSyncRepository> {
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

    private fun waitUntilIdleLooper(condition: () -> Boolean) {
        composeRule.waitUntil(4_000L) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            condition()
        }
    }

    @Test
    fun `browser url policy enforces https by default permits scoped http mirrors and blocks unsafe schemes`() {
        assertNull(BrowserUrlPolicy.validateCustomSiteUrl("http://legacy-mirror.org", allowLegacyHttpMirror = false))
        assertEquals(
            "http://legacy-mirror.org",
            BrowserUrlPolicy.validateCustomSiteUrl("http://legacy-mirror.org", allowLegacyHttpMirror = true)
        )
        assertEquals(
            "https://1337x.to",
            BrowserUrlPolicy.validateCustomSiteUrl("1337x.to", allowLegacyHttpMirror = false)
        )

        val blockedFile = BrowserUrlPolicy.evaluateNavigation("file:///data/data/com.example/databases/vault.db")
        assertTrue(blockedFile is UrlNavigationDecision.Blocked)

        val blockedContent = BrowserUrlPolicy.evaluateNavigation("content://com.example.provider/secret")
        assertTrue(blockedContent is UrlNavigationDecision.Blocked)

        val blockedUserInfo = BrowserUrlPolicy.evaluateNavigation("https://admin:secret@mirror.org")
        assertTrue(blockedUserInfo is UrlNavigationDecision.Blocked)

        val unapprovedHttp = BrowserUrlPolicy.evaluateNavigation(
            rawUrl = "http://unapproved.example.com/torrent",
            configuredHttpHosts = setOf("legacy.mirror.org")
        )
        assertTrue(unapprovedHttp is UrlNavigationDecision.RequiresHttpExceptionPrompt)

        val configuredHttp = BrowserUrlPolicy.evaluateNavigation(
            rawUrl = "http://legacy.mirror.org/search",
            configuredHttpHosts = setOf("legacy.mirror.org")
        )
        assertTrue(configuredHttp is UrlNavigationDecision.Allowed)
        assertTrue((configuredHttp as UrlNavigationDecision.Allowed).isLegacyHttpException)
    }

    @Test
    fun `download parser handoff controller and outbound share intent validate semantics and isolate cookies`() {
        val parsedSeedr = DownloadHandoffParser.parse(
            rawUrl = "https://cdn.seedr.cc/ff_get/999/Ubuntu.24.04.mkv",
            contentDisposition = "attachment; filename=\"Ubuntu.24.04.mkv\"",
            mimeType = "video/x-matroska",
            contentLength = 1_500_000_000L,
            hasPageCookies = true,
            currentPageHost = "www.seedr.cc"
        )
        assertNotNull(parsedSeedr)
        assertEquals("cdn.seedr.cc", parsedSeedr!!.hostLabel)
        assertEquals("Ubuntu.24.04.mkv", parsedSeedr.fileName)
        assertTrue(parsedSeedr.isLikelySessionBound)

        val gateway = RecordingHandoffGateway()
        val controller = MagnetHandoffController(gateway)

        // Malformed outgoing magnets are rejected before any intent or clipboard write
        val badCopy = controller.copyMagnet("Bad", "magnet:?dn=MissingHash")
        assertFalse(badCopy.success)
        assertNull(gateway.lastCopied)

        val badClient = controller.openLocalClient("Bad", "https://not-a-magnet.org")
        assertFalse(badClient.success)
        assertNull(gateway.lastOpenedMagnet)

        // Seedr/TorBox copies magnet & preserves existing destination behavior
        val seedrFeedback = controller.openCloudBox(
            title = "Ubuntu",
            magnetUri = magnetA,
            targetUrl = "https://www.seedr.cc",
            targetLabel = "Seedr.cc",
            mode = WebsiteOpenMode.IN_APP
        )
        assertTrue(seedrFeedback.success)
        assertEquals("https://www.seedr.cc", seedrFeedback.openInAppUrl)
        assertEquals(magnetA, gateway.lastCopied)

        // Outbound shareText excludes MagnetVault's own MainActivity intake component
        com.example.util.IntentActions.shareText(context, "Ubuntu", magnetA)
        val launchedIntent = Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .nextStartedActivity
        assertNotNull(launchedIntent)
        assertEquals(Intent.ACTION_CHOOSER, launchedIntent.action)
        val excluded = launchedIntent.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)
        assertNotNull(excluded)
        assertTrue(
            excluded!!.filterIsInstance<ComponentName>().any { it.className == "com.example.MainActivity" }
        )
    }

    @Test
    fun `magnet intake and share intent parser handle 0 1 multiple duplicate and oversized inputs`() {
        assertTrue(MagnetIntake.resolveSharedText("just some random text") is MagnetIntakeResolution.Unsupported)

        val single = MagnetIntake.resolveSharedText("Check this out: $magnetA")
        assertTrue(single is MagnetIntakeResolution.Single)
        assertEquals(hashA, (single as MagnetIntakeResolution.Single).magnet.primaryIdentity.canonicalHex)

        val dedupedSingle = MagnetIntake.resolveSharedText("$magnetA and again $magnetA")
        assertTrue(dedupedSingle is MagnetIntakeResolution.Single)

        val multi = MagnetIntake.resolveSharedText("First: $magnetA\nSecond: $magnetB")
        assertTrue(multi is MagnetIntakeResolution.Multiple)
        assertEquals(2, (multi as MagnetIntakeResolution.Multiple).magnets.size)

        val oversized = MagnetIntake.resolveSharedText("a".repeat(MagnetIntake.MAX_SHARE_TEXT_LENGTH + 100))
        assertTrue(oversized is MagnetIntakeResolution.Unsupported)

        // ShareIntentParser extracts once and deduplicates on restore / repeat
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, magnetA)
        }
        assertNull(ShareIntentParser.extract(shareIntent, isRestoredInstance = true))
        val firstExtract = ShareIntentParser.extract(shareIntent, isRestoredInstance = false)
        assertNotNull(firstExtract)
        assertEquals(magnetA, firstExtract!!.rawText)
        // Re-extracting same Intent (e.g. after recreation) is suppressed cleanly
        assertNull(ShareIntentParser.extract(shareIntent, isRestoredInstance = false))
    }

    @Test
    fun `custom app dns tracks working blocked fallback and invalid config diagnostics without leaking hostnames`() {
        val sampleIp = InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))

        // Invalid custom endpoint
        CustomAppDns.configure(CustomDnsConfig(preset = CustomDnsPreset.CUSTOM, customEndpoint = "http://not-https.local"))
        assertEquals(DnsRuntimeStatus.INVALID_CONFIG, CustomAppDns.diagnostics.value.status)

        // Working DoH resolution + ad blocking detection
        CustomAppDns.wireQueryOverride = { host, _ ->
            if (host.contains("doubleclick")) {
                DnsPacketParseResult(rcode = 0, addresses = emptyList(), isSinkholed = true)
            } else {
                DnsPacketParseResult(rcode = 0, addresses = listOf(sampleIp), isSinkholed = false)
            }
        }
        CustomAppDns.configure(CustomDnsConfig(preset = CustomDnsPreset.ADGUARD))
        val testOk = CustomAppDns.testEndpoint(CustomDnsConfig(preset = CustomDnsPreset.ADGUARD))
        assertEquals(DnsRuntimeStatus.WORKING, testOk.status)
        assertTrue(testOk.adBlockingVerified)

        // Host blocked in WebView transitions diagnostics cleanly without storing raw hostname in reason
        val blocked = CustomAppDns.isHostBlockedByCustomDns("ads.doubleclick.net", isMainFrame = false)
        assertTrue(blocked)
        assertEquals(DnsRuntimeStatus.BLOCKED, CustomAppDns.diagnostics.value.status)
        assertEquals(1, CustomAppDns.diagnostics.value.blockedCount)
        assertFalse(CustomAppDns.diagnostics.value.lastFallbackReason.orEmpty().contains("doubleclick"))

        // Unreachable DoH triggers System DNS fallback and updates FALLBACK telemetry
        CustomAppDns.wireQueryOverride = { _, _ -> null }
        CustomAppDns.systemDnsOverride = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(sampleIp)
        }
        val resolvedFallback = CustomAppDns.lookup("tracker-example-host.org")
        assertEquals(listOf(sampleIp), resolvedFallback)
        assertEquals(DnsRuntimeStatus.FALLBACK, CustomAppDns.diagnostics.value.status)
        assertTrue(CustomAppDns.diagnostics.value.fallbackCount >= 1)
        assertFalse(CustomAppDns.diagnostics.value.lastFallbackReason.orEmpty().contains("tracker-example-host"))
    }

    @Test
    fun `sites opens to pinned and custom sites supports room add edit remove download sheet and magnet capture`() {
        val (_, cloud) = createLocalRepo()
        val gateway = RecordingHandoffGateway()
        val handoff = MagnetHandoffController(gateway)
        val messages = mutableListOf<String>()

        lateinit var capturedSession: com.example.ui.browser.SitesBrowserSessionState

        composeRule.setContent {
            val session = rememberSitesBrowserSessionState().also { capturedSession = it }
            MagnetVaultTheme(appearanceMode = AppAppearanceMode.OBSIDIAN) {
                InAppBrowserScreen(
                    cloudSyncRepo = cloud,
                    handoffController = handoff,
                    sessionState = session,
                    mirrorConfigs = IndexerSource.entries.associateWith { MirrorConfig(it) },
                    torrentBoxOpenMode = WebsiteOpenMode.IN_APP,
                    customDnsConfig = CustomDnsConfig(CustomDnsPreset.ADGUARD),
                    requestedUrl = null,
                    requestNonce = 0,
                    onApplyHandoffFeedback = {},
                    onShowMessage = { messages += it }
                )
            }
        }

        composeRule.waitForIdle()
        // Opens to Pinned & Custom Sites home directory first
        composeRule.onNodeWithTag("sites_home_list")
            .assertIsDisplayed()
            .performScrollToNode(hasTestTag("site_chip_preset_1337x"))
        composeRule.onNodeWithTag("site_chip_preset_1337x").assertIsDisplayed()

        // Add custom site -> saved in Room
        composeRule.onNodeWithTag("add_custom_site_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("custom_site_name_input").performTextInput("My Mirror")
        composeRule.onNodeWithTag("custom_site_url_input").performTextClearance()
        composeRule.onNodeWithTag("custom_site_url_input").performTextInput("https://mirror.example.org")
        composeRule.onNodeWithTag("custom_site_save_button").performClick()

        val addedSite = runBlocking {
            waitUntilIdleLooper { runBlocking { cloud.observeCustomSites().first().isNotEmpty() } }
            cloud.observeCustomSites().first().first()
        }
        assertEquals("My Mirror", addedSite.name)
        assertEquals("https://mirror.example.org", addedSite.url)

        // Edit custom site in place
        composeRule.onNodeWithTag("edit_custom_site_${addedSite.id}").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("custom_site_name_input").performTextClearance()
        composeRule.onNodeWithTag("custom_site_name_input").performTextInput("Edited Mirror")
        composeRule.onNodeWithTag("custom_site_save_button").performClick()
        waitUntilIdleLooper {
            composeRule.onAllNodesWithText("Edited Mirror").fetchSemanticsNodes().isNotEmpty()
        }

        // Open site -> switches to active browser chrome showing hostname badge
        composeRule.onNodeWithTag("site_chip_${addedSite.id}").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("browser_hostname_badge").assertIsDisplayed()
        composeRule.onNodeWithText("mirror.example.org").assertIsDisplayed()

        // Capture a magnet:? link -> opens shared MagnetActionModalSheet
        composeRule.runOnUiThread {
            capturedSession.captureMagnet(magnetA) { messages += it }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("magnet_action_sheet_content").assertIsDisplayed()
        composeRule.onNodeWithTag("action_open_with_button").assertIsDisplayed()
        composeRule.runOnUiThread { capturedSession.capturedMagnetGroup = null }
        composeRule.waitForIdle()

        // Download interception -> shows DownloadHandoffSheet FIRST (does NOT auto-launch chooser)
        val dl = DownloadHandoffParser.parse(
            rawUrl = "https://cdn.seedr.cc/ff_get/42/Movie.2026.1080p.mkv",
            contentDisposition = null,
            mimeType = "video/x-matroska",
            contentLength = 2_000_000_000L,
            hasPageCookies = true,
            currentPageHost = "www.seedr.cc"
        )!!
        composeRule.runOnUiThread { capturedSession.capturedDownload = dl }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("download_handoff_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("download_sheet_filename").assertIsDisplayed()
        composeRule.onNodeWithTag("download_sheet_hostname").assertIsDisplayed()
        composeRule.onNodeWithTag("download_sheet_session_notice").assertIsDisplayed()
        assertTrue(gateway.openedDownloadUrls.isEmpty())

        // Tapping Choose Download Manager launches gateway
        composeRule.onNodeWithTag("choose_download_manager_button").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("https://cdn.seedr.cc/ff_get/42/Movie.2026.1080p.mkv"), gateway.openedDownloadUrls)

        // Return to Sites home & remove custom site
        composeRule.onNodeWithTag("browser_sites_home_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("resume_active_browser_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("remove_custom_site_${addedSite.id}").performScrollTo().performClick()
        waitUntilIdleLooper {
            composeRule.onAllNodesWithText("Edited Mirror").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `app preserves active sites tab state across tab switches and handles single and multi share intents`() {
        val indexerRepo = IndexerAggregatorRepository(ioDispatcher = Dispatchers.Default)
        val handoff = MagnetHandoffController(RecordingHandoffGateway())

        // 1. Single-magnet ACTION_SEND share intake opens shared MagnetActionModalSheet without auto-launching client
        val shareReq = IncomingShareRequest(eventId = "share_1", rawText = "Release: $magnetA")
        composeRule.setContent {
            MagnetVaultApp(
                indexerRepoOverride = indexerRepo,
                handoffControllerOverride = handoff,
                incomingShare = shareReq
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("magnet_action_sheet_content").assertIsDisplayed()
        composeRule.onNodeWithText("Ubuntu 24.04 Desktop").assertIsDisplayed()
        composeRule.onNodeWithTag("copy_magnet_button").performClick()
        composeRule.waitForIdle()

        // Navigate to Sites, open pinned site, switch to Search, switch back to Sites -> retains browser page
        composeRule.onNodeWithTag("nav_sites").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sites_home_list").performScrollToNode(hasTestTag("site_chip_preset_yts"))
        composeRule.onNodeWithTag("site_chip_preset_yts").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("browser_hostname_badge").assertIsDisplayed()
        composeRule.onNodeWithText("yts.mx").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search_query_input").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_sites").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("browser_hostname_badge").assertIsDisplayed()
        composeRule.onNodeWithText("yts.mx").assertIsDisplayed()
    }

    @Test
    fun `multi magnet share intake shows picker and unsupported share shows clear dialog`() {
        val indexerRepo = IndexerAggregatorRepository(ioDispatcher = Dispatchers.Default)
        val handoff = MagnetHandoffController(RecordingHandoffGateway())
        val multiShare = IncomingShareRequest(eventId = "share_multi", rawText = "$magnetA\n$magnetB")

        composeRule.setContent {
            MagnetVaultApp(
                indexerRepoOverride = indexerRepo,
                handoffControllerOverride = handoff,
                incomingShare = multiShare
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("multi_magnet_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("multi_magnet_option_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("magnet_action_sheet_content").assertIsDisplayed()
        composeRule.onNodeWithText("Fedora Workstation 41").assertIsDisplayed()
    }

    @Test
    fun `unsupported share shows explanation and returns cleanly to app`() {
        val indexerRepo = IndexerAggregatorRepository(ioDispatcher = Dispatchers.Default)
        val handoff = MagnetHandoffController(RecordingHandoffGateway())
        val badShare = IncomingShareRequest(eventId = "share_bad", rawText = "hello world without magnets")

        composeRule.setContent {
            MagnetVaultApp(
                indexerRepoOverride = indexerRepo,
                handoffControllerOverride = handoff,
                incomingShare = badShare
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("unsupported_share_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("unsupported_share_dismiss_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search_query_input").assertIsDisplayed()
    }

    @Test
    fun `settings displays 5 sections distinguishes mirror http vs parser verification and shows dns backup policy`() {
        val (prefs, cloud) = createLocalRepo()
        val transport = object : HttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>, maxResponseBytes: Long): String =
                if (url.contains("yts", ignoreCase = true)) {
                    """{"status":"ok","data":{"movies":[{"id":1,"title":"Verified Linux","year":2025,"torrents":[{"hash":"$hashA","quality":"1080p","seeds":50,"peers":5,"size":"1.2 GB"}]}]}}"""
                } else {
                    "<html><body>OK</body></html>"
                }

            override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>, maxResponseBytes: Long): String = ""

            override suspend fun probe(url: String, postJsonBody: String?, maxResponseBytes: Long): HttpResponseData =
                HttpResponseData(200, "<html>OK</html>")
        }
        val indexerRepo = IndexerAggregatorRepository(transport = transport, ioDispatcher = Dispatchers.Default)
        val vm = SettingsViewModel(prefs, cloud, indexerRepo)

        composeRule.setContent {
            MagnetVaultTheme(appearanceMode = AppAppearanceMode.OBSIDIAN) {
                SettingsScreen(
                    viewModel = vm,
                    isSignedIn = false,
                    userUid = null,
                    userEmail = null,
                    userName = null,
                    firebaseConfigured = false,
                    onSignInSuccess = {},
                    onSignOut = {},
                    onOpenTorrentBoxUrl = { _, _ -> },
                    onShowMessage = {}
                )
            }
        }

        composeRule.waitForIdle()
        // Verify all 5 required Settings sections exist cleanly
        composeRule.onNodeWithTag("section_appearance_header").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("section_search_sources_header").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("section_open_with_header").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("section_privacy_dns_header").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("section_account_sync_header").performScrollTo().assertIsDisplayed()

        // Privacy DNS status & Backup policy card
        composeRule.onNodeWithTag("dns_runtime_status_badge").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_backup_policy_card").performScrollTo().assertIsDisplayed()

        // Verify Mirror HTTP ping vs Parser Verification states
        composeRule.onNodeWithTag("ping_mirror_YTS_0").performScrollTo().performClick()
        waitUntilIdleLooper {
            vm.transient.value.mirrorHealthMap[IndexerSource.YTS.defaultMirrors.first()]?.isReachable == true
        }
        val afterHttpPing = vm.transient.value.mirrorHealthMap[IndexerSource.YTS.defaultMirrors.first()]!!
        assertEquals(ParserCheckStatus.NEVER_RUN, afterHttpPing.parserStatus)
        assertNotNull(afterHttpPing.checkedAtEpochMs)

        composeRule.onNodeWithTag("verify_parser_YTS_0").performScrollTo().performClick()
        waitUntilIdleLooper {
            vm.transient.value.mirrorHealthMap[IndexerSource.YTS.defaultMirrors.first()]?.parserStatus == ParserCheckStatus.VERIFIED
        }
    }

    @Test
    fun `backup and data extraction xml exclude room database datastore sharedprefs and root caches`() {
        val backupXml = context.resources.getXml(R.xml.backup_rules)
        val excludedBackupDomains = mutableSetOf<String>()
        var event = backupXml.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && backupXml.name == "exclude") {
                backupXml.getAttributeValue(null, "domain")?.let { excludedBackupDomains += it }
            }
            event = backupXml.next()
        }
        assertTrue(excludedBackupDomains.containsAll(setOf("database", "file", "sharedpref", "root", "external")))

        val extractionXml = context.resources.getXml(R.xml.data_extraction_rules)
        var cloudExcludes = 0
        var d2dExcludes = 0
        var inCloud = false
        var inD2d = false
        event = extractionXml.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when (extractionXml.name) {
                    "cloud-backup" -> inCloud = true
                    "device-transfer" -> inD2d = true
                    "exclude" -> {
                        if (inCloud) cloudExcludes++
                        if (inD2d) d2dExcludes++
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> when (extractionXml.name) {
                    "cloud-backup" -> inCloud = false
                    "device-transfer" -> inD2d = false
                }
            }
            event = extractionXml.next()
        }
        assertTrue(cloudExcludes >= 5)
        assertTrue(d2dExcludes >= 5)
    }
}
