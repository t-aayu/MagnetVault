package com.example.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.example.model.CustomDnsPreset
import com.example.model.WebsiteOpenMode
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryMonoStyle
import com.example.util.IntentActions
import com.example.viewmodel.SettingsViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    isSignedIn: Boolean,
    userEmail: String?,
    userName: String?,
    onSignInSuccess: () -> Unit,
    onSignOut: () -> Unit,
    onOpenTorrentBoxUrl: (url: String, label: String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
    val websiteOpenMode by viewModel.websiteOpenMode.collectAsStateWithLifecycle()
    val torrentBoxOpenMode by viewModel.torrentBoxOpenMode.collectAsStateWithLifecycle()
    val customDnsConfig by viewModel.customDnsConfig.collectAsStateWithLifecycle()
    val mirrorConfigs by viewModel.mirrorConfigs.collectAsStateWithLifecycle()
    val transient by viewModel.transient.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val telemetry = LocalTelemetryColors.current

    var isSigningIn by remember { mutableStateOf(false) }
    var customDnsInput by remember(customDnsConfig.customEndpoint) {
        mutableStateOf(customDnsConfig.customEndpoint)
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { onShowMessage(it) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "settings_header") {
            Column {
                Text(
                    text = stringResource(R.string.nav_settings),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Optional cloud sync, browser shortcuts & live mirror health",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // 1. Account / Local Mode Card
        item(key = "account_card") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, telemetry.cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSignedIn) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(38.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = userName?.ifBlank { "Google Account" } ?: "Google Account",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = userEmail ?: "Cloud sync active",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = onSignOut,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("sign_out_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.sign_out))
                        }
                    }
                } else {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.AccountCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Local Mode (No Sign-In)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Bookmarks, history & mirror overrides stay on this device. Sign in to enable cloud sync.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
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
                                .height(48.dp)
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
                                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.sign_in_google))
                            }
                        }
                    }
                }
            }
        }

        // 2. Appearance Card
        item(key = "appearance_card") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, telemetry.cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.DarkMode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Obsidian Dark Theme",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Toggle between dark utility and crisp alabaster light",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isDarkTheme,
                        onCheckedChange = viewModel::setDarkTheme,
                        modifier = Modifier.testTag("settings_dark_theme_switch")
                    )
                }
            }
        }

        // 2B. Website Opening Preference (In-App HTML View vs Default Browser)
        item(key = "website_open_mode_card") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, telemetry.cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Language,
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
                        text = "Choose how to open a torrent site as an HTML page when an indexer returns an error or when inspecting a release page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        WebsiteOpenMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = websiteOpenMode == mode,
                                onClick = { viewModel.setWebsiteOpenMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = WebsiteOpenMode.entries.size),
                                modifier = Modifier.testTag("website_mode_${mode.name.lowercase()}")
                            ) {
                                Text(mode.label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }

        // 2C. App-Wide Custom DNS (DNS-over-HTTPS Ad-Blocking for Sites & Searches)
        item(key = "custom_dns_card") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, telemetry.cardBorder),
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
                            imageVector = Icons.Filled.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "App-Wide Custom DNS (Ad-Blocker)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (customDnsConfig.isEnabled && customDnsConfig.preset.blocksAds) {
                            Surface(
                                color = telemetry.seederGreenContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Shield,
                                        contentDescription = null,
                                        tint = telemetry.seederGreen,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "AD-FREE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = telemetry.seederGreen,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Routes app searches and in-app websites through encrypted DNS-over-HTTPS so ad, tracker, and pop-under domains are blocked automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                                label = {
                                    Text(
                                        text = preset.displayName,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier.testTag("dns_preset_${preset.name.lowercase()}")
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
                                textStyle = TelemetryMonoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("custom_dns_endpoint_input")
                            )
                            Button(
                                onClick = { viewModel.saveCustomDnsEndpoint(customDnsInput) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .height(52.dp)
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
                            color = MaterialTheme.colorScheme.primary
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
                            modifier = Modifier
                                .height(44.dp)
                                .testTag("test_custom_dns_button")
                        ) {
                            if (transient.isTestingDns) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
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
                                color = when {
                                    !testRes.reachable -> telemetry.deadRose
                                    testRes.adBlockingVerified -> telemetry.seederGreen
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 3. Seedr.cc & TorBox Quick Launchers + In-App vs Default Browser Option
        item(key = "browser_shortcuts") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, telemetry.cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Seedr.cc & TorBox",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Choose whether Seedr.cc & TorBox open inside the app (with download manager chooser for IDM/1DM/ADM/Browser) or in your default browser.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        WebsiteOpenMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = torrentBoxOpenMode == mode,
                                onClick = { viewModel.setTorrentBoxOpenMode(mode) },
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
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { onOpenTorrentBoxUrl(IntentActions.SEEDR_WEB_URL, "Seedr.cc") },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("settings_open_seedr_button")
                        ) {
                            Text(stringResource(R.string.action_open_seedr))
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = if (torrentBoxOpenMode == WebsiteOpenMode.IN_APP) {
                                    Icons.Filled.Language
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
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("settings_open_torbox_button")
                        ) {
                            Text(stringResource(R.string.action_open_torbox))
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = if (torrentBoxOpenMode == WebsiteOpenMode.IN_APP) {
                                    Icons.Filled.Language
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
        }

        // 4. Live Mirror Manager & Health Checker
        item(key = "mirror_manager") {
            MirrorManagerSection(
                mirrorConfigs = mirrorConfigs,
                healthMap = transient.mirrorHealthMap,
                isPingingAll = transient.isPingingAll,
                onPingAll = viewModel::pingAllMirrors,
                onPingSingle = viewModel::pingMirror,
                onToggleProvider = viewModel::toggleProviderEnabled,
                onAddMirror = viewModel::addMirrorUrl,
                onRemoveMirror = viewModel::removeMirrorUrl,
                onSetPrimaryMirror = viewModel::setPrimaryMirror,
                onPromoteFastest = viewModel::promoteFastestHealthyMirror,
                onResetDefaults = viewModel::resetMirrorsToDefaults
            )
        }
    }
}
