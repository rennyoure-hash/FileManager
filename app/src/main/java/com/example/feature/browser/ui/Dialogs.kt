package com.example.feature.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.IconButton
import com.example.core.firebase.CloudBookmark
import com.google.firebase.auth.FirebaseUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.fs.StorageRoot
import com.example.core.fs.VPath
import com.example.core.tasks.ConflictAction
import com.example.core.tasks.FileConflict
import com.example.feature.browser.PropertiesInfo
import com.example.ui.theme.ConflictAmber
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DangerCrimson
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DarkSurfaceHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun OverwriteDialog(
    conflict: FileConflict,
    onResolve: (ConflictAction, Boolean) -> Unit
) {
    var applyToAll by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { onResolve(ConflictAction.SKIP, applyToAll) },
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = ConflictAmber,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "File Conflict",
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "A file named \"${conflict.source.name}\" already exists in the destination folder.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Source Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("New File (Source):", color = CyanAccent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text("Size: ${conflict.source.formattedSize}", color = TextPrimary, fontSize = 12.sp)
                        Text("Modified: ${conflict.source.formattedDate}", color = TextMuted, fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Destination Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Existing File (Destination):", color = ConflictAmber, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text("Size: ${conflict.destination.formattedSize}", color = TextPrimary, fontSize = 12.sp)
                        Text("Modified: ${conflict.destination.formattedDate}", color = TextMuted, fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { applyToAll = !applyToAll }
                ) {
                    Checkbox(
                        checked = applyToAll,
                        onCheckedChange = { applyToAll = it },
                        colors = CheckboxDefaults.colors(checkedColor = CyanAccent)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Apply to all conflicts", color = TextPrimary, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onResolve(ConflictAction.OVERWRITE, applyToAll) },
                colors = ButtonDefaults.buttonColors(containerColor = DangerCrimson)
            ) {
                Text("Overwrite")
            }
        },
        dismissButton = {
            Row {
                OutlinedButton(
                    onClick = { onResolve(ConflictAction.AUTO_RENAME, applyToAll) },
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Text("Keep Both")
                }
                TextButton(
                    onClick = { onResolve(ConflictAction.SKIP, applyToAll) }
                ) {
                    Text("Skip", color = TextSecondary)
                }
            }
        }
    )
}

@Composable
fun PropertiesDialog(
    info: PropertiesInfo,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Properties",
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = info.title,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = DarkSurfaceHighlight)
                Spacer(modifier = Modifier.height(8.dp))

                PropertyRow("Location", info.path)
                PropertyRow("Type", if (info.isDirectory) "Directory" else "File")
                PropertyRow("Total Size", if (info.isCalculating) "Calculating..." else info.formattedSize)

                if (info.isDirectory) {
                    PropertyRow("Contains", "${info.fileCount} files, ${info.folderCount} subfolders")
                }

                if (info.lastModified.isNotEmpty()) {
                    PropertyRow("Modified", info.lastModified)
                }
                PropertyRow("Permissions", "Read: ${if (info.canRead) "Yes" else "No"} | Write: ${if (info.canWrite) "Yes" else "No"}")

                if (info.isCalculating) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = CyanAccent,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Calculating folder tree size...", color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
            ) {
                Text("Close", color = Color.Black)
            }
        }
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(text = label, color = TextMuted, fontSize = 11.sp)
        Text(text = value, color = TextPrimary, fontSize = 13.sp)
    }
}

@Composable
fun NewItemDialog(
    onDismiss: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onCreateFile: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var isFolder by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isFolder) "Create New Folder" else "Create New File",
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { isFolder = true }
                            .padding(end = 16.dp)
                    ) {
                        RadioButton(
                            selected = isFolder,
                            onClick = { isFolder = true }
                        )
                        Text("Folder", color = TextPrimary, fontSize = 14.sp)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { isFolder = false }
                    ) {
                        RadioButton(
                            selected = !isFolder,
                            onClick = { isFolder = false }
                        )
                        Text("File", color = TextPrimary, fontSize = 14.sp)
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (isFolder) "Folder Name" else "File Name (e.g. notes.txt)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_new_item_name")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        if (isFolder) onCreateFolder(name) else onCreateFile(name)
                        onDismiss()
                    }
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                modifier = Modifier.testTag("btn_confirm_new_item")
            ) {
                Text("Create", color = Color.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Rename",
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New Name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_rename_name")
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newName.isNotBlank() && newName != currentName) {
                        onConfirm(newName)
                        onDismiss()
                    }
                },
                enabled = newName.isNotBlank() && newName != currentName,
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                modifier = Modifier.testTag("btn_confirm_rename")
            ) {
                Text("Rename", color = Color.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
fun DeleteConfirmDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.DeleteForever,
                contentDescription = null,
                tint = DangerCrimson,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Permanent Delete",
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "Are you sure you want to delete $count item(s)? This action cannot be undone.",
                color = TextSecondary,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = DangerCrimson),
                modifier = Modifier.testTag("btn_confirm_delete")
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageRootsSheet(
    roots: List<StorageRoot>,
    quickLocations: List<Pair<String, VPath>>,
    currentUser: FirebaseUser?,
    cloudBookmarks: List<CloudBookmark>,
    onSignInGoogle: () -> Unit,
    onSignOut: () -> Unit,
    onAddCurrentBookmark: () -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onSelectLocation: (VPath) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurfaceElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Cloud Sync & Google Account Header
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (currentUser != null) Icons.Default.CloudDone else Icons.Default.Cloud,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (currentUser != null) (currentUser.displayName ?: currentUser.email ?: "Signed in") else "Cloud Sync",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = if (currentUser != null) "Bookmarks synced via Firebase" else "Sign in with Google to sync bookmarks",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    if (currentUser == null) {
                        Button(
                            onClick = onSignInGoogle,
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                            modifier = Modifier.testTag("btn_google_sign_in")
                        ) {
                            Text("Sign in", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        TextButton(
                            onClick = onSignOut,
                            modifier = Modifier.testTag("btn_google_sign_out")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ExitToApp,
                                contentDescription = "Sign out",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sign out", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Cloud Bookmarks Section (if user is signed in)
            if (currentUser != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                ) {
                    Text(
                        text = "Cloud Bookmarks (${cloudBookmarks.size})",
                        color = CyanAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    TextButton(onClick = onAddCurrentBookmark) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Bookmark this folder", color = CyanAccent, fontSize = 11.sp)
                    }
                }

                if (cloudBookmarks.isEmpty()) {
                    Text(
                        text = "No bookmarks saved yet. Tap 'Bookmark this folder' to sync your current location.",
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else {
                    cloudBookmarks.forEach { bookmark ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectLocation(VPath(bookmark.path))
                                    onDismiss()
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Bookmark, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(bookmark.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(bookmark.path, color = TextMuted, fontSize = 11.sp)
                            }
                            IconButton(
                                onClick = { onDeleteBookmark(bookmark.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Delete bookmark", tint = TextMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = DarkSurfaceHighlight)
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = "Storage Roots",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            roots.forEach { root ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectLocation(root.path)
                            onDismiss()
                        }
                        .padding(vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = if (root.isRemovable) Icons.Default.SdCard else Icons.Default.Storage,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = root.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        if (root.formattedFreeSpace.isNotEmpty()) {
                            Text(text = root.formattedFreeSpace, color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = DarkSurfaceHighlight)
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Quick Folders",
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            quickLocations.forEach { (name, path) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectLocation(path)
                            onDismiss()
                        }
                        .padding(vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = name, color = TextPrimary, fontSize = 13.sp)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
