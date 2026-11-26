package com.example.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.auth.GoogleAuthManager
import com.example.data.local.SyncAccountPrefs
import com.example.data.network.DnsRuntimeStatus
import com.example.data.sync.SyncSessionStatus
import com.example.model.AppAppearanceMode
import com.example.model.CustomDnsPreset
import com.example.model.WebsiteOpenMode
import com.example.ui.theme.GlassCard
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryMonoStyle
import com.example.ui.theme.ambientGlassBackdrop
import com.example.util.IntentActions
import com.example.viewmodel.SettingsTransientState
import com.example.viewmodel.SettingsViewModel

/**
 * Reorganized Phase 3 Settings surface:
 * 1. Appearance
 * 2. Search sources
 * 3. Open with
 * 4. Privacy and DNS
 * 5. Account and sync
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    isSignedIn: Boolean,
    userUid: String?,
    userEmail: String?,
    userName: String?,
    firebaseConfigured: Boolean,
    onSignInSuccess: () -> Unit,
    onSignOut: () -> Unit,
    onOpenTorrentBoxUrl: (url: String, label: String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val appearanceMode by viewModel.appearanceMode.collectAsStateWithLifecycle()
    val searchHistoryEnabled by viewModel.searchHistoryEnabled.collectAsStateWithLifecycle()
    val websiteOpenMode by viewModel.websiteOpenMode.collectAsStateWithLifecycle()
    val torrentBoxOpenMode by viewModel.torrentBoxOpenMode.collectAsStateWithLifecycle()
    val customDnsConfig by viewModel.customDnsConfig.collectAsStateWithLifecycle()
    val dnsDiagnostics by viewModel.dnsDiagnostics.collectAsStateWithLifecycle()
    val mirrorConfigs by viewModel.mirrorConfigs.collectAsStateWithLifecycle()
    val transient by viewModel.transient.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val syncPrefs by viewModel.observeSyncPrefs(userUid).collectAsStateWithLifecycle(
        initialValue = SyncAccountPrefs()
    )

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val telemetry = LocalTelemetryColors.current

    var isSigningIn by remember { mutableStateOf(false) }
    var showEnrollmentDialog by remember { mutableStateOf(false) }
    var customDnsInput by remember(customDnsConfig.customEndpoint) {
        mutableStateOf(customDnsConfig.customEndpoint)
    }

    val segmentedColors = SegmentedButtonDefaults.colors(
        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        activeBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
        inactiveContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        inactiveBorderColor = telemetry.cardBorder.copy(alpha = 0.7f)
    )

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { onShowMessage(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .ambientGlassBackdrop(telemetry)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp)
            .testTag("settings_scroll_container"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Text(
                text = stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Appearance, search sources, external handoffs, privacy & local-first sync",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        // ============================================================
        // 1. APPEARANCE
        // ============================================================
        SettingsSectionHeader(
            title = "APPEARANCE",
            tag = "section_appearance_header"
        )
        GlassCard(
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.DarkMode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(21.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Theme Mode",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Choose System, Obsidian dark, or Alabaster light",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    AppAppearanceMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = appearanceMode == mode,
                            onClick = { viewModel.setAppearanceMode(mode) },
                            colors = segmentedColors,
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = AppAppearanceMode.entries.size
                            ),
                            modifier = Modifier.testTag("appearance_mode_${mode.name.lowercase()}")
                        ) {
                            Text(mode.label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Recent Searches",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Save submitted queries and display recent searches on Search home",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = searchHistoryEnabled,
                        onCheckedChange = viewModel::setSearchHistoryEnabled,
                        modifier = Modifier.testTag("settings_search_history_switch")
                    )
                }
            }
        }

        // ============================================================
        // 2. SEARCH SOURCES
        // ============================================================
        SettingsSectionHeader(
            title = "SEARCH SOURCES",
            tag = "section_search_sources_header"
        )
        MirrorManagerSection(
            mirrorConfigs = mirrorConfigs,
            healthMap = transient.mirrorHealthMap,
            isPingingAll = transient.isPingingAll,
            onPingAll = viewModel::pingAllMirrors,
            onPingSingle = { source, url -> viewModel.pingMirror(url, sourceHint = source) },
            onVerifyParser = viewModel::verifyMirrorParser,
            onToggleProvider = viewModel::toggleProviderEnabled,
            onAddMirror = viewModel::addMirrorUrl,
            onRemoveMirror = viewModel::removeMirrorUrl,
            onSetPrimaryMirror = viewModel::setPrimaryMirror,
            onPromoteFastest = viewModel::promoteFastestHealthyMirror,
            onResetDefaults = viewModel::resetMirrorsToDefaults
        )

        // ============================================================
        // 3. OPEN WITH
        // ============================================================
        SettingsSectionHeader(
            title = "OPEN WITH",
            tag = "section_open_with_header"
        )
        GlassCard(
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Open Indexer Websites In",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Choose how to open a torrent site HTML page when inspecting a release or recovering from a source error.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    WebsiteOpenMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = websiteOpenMode == mode,
                            onClick = { viewModel.setWebsiteOpenMode(mode) },
                            colors = segmentedColors,
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = WebsiteOpenMode.entries.size),
                            modifier = Modifier.testTag("website_mode_${mode.name.lowercase()}")
                        ) {
                            Text(mode.label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        GlassCard(
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.CloudQueue,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Cloud Torrent-Box (Seedr.cc & TorBox)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Seedr.cc & TorBox copy the magnet link for manual paste and open either In-App (preserving WebView cookies and Download Handoff) or in your Default Browser.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    WebsiteOpenMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = torrentBoxOpenMode == mode,
                            onClick = { viewModel.setTorrentBoxOpenMode(mode) },
                            colors = segmentedColors,
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = WebsiteOpenMode.entries.size
                            ),
                            modifier = Modifier.testTag("torrent_box_mode_${mode.name.lowercase()}")
                        ) {
                            Text(mode.label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onOpenTorrentBoxUrl(IntentActions.SEEDR_WEB_URL, "Seedr.cc") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("settings_open_seedr_button")
                    ) {
                        Text(stringResource(R.string.action_open_seedr))
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (torrentBoxOpenMode == WebsiteOpenMode.IN_APP) {
                                Icons.Outlined.Language
                            } else {
                                Icons.AutoMirrored.Filled.OpenInNew
                            },
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    FilledTonalButton(
                        onClick = { onOpenTorrentBoxUrl(IntentActions.TORBOX_WEB_URL, "TorBox") },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("settings_open_torbox_button")
                    ) {
                        Text(stringResource(R.string.action_open_torbox))
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (torrentBoxOpenMode == WebsiteOpenMode.IN_APP) {
                                Icons.Outlined.Language
                            } else {
                                Icons.AutoMirrored.Filled.OpenInNew
                            },
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }

        // ============================================================
        // 4. PRIVACY AND DNS
        // ============================================================
        SettingsSectionHeader(
            title = "PRIVACY AND DNS",
            tag = "section_privacy_dns_header"
        )
        GlassCard(
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("custom_dns_settings_card")
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "App-Wide Custom DNS (DoH)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )

                    val (dnsBadgeBg, dnsBadgeFg) = when (dnsDiagnostics.status) {
                        DnsRuntimeStatus.WORKING -> telemetry.seederGreenContainer to telemetry.seederGreen
                        DnsRuntimeStatus.BLOCKED -> telemetry.leecherAmberContainer to telemetry.leecherAmber
                        DnsRuntimeStatus.FALLBACK -> telemetry.leecherAmberContainer to telemetry.leecherAmber
                        DnsRuntimeStatus.INVALID_CONFIG -> telemetry.deadRoseContainer to telemetry.deadRose
                        DnsRuntimeStatus.SYSTEM_DEFAULT -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        color = dnsBadgeBg,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(0.75.dp, dnsBadgeFg.copy(alpha = 0.3f)),
                        modifier = Modifier.testTag("dns_runtime_status_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shield,
                                contentDescription = null,
                                tint = dnsBadgeFg,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = dnsDiagnostics.status.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = dnsBadgeFg,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Indexer searches resolve through encrypted DNS-over-HTTPS. In Sites WebView, MagnetVault applies DNS-based request filtering before loading subresources, and automatically falls back to System DNS when DoH is unreachable so searches keep working.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (dnsDiagnostics.blockedCount > 0 || dnsDiagnostics.fallbackCount > 0 || !dnsDiagnostics.lastFallbackReason.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = telemetry.glassSurfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(0.75.dp, telemetry.cardBorder),
                        modifier = Modifier.testTag("dns_runtime_telemetry_detail")
                    ) {
                        Text(
                            text = buildString {
                                append("Blocked requests: ${dnsDiagnostics.blockedCount} • Fallbacks: ${dnsDiagnostics.fallbackCount}")
                                dnsDiagnostics.lastFallbackReason?.let { reason ->
                                    append("\n")
                                    append(reason)
                                }
                            },
                            style = TelemetryMonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CustomDnsPreset.entries.forEach { preset ->
                        val selected = customDnsConfig.preset == preset
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.selectDnsPreset(preset) },
                            shape = RoundedCornerShape(10.dp),
                            label = {
                                Text(
                                    text = preset.displayName,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = telemetry.cardBorder.copy(alpha = 0.65f),
                                selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("dns_preset_${preset.name.lowercase()}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = customDnsConfig.preset.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (customDnsConfig.preset == CustomDnsPreset.CUSTOM) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customDnsInput,
                            onValueChange = { customDnsInput = it },
                            label = { Text("Custom HTTPS DoH URL or Host") },
                            placeholder = { Text("https://dns.nextdns.io/abc123") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = telemetry.glassSurfaceElevated,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = telemetry.cardBorder
                            ),
                            textStyle = TelemetryMonoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("custom_dns_endpoint_input")
                        )
                        Button(
                            onClick = { viewModel.saveCustomDnsEndpoint(customDnsInput) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .heightIn(min = 52.dp)
                                .testTag("save_custom_dns_button")
                        ) {
                            Text("Save")
                        }
                    }
                } else if (customDnsConfig.activeDohUrl.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = customDnsConfig.activeDohUrl,
                        style = TelemetryMonoStyle,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = viewModel::testActiveDns,
                        enabled = !transient.isTestingDns,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, telemetry.cardBorder),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("test_custom_dns_button")
                    ) {
                        if (transient.isTestingDns) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Testing…")
                        } else {
                            Text("Test DNS")
                        }
                    }
                    val testRes = transient.dnsTestResult
                    if (testRes != null) {
                        Text(
                            text = testRes.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = when (testRes.status) {
                                DnsRuntimeStatus.WORKING -> telemetry.seederGreen
                                DnsRuntimeStatus.SYSTEM_DEFAULT -> MaterialTheme.colorScheme.onSurfaceVariant
                                DnsRuntimeStatus.FALLBACK -> telemetry.leecherAmber
                                DnsRuntimeStatus.BLOCKED,
                                DnsRuntimeStatus.INVALID_CONFIG -> telemetry.deadRose
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dns_test_result_text")
                        )
                    }
                }
            }
        }

        GlassCard(
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("privacy_backup_policy_card")
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Backup,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Android Auto-Backup & Local Export Policy",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Automatic Android cloud backups and D2D device transfers exclude your Vault database, preferences, and WebView cookies/cache by policy. Use Vault → Export JSON for portable backups you control.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ============================================================
        // 5. ACCOUNT AND SYNC
        // ============================================================
        SettingsSectionHeader(
            title = "ACCOUNT AND SYNC",
            tag = "section_account_sync_header"
        )
        GlassCard(
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("account_sync_card")
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                if (isSignedIn && !userUid.isNullOrBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = userName?.ifBlank { "Google Account" } ?: "Google Account",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = userEmail ?: "Signed in",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = onSignOut,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, telemetry.cardBorder),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("sign_out_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.sign_out))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(thickness = 0.75.dp, color = telemetry.cardBorder.copy(alpha = 0.6f))
                    Spacer(modifier = Modifier.height(12.dp))

                    if (!syncPrefs.enrolled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.PhoneAndroid,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Cloud Sync is OFF (Local-only)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Signing in does not upload your Vault automatically. Review merge details before enabling sync.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                viewModel.loadEnrollmentPreview(userUid)
                                showEnrollmentDialog = true
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("open_sync_enrollment_button")
                        ) {
                            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.sync_review_enable))
                        }
                    } else {
                        val (statusIcon, statusLabel, statusColor) = when (val st = syncStatus) {
                            is SyncSessionStatus.Synced -> Triple(Icons.Filled.CloudDone, "Synced", telemetry.seederGreen)
                            is SyncSessionStatus.Pending -> Triple(
                                Icons.Filled.Sync,
                                if (st.isSyncing) "Syncing (${st.pendingCount})…" else "${st.pendingCount} pending",
                                MaterialTheme.colorScheme.primary
                            )
                            is SyncSessionStatus.Offline -> Triple(
                                Icons.Filled.CloudOff,
                                "Offline • ${st.pendingCount} queued",
                                MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            is SyncSessionStatus.ActionNeeded -> Triple(
                                Icons.Filled.WarningAmber,
                                "Attention needed (${st.errorCategory})",
                                telemetry.deadRose
                            )
                            SyncSessionStatus.LocalOnly -> Triple(
                                Icons.Outlined.PhoneAndroid,
                                "Local-only",
                                MaterialTheme.colorScheme.tertiary
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Surface(
                                color = statusColor.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(0.75.dp, statusColor.copy(alpha = 0.35f)),
                                modifier = Modifier.testTag("settings_sync_status_badge")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = statusLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = statusColor,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            FilledTonalButton(
                                onClick = { viewModel.syncNowOrRetry(userUid) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("settings_sync_now_button")
                            ) {
                                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sync Now")
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Bookmarks sync automatically. Collections & Custom Sites always remain local-only.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Sync Search History (Optional)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Off by default. Sync submitted search queries for this account.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = syncPrefs.syncHistory,
                                onCheckedChange = { enabled -> viewModel.setHistorySyncOptIn(userUid, enabled) },
                                modifier = Modifier.testTag("settings_sync_history_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Sync Mirror Configs (Optional)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Off by default. Sync enabled sources & custom mirror lists.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = syncPrefs.syncMirrors,
                                onCheckedChange = { enabled -> viewModel.setMirrorsSyncOptIn(userUid, enabled) },
                                modifier = Modifier.testTag("settings_sync_mirrors_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { viewModel.disableSync(userUid) },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, telemetry.cardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("settings_disable_sync_button")
                        ) {
                            Text("Turn Off Cloud Sync (Keep Local Vault)")
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Local Vault (Signed Out)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (firebaseConfigured) {
                                    "Everything is saved locally on this device. Sign in to Google when you want optional cloud sync."
                                } else {
                                    "Firebase sync is not configured in this build. Your Vault, collections, history & mirrors run 100% locally."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (firebaseConfigured) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                isSigningIn = true
                                GoogleAuthManager.onGoogleSignInClicked(
                                    context = context,
                                    credentialManager = credentialManager,
                                    scope = scope,
                                    onAuthSuccess = {
                                        isSigningIn = false
                                        onSignInSuccess()
                                    },
                                    onAuthError = { err ->
                                        isSigningIn = false
                                        onShowMessage(err)
                                    },
                                    onAuthCancelled = {
                                        isSigningIn = false
                                    }
                                )
                            },
                            enabled = !isSigningIn,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("settings_sign_in_button")
                        ) {
                            if (isSigningIn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Signing in…")
                            } else {
                                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.sign_in_google))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEnrollmentDialog && !userUid.isNullOrBlank()) {
        SyncEnrollmentDialog(
            userName = userName,
            userEmail = userEmail,
            transient = transient,
            onDismiss = { showEnrollmentDialog = false },
            onConfirmEnable = { syncHistoryOptIn, syncMirrorsOptIn ->
                viewModel.confirmEnableSync(
                    accountUid = userUid,
                    syncHistory = syncHistoryOptIn,
                    syncMirrors = syncMirrorsOptIn
                ) {
                    showEnrollmentDialog = false
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String, tag: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(top = 4.dp)
            .testTag(tag)
    )
}

@Composable
private fun SyncEnrollmentDialog(
    userName: String?,
    userEmail: String?,
    transient: SettingsTransientState,
    onDismiss: () -> Unit,
    onConfirmEnable: (syncHistory: Boolean, syncMirrors: Boolean) -> Unit
) {
    var syncHistory by remember { mutableStateOf(false) }
    var syncMirrors by remember { mutableStateOf(false) }
    val telemetry = LocalTelemetryColors.current

    AlertDialog(
        onDismissRequest = { if (!transient.isApplyingEnrollment) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Enable Cloud Sync?") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.testTag("sync_enrollment_dialog")
            ) {
                Text(
                    text = "Destination account: ${userName?.ifBlank { userEmail } ?: userEmail ?: "Google Account"}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Bookmarks sync when enabled. Collections & Custom Sites always remain local-only on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    color = telemetry.glassSurface,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.75.dp, telemetry.cardBorder)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "INITIAL BOOKMARK MERGE PREVIEW",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        when {
                            transient.isLoadingPreview -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Comparing local and cloud bookmarks…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            transient.enrollmentPreview != null -> {
                                val p = transient.enrollmentPreview
                                Text(
                                    text = "Device: ${p.localBookmarkCount} (${p.newToUploadCount} to upload)\nCloud: ${p.cloudBookmarkCount} (${p.newToDownloadCount} to pull)\nMatching releases: ${p.overlapCount}",
                                    style = TelemetryMonoStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("enrollment_preview_counts")
                                )
                            }
                            transient.enrollmentPreviewError != null -> {
                                Text(
                                    text = "Preview unavailable (${transient.enrollmentPreviewError}). Offline bookmarks will merge safely once connected.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sync Search History", style = MaterialTheme.typography.bodyMedium)
                        Text("Optional • Off by default", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = syncHistory,
                        onCheckedChange = { syncHistory = it },
                        modifier = Modifier.testTag("enrollment_history_opt_in")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sync Mirror Configs", style = MaterialTheme.typography.bodyMedium)
                        Text("Optional • Off by default", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = syncMirrors,
                        onCheckedChange = { syncMirrors = it },
                        modifier = Modifier.testTag("enrollment_mirrors_opt_in")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmEnable(syncHistory, syncMirrors) },
                enabled = !transient.isApplyingEnrollment,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_enable_sync_button")
            ) {
                if (transient.isApplyingEnrollment) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Enabling…")
                } else {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Enable Sync")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !transient.isApplyingEnrollment,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cancel_enable_sync_button")
            ) {
                Text("Cancel")
            }
        }
    )
}
