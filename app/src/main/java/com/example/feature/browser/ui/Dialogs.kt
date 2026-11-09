package com.example.feature.browser.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.example.core.firebase.CloudBookmark
import com.example.core.fs.StorageRoot
import com.example.core.fs.VPath
import com.example.core.permission.SafDirectoryInfo
import com.example.core.root.PartitionBadge
import com.example.core.tasks.ConflictAction
import com.example.core.tasks.FileConflict
import com.example.feature.browser.PropertiesInfo
import com.example.ui.theme.ConflictAmber
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DangerCrimson
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.SuccessGreen
import com.google.firebase.auth.FirebaseUser

/**
 * FASE 8c.6: All sensitive file dialogs & bottom sheets enforce
 * DialogProperties(securePolicy = SecureFlagPolicy.SecureOn) and
 * ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn).
 */
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
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = {
            Text(
                if (isFolder) "Buat Folder Baru" else "Buat File Baru",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = isFolder,
                        onClick = { isFolder = true },
                        label = { Text("Folder") },
                        modifier = Modifier.testTag("new_item_type_folder")
                    )
                    FilterChip(
                        selected = !isFolder,
                        onClick = { isFolder = false },
                        label = { Text("File") },
                        modifier = Modifier.testTag("new_item_type_file")
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_item_name_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) {
                        if (isFolder) onCreateFolder(trimmed) else onCreateFile(trimmed)
                        onDismiss()
                    }
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("new_item_confirm_btn")
            ) {
                Text("Buat")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
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
    var newName by remember(currentName) { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Ubah Nama", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Nama baru") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rename_input")
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = newName.trim()
                    if (trimmed.isNotEmpty() && trimmed != currentName) {
                        onConfirm(trimmed)
                    }
                    onDismiss()
                },
                enabled = newName.isNotBlank() && newName.trim() != currentName,
                modifier = Modifier.testTag("rename_confirm_btn")
            ) {
                Text("Simpan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
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
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = DangerCrimson) },
        title = { Text("Konfirmasi Hapus", fontWeight = FontWeight.Bold) },
        text = {
            Text("Apakah Anda yakin ingin menghapus $count item yang dipilih?")
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = DangerCrimson),
                modifier = Modifier.testTag("delete_confirm_btn")
            ) {
                Text("Hapus", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
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
    safDirectories: List<SafDirectoryInfo>,
    onRequestSafFolder: () -> Unit,
    onRevokeSafAccess: (Uri) -> Unit,
    onSignInGoogle: () -> Unit,
    onSignOut: () -> Unit,
    onAddCurrentBookmark: () -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onSelectLocation: (VPath) -> Unit,
    onToggleRootMountRw: (StorageRoot) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
        containerColor = DarkSurfaceElevated
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "Volume & Partisi Penyimpanan",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(roots) { root ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectLocation(root.path)
                            onDismiss()
                        }
                        .testTag("storage_root_${root.title}")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(root.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                root.path.normalized,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (root.formattedFreeSpace.isNotEmpty()) {
                                Text(
                                    root.formattedFreeSpace,
                                    fontSize = 11.sp,
                                    color = CyanAccent
                                )
                            }
                        }
                        if (root.mountEntry != null) {
                            AssistChip(
                                onClick = { onToggleRootMountRw(root) },
                                label = {
                                    Text(
                                        text = when (root.badge) {
                                            PartitionBadge.RW -> "RW"
                                            PartitionBadge.RO -> "RO"
                                            PartitionBadge.RO_PERMANENT -> "EROFS/RO"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            )
                        }
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "Lokasi Cepat",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            items(quickLocations) { (label, path) ->
                ListItem(
                    headlineContent = { Text(label) },
                    supportingContent = { Text(path.normalized, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                    leadingContent = { Icon(Icons.Default.Folder, contentDescription = null, tint = CyanAccent) },
                    modifier = Modifier
                        .clickable {
                            onSelectLocation(path)
                            onDismiss()
                        }
                )
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SAF Directories",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onRequestSafFolder) {
                        Text("+ Tambah Folder SAF")
                    }
                }
            }

            items(safDirectories) { saf ->
                ListItem(
                    headlineContent = { Text(saf.name) },
                    supportingContent = { Text(saf.uri.toString(), fontSize = 10.sp) },
                    trailingContent = {
                        IconButton(onClick = { onRevokeSafAccess(saf.uri) }) {
                            Icon(Icons.Default.Close, contentDescription = "Cabut akses SAF")
                        }
                    }
                )
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cloud Bookmarks",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (currentUser != null) {
                        TextButton(onClick = onAddCurrentBookmark) {
                            Text("+ Bookmark Path Ini")
                        }
                    } else {
                        TextButton(onClick = onSignInGoogle) {
                            Text("Sign In Google")
                        }
                    }
                }
            }

            items(cloudBookmarks) { bm ->
                ListItem(
                    headlineContent = { Text(bm.name) },
                    supportingContent = { Text(bm.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                    trailingContent = {
                        IconButton(onClick = { onDeleteBookmark(bm.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Hapus bookmark")
                        }
                    },
                    modifier = Modifier.clickable {
                        onSelectLocation(VPath(bm.path))
                        onDismiss()
                    }
                )
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }
}

@Composable
fun PropertiesDialog(
    info: PropertiesInfo,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        icon = { Icon(Icons.Default.Info, contentDescription = null, tint = CyanAccent) },
        title = { Text("Properti: ${info.title}", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Path: ${info.path}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text("Tipe: ${if (info.isDirectory) "Direktori" else "File"}", fontSize = 12.sp)
                Text("Ukuran: ${info.formattedSize} (${info.totalSize} bytes)", fontSize = 12.sp)
                if (info.isDirectory) {
                    Text("Isi: ${info.fileCount} file, ${info.folderCount} sub-folder", fontSize = 12.sp)
                }
                Text("Diubah: ${info.lastModified}", fontSize = 12.sp)
                Text(
                    "Akses: R=${if (info.canRead) "Ya" else "Tidak"} / W=${if (info.canWrite) "Ya" else "Tidak"}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                if (info.isCalculating) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("properties_close_btn")) {
                Text("Tutup")
            }
        }
    )
}

@Composable
fun OverwriteDialog(
    conflict: FileConflict,
    onResolve: (ConflictAction, Boolean) -> Unit
) {
    var applyToAll by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { onResolve(ConflictAction.SKIP, false) },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = ConflictAmber) },
        title = { Text("Konflik File Sudah Ada", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Item '${conflict.source.name}' sudah ada di folder tujuan:")
                Text(
                    conflict.destination.path.normalized,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = applyToAll,
                        onCheckedChange = { applyToAll = it }
                    )
                    Text("Terapkan untuk semua konflik berikutnya", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { onResolve(ConflictAction.SKIP, applyToAll) }) {
                    Text("Lewati")
                }
                TextButton(onClick = { onResolve(ConflictAction.AUTO_RENAME, applyToAll) }) {
                    Text("Ganti Nama")
                }
                Button(
                    onClick = { onResolve(ConflictAction.OVERWRITE, applyToAll) },
                    colors = ButtonDefaults.buttonColors(containerColor = ConflictAmber)
                ) {
                    Text("Timpa")
                }
            }
        }
    )
}

@Composable
fun UserProfileDialog(
    user: FirebaseUser?,
    bookmarkCount: Int,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        icon = { Icon(Icons.Default.AccountCircle, contentDescription = null, tint = SuccessGreen) },
        title = { Text("Profil Akun Cloud", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Nama: ${user?.displayName ?: "Pengguna"}")
                Text("Email: ${user?.email ?: "-"}")
                Text("Cloud Bookmarks Tersimpan: $bookmarkCount")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup")
            }
        },
        dismissButton = {
            TextButton(onClick = onSignOut) {
                Text("Sign Out", color = DangerCrimson)
            }
        }
    )
}

/**
 * Compliance rationale dialog before requesting MANAGE_EXTERNAL_STORAGE.
 * Informs the user of the core file manager capabilities requiring broad storage access
 * in accordance with Google Play Developer Program policies.
 */
@Composable
fun StorageAccessRationaleDialog(
    onConfirmSettings: () -> Unit,
    onChooseSaf: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        icon = {
            Icon(
                imageVector = Icons.Default.FolderShared,
                contentDescription = null,
                tint = CyanAccent,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Storage Access & Policy Compliance",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "MT Explorer is a full-featured dual-pane file manager designed for browsing, copying, moving, archiving, and editing files across device storage and external SD cards.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Text(
                    text = "In compliance with Android and Google Play policies for file manager apps, full file management requires the 'All Files Access' (MANAGE_EXTERNAL_STORAGE) permission.\n\nYou can enable this in system Settings, or choose to grant access to a specific folder only using the Storage Access Framework (SAF).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmSettings,
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                modifier = Modifier.testTag("rationale_btn_open_settings")
            ) {
                Text("Open Settings", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onChooseSaf,
                    modifier = Modifier.testTag("rationale_btn_use_saf")
                ) {
                    Text("Use SAF Folder", color = CyanAccent)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("rationale_btn_not_now")
                ) {
                    Text("Not Now")
                }
            }
        }
    )
}

/**
 * Follow-up dialog offered when MANAGE_EXTERNAL_STORAGE was denied or cancelled in Settings.
 * Guides the user to pick an individual folder using Storage Access Framework (SAF).
 */
@Composable
fun SafFallbackOfferDialog(
    onChooseSaf: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        icon = {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = ConflictAmber,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "Grant Folder Access (SAF)",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Text(
                text = "All Files Access was not enabled. You can still manage files inside specific folders (such as Downloads, Documents, or SD cards) by granting access via the Android Storage Access Framework (SAF).",
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onChooseSaf,
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                modifier = Modifier.testTag("saf_fallback_btn_pick_folder")
            ) {
                Text("Select Folder", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("saf_fallback_btn_cancel")
            ) {
                Text("Cancel")
            }
        }
    )
}
