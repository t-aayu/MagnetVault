package com.example.ui.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.CustomSite
import com.example.ui.theme.GlassCard
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryMonoStyle
import com.example.ui.theme.ambientGlassBackdrop

@Composable
fun SitesHomeContent(
    customSites: List<CustomSite>,
    pinnedSites: List<CustomSite>,
    sessionState: SitesBrowserSessionState,
    onOpenUrl: (String) -> Unit,
    onResumeActiveSession: () -> Unit,
    onDiscardActiveSession: () -> Unit,
    onAddCustomSite: (name: String, normalizedUrl: String) -> Unit,
    onUpdateCustomSite: (siteId: String, name: String, normalizedUrl: String) -> Unit,
    onRemoveCustomSite: (siteId: String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val telemetry = LocalTelemetryColors.current
    val focusManager = LocalFocusManager.current

    var quickUrlInput by remember { mutableStateOf("") }
    var editingSite by remember { mutableStateOf<CustomSite?>(null) }
    var showSiteDialog by remember { mutableStateOf(false) }
    var dialogName by remember { mutableStateOf("") }
    var dialogUrl by remember { mutableStateOf("https://") }
    var dialogAllowHttp by remember { mutableStateOf(false) }

    fun openAddDialog() {
        editingSite = null
        dialogName = ""
        dialogUrl = "https://"
        dialogAllowHttp = false
        showSiteDialog = true
    }

    fun openEditDialog(site: CustomSite) {
        editingSite = site
        dialogName = site.name
        dialogUrl = site.url
        dialogAllowHttp = site.url.startsWith("http://", ignoreCase = true)
        showSiteDialog = true
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .ambientGlassBackdrop(telemetry)
            .testTag("sites_home_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "sites_header") {
            Column {
                Text(
                    text = "Sites",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Open pinned mirrors or custom sites in MagnetVault's hardened browser",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }

        if (sessionState.hasRetainedSession) {
            item(key = "active_session_banner") {
                GlassCard(
                    shape = RoundedCornerShape(14.dp),
                    elevated = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("resume_active_browser_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Active Tab",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = sessionState.currentHostname.ifBlank { "In-app browser" },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        FilledTonalButton(
                            onClick = onResumeActiveSession,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("resume_active_browser_button")
                        ) {
                            Text("Resume")
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onDiscardActiveSession,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("discard_browser_session_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close active browser session",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item(key = "quick_address_card") {
            GlassCard(
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = quickUrlInput,
                        onValueChange = { quickUrlInput = it },
                        singleLine = true,
                        placeholder = { Text("Enter HTTPS URL (e.g. 1337x.to)", style = MaterialTheme.typography.bodySmall) },
                        textStyle = TelemetryMonoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                focusManager.clearFocus()
                                onOpenUrl(quickUrlInput)
                            }
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = telemetry.glassSurfaceElevated,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = telemetry.cardBorder
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sites_home_url_input")
                    )
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            onOpenUrl(quickUrlInput)
                        },
                        enabled = quickUrlInput.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .heightIn(min = 52.dp)
                            .testTag("sites_home_open_url_button")
                    ) {
                        Text("Open")
                    }
                }
            }
        }

        item(key = "custom_sites_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CUSTOM SITES",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Saved locally on this device (${customSites.size})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = ::openAddDialog,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("add_custom_site_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Site")
                }
            }
        }

        if (customSites.isEmpty()) {
            item(key = "custom_sites_empty") {
                GlassCard(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_sites_empty_card")
                ) {
                    Text(
                        text = "No custom sites saved yet. Tap Add Site to pin your preferred torrent mirrors locally.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(customSites, key = { "custom_${it.id}" }) { site ->
                SiteDirectoryRow(
                    site = site,
                    onClick = { onOpenUrl(site.url) },
                    onEdit = { openEditDialog(site) },
                    onRemove = { onRemoveCustomSite(site.id) }
                )
            }
        }

        item(key = "pinned_sites_header") {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "PINNED SITES",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.SemiBold
            )
        }

        items(pinnedSites, key = { "pinned_${it.id}" }) { site ->
            SiteDirectoryRow(
                site = site,
                onClick = { onOpenUrl(site.url) },
                onEdit = null,
                onRemove = null
            )
        }
    }

    if (showSiteDialog) {
        val isEdit = editingSite != null
        AlertDialog(
            onDismissRequest = { showSiteDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(18.dp),
            title = { Text(if (isEdit) "Edit Custom Site" else "Add Custom Website") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "HTTPS is enforced by default. Enable the HTTP exception only for legacy mirrors without HTTPS.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = dialogName,
                        onValueChange = { dialogName = it },
                        label = { Text("Site Name (optional)") },
                        placeholder = { Text("e.g. 1337x Mirror") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = telemetry.glassSurfaceElevated,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = telemetry.cardBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_site_name_input")
                    )
                    OutlinedTextField(
                        value = dialogUrl,
                        onValueChange = { dialogUrl = it },
                        label = { Text("Website URL") },
                        placeholder = { Text("https://x1337x.cc") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = telemetry.glassSurfaceElevated,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = telemetry.cardBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_site_url_input")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Allow HTTP mirror exception",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Keep off unless this mirror has no HTTPS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = dialogAllowHttp,
                            onCheckedChange = { dialogAllowHttp = it },
                            modifier = Modifier.testTag("custom_site_allow_http_switch")
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val valid = BrowserUrlPolicy.validateCustomSiteUrl(
                            raw = dialogUrl,
                            allowLegacyHttpMirror = dialogAllowHttp
                        )
                        if (valid == null) {
                            onShowMessage(
                                if (dialogAllowHttp) {
                                    "Enter a valid https:// or http:// website URL"
                                } else {
                                    "Enter an https:// URL or enable the HTTP mirror exception"
                                }
                            )
                        } else {
                            val target = editingSite
                            if (target != null) {
                                onUpdateCustomSite(target.id, dialogName, valid)
                            } else {
                                onAddCustomSite(dialogName, valid)
                            }
                            showSiteDialog = false
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("custom_site_save_button")
                ) {
                    Text(if (isEdit) "Save Changes" else "Save Site")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSiteDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SiteDirectoryRow(
    site: CustomSite,
    onClick: () -> Unit,
    onEdit: (() -> Unit)?,
    onRemove: (() -> Unit)?
) {
    val telemetry = LocalTelemetryColors.current
    val host = remember(site.url) { BrowserUrlPolicy.formatHostLabel(site.url) }
    val isHttpException = site.url.startsWith("http://", ignoreCase = true)

    GlassCard(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("site_chip_${site.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .padding(start = 14.dp, end = if (site.isCustom) 4.dp else 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = if (isHttpException) telemetry.leecherAmberContainer else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(36.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (isHttpException) Icons.Filled.WarningAmber else Icons.Filled.Lock,
                        contentDescription = null,
                        tint = if (isHttpException) telemetry.leecherAmber else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = site.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isHttpException) "$host • HTTP exception" else host,
                    style = TelemetryMonoStyle,
                    color = if (isHttpException) telemetry.leecherAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (site.isCustom && onEdit != null && onRemove != null) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("edit_custom_site_${site.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit ${site.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("remove_custom_site_${site.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Remove ${site.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open ${site.name}",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
