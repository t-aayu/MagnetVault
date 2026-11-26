package com.example.ui.vault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.ExportPreviewCounts
import com.example.data.local.ExportSelection
import com.example.data.local.db.BookmarkWithCollections
import com.example.data.local.db.CollectionWithCount
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryMonoStyle

@Composable
internal fun ManageCollectionsDialog(
    collections: List<CollectionWithCount>,
    onCreateCollection: (String) -> Unit,
    onRenameCollection: (collectionId: String, newName: String) -> Unit,
    onDeleteCollection: (collectionId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val telemetry = LocalTelemetryColors.current
    var newName by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<CollectionWithCount?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Manage Collections") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag("manage_collections_dialog")
            ) {
                Text(
                    text = "Collections organize saved releases on this device (local-only, not synced to cloud, included in JSON exports).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("New collection name") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("new_collection_name_input")
                    )
                    Button(
                        onClick = {
                            if (newName.isNotBlank()) {
                                onCreateCollection(newName)
                                newName = ""
                            }
                        },
                        enabled = newName.isNotBlank(),
                        modifier = Modifier
                            .heightIn(min = 52.dp)
                            .testTag("create_collection_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Create collection", modifier = Modifier.size(18.dp))
                    }
                }

                if (collections.isEmpty()) {
                    Text(
                        text = "No collections yet. Create one above to group bookmarks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    collections.forEach { col ->
                        Surface(
                            color = telemetry.glassSurface,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(0.75.dp, telemetry.cardBorder)
                        ) {
                            if (editingId == col.collectionId) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = renameDraft,
                                        onValueChange = { renameDraft = it },
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("rename_collection_input_${col.collectionId}")
                                    )
                                    IconButton(
                                        onClick = {
                                            if (renameDraft.isNotBlank()) {
                                                onRenameCollection(col.collectionId, renameDraft)
                                                editingId = null
                                            }
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("confirm_rename_collection_${col.collectionId}")
                                    ) {
                                        Icon(Icons.Filled.Check, contentDescription = "Confirm rename")
                                    }
                                    IconButton(
                                        onClick = { editingId = null },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = "Cancel rename")
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(col.name, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "${col.bookmarkCount} saved item${if (col.bookmarkCount == 1) "" else "s"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            editingId = col.collectionId
                                            renameDraft = col.name
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("rename_collection_${col.collectionId}")
                                    ) {
                                        Icon(Icons.Outlined.Edit, contentDescription = "Rename ${col.name}", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = { pendingDelete = col },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("delete_collection_${col.collectionId}")
                                    ) {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            contentDescription = "Delete ${col.name}",
                                            tint = telemetry.deadRose,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("close_manage_collections_button")
            ) {
                Text("Done")
            }
        }
    )

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Delete collection '${toDelete.name}'?") },
            text = {
                Text(
                    "Your saved bookmarks will remain safely in your Vault; only this local collection group will be removed.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteCollection(toDelete.collectionId)
                        pendingDelete = null
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("confirm_delete_collection_button")
                ) {
                    Text("Delete Collection")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
internal fun AssignCollectionsDialog(
    item: BookmarkWithCollections,
    allCollections: List<CollectionWithCount>,
    onCreateAndAssign: (String) -> Unit,
    onSaveMembership: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedIds by remember(item.bookmark.localId) {
        mutableStateOf(item.collections.map { it.collectionId }.toSet())
    }
    var quickName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Assign to Collections") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag("assign_collections_dialog")
            ) {
                Text(
                    text = item.bookmark.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2
                )
                Text(
                    text = stringResource(R.string.collections_local_only),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = quickName,
                        onValueChange = { quickName = it },
                        placeholder = { Text("Create new collection") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("assign_quick_collection_input")
                    )
                    OutlinedButton(
                        onClick = {
                            if (quickName.isNotBlank()) {
                                onCreateAndAssign(quickName)
                                quickName = ""
                            }
                        },
                        enabled = quickName.isNotBlank(),
                        modifier = Modifier
                            .heightIn(min = 52.dp)
                            .testTag("assign_quick_collection_add")
                    ) {
                        Text("Add")
                    }
                }

                HorizontalDivider()

                if (allCollections.isEmpty()) {
                    Text(
                        text = "No collections yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    allCollections.forEach { col ->
                        val checked = col.collectionId in selectedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    selectedIds = if (checked) {
                                        selectedIds - col.collectionId
                                    } else {
                                        selectedIds + col.collectionId
                                    }
                                }
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selectedIds = if (isChecked) {
                                        selectedIds + col.collectionId
                                    } else {
                                        selectedIds - col.collectionId
                                    }
                                },
                                modifier = Modifier.testTag("assign_collection_checkbox_${col.collectionId}")
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(col.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveMembership(selectedIds)
                    onDismiss()
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("save_bookmark_collections_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
internal fun VaultExportDialog(
    counts: ExportPreviewCounts,
    onExportConfirmed: (ExportSelection) -> Unit,
    onDismiss: () -> Unit
) {
    var includeHistory by remember { mutableStateOf(false) }
    var includeCustomSites by remember { mutableStateOf(false) }
    var includeMirrors by remember { mutableStateOf(false) }
    val telemetry = LocalTelemetryColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Export Vault JSON (v2)") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault_export_dialog")
            ) {
                Surface(
                    color = telemetry.glassSurface,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.75.dp, telemetry.cardBorder)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "ALWAYS INCLUDED",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Bookmarks: ${counts.bookmarksCount} • Collections: ${counts.collectionsCount}",
                            style = TelemetryMonoStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("export_always_included_summary")
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Search History", style = MaterialTheme.typography.bodyMedium)
                        Text("${counts.historyCount} entries", style = TelemetryMonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = includeHistory,
                        onCheckedChange = { includeHistory = it },
                        modifier = Modifier.testTag("export_toggle_history")
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Custom Sites", style = MaterialTheme.typography.bodyMedium)
                        Text("${counts.customSitesCount} sites", style = TelemetryMonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = includeCustomSites,
                        onCheckedChange = { includeCustomSites = it },
                        modifier = Modifier.testTag("export_toggle_sites")
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Mirror Configs", style = MaterialTheme.typography.bodyMedium)
                        Text("${counts.mirrorsCount} providers", style = TelemetryMonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = includeMirrors,
                        onCheckedChange = { includeMirrors = it },
                        modifier = Modifier.testTag("export_toggle_mirrors")
                    )
                }

                Text(
                    text = "Never exports accounts, UIDs, Firebase tokens, Seedr/TorBox credentials, cookies, or sync metadata.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onExportConfirmed(
                        ExportSelection(
                            includeHistory = includeHistory,
                            includeCustomSites = includeCustomSites,
                            includeMirrors = includeMirrors
                        )
                    )
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("export_confirm_button")
            ) {
                Text("Export JSON")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("export_cancel_button")
            ) {
                Text("Cancel")
            }
        }
    )
}
