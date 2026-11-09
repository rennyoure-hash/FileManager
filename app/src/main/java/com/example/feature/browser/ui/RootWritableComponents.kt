package com.example.feature.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.example.core.root.MountEntry
import com.example.core.root.PartitionBadge
import com.example.core.root.RemountFailureDiagnosis
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DangerCrimson
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DarkSurfaceHighlight
import com.example.ui.theme.TealAccent
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun RootUnavailableBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF332400),
        modifier = modifier
            .fillMaxWidth()
            .testTag("banner_root_unavailable")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = Color(0xFFFFCA28),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Root tidak tersedia — berjalan dalam mode read-only biasa",
                color = Color(0xFFFFECB3),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Tutup banner",
                    tint = Color(0xFFFFECB3),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun PartitionBadgeChip(
    badge: PartitionBadge,
    fsType: String = "",
    modifier: Modifier = Modifier
) {
    val (bgColor, borderColor, textColor) = when (badge) {
        PartitionBadge.RW -> Triple(
            Color(0xFF003D24),
            TealAccent,
            TealAccent
        )
        PartitionBadge.RO -> Triple(
            Color(0xFF002E3D),
            CyanAccent,
            CyanAccent
        )
        PartitionBadge.RO_PERMANENT -> Triple(
            Color(0xFF3D0E18),
            DangerCrimson,
            DangerCrimson
        )
    }

    val text = if (fsType.isNotBlank()) {
        "${badge.label} · $fsType"
    } else {
        badge.label
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, borderColor.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("badge_partition_${badge.name.lowercase()}")
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun PartitionMountStatusBar(
    mountEntry: MountEntry,
    onToggleMountRw: (MountEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val badge = mountEntry.badge
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .background(DarkSurfaceElevated)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("partition_mount_status_bar")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (mountEntry.isRw) Icons.Default.LockOpen else Icons.Default.Lock,
                contentDescription = null,
                tint = when (badge) {
                    PartitionBadge.RW -> TealAccent
                    PartitionBadge.RO -> CyanAccent
                    PartitionBadge.RO_PERMANENT -> DangerCrimson
                },
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Mount: ${mountEntry.mountPoint}",
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(8.dp))
            PartitionBadgeChip(badge = badge, fsType = mountEntry.fsType)
        }

        val toggleLabel = when (badge) {
            PartitionBadge.RW -> "Mount R/O"
            PartitionBadge.RO -> "Mount R/W"
            PartitionBadge.RO_PERMANENT -> "RO-Permanen"
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (badge == PartitionBadge.RO_PERMANENT) DarkSurface
                    else CyanAccent.copy(alpha = 0.16f)
                )
                .border(
                    1.dp,
                    if (badge == PartitionBadge.RO_PERMANENT) DarkSurfaceHighlight else CyanAccent.copy(alpha = 0.6f),
                    RoundedCornerShape(6.dp)
                )
                .clickable { onToggleMountRw(mountEntry) }
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("btn_toggle_mount_rw")
        ) {
            Text(
                text = toggleLabel,
                color = if (badge == PartitionBadge.RO_PERMANENT) TextMuted else CyanAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemountFailureBottomSheet(
    diagnosis: RemountFailureDiagnosis,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
        containerColor = DarkSurface,
        modifier = Modifier.testTag("bottom_sheet_remount_failure")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = DangerCrimson,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Gagal Remount R/W (${diagnosis.mountPoint})",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Filesystem: ${diagnosis.fsType} · Opsi saat ini: ${diagnosis.currentOptions}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = DarkSurfaceHighlight)
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Hasil Diagnosa Partisi",
                color = CyanAccent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            diagnosis.causes.forEach { cause ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFFCA28),
                            modifier = Modifier
                                .size(18.dp)
                                .padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = cause,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Detail Properti Boot & Mount:",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Dynamic Partitions: ${diagnosis.dynamicPartitionDetail}\n" +
                            "• dm-verity / AVB: ${diagnosis.verityDetail}\n" +
                            "• mount stderr: ${diagnosis.mountStderr}",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF002B36)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CyanAccent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Info Opsi Systemless (Magisk Module / OverlayFS)",
                            color = CyanAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = diagnosis.magiskOverlayInfo,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("btn_stay_readonly")
            ) {
                Text(
                    text = "Tetap Read-Only",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SensitiveWriteConfirmDialog(
    targetPath: String,
    operationName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        containerColor = DarkSurface,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = DangerCrimson
            )
        },
        title = {
            Text(
                text = "Konfirmasi Akses Area Sistem",
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Anda akan menjalankan operasi '$operationName' pada lokasi sensitif sistem:",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Text(
                    text = targetPath,
                    color = CyanAccent,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Modifikasi yang salah pada /system, /vendor, /product, /boot, /dev/block, atau data aplikasi lain dapat menyebabkan ketidakstabilan sistem atau bootloop.",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = DangerCrimson),
                modifier = Modifier.testTag("btn_confirm_sensitive_write")
            ) {
                Text("Lanjutkan", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("btn_cancel_sensitive_write")
            ) {
                Text("Batal", color = TextSecondary)
            }
        }
    )
}

@Composable
fun SystemDeleteBackupDialog(
    targetPath: String,
    onBackupAndDelete: () -> Unit,
    onDeleteWithoutBackup: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        containerColor = DarkSurface,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFFCA28)
            )
        },
        title = {
            Text(
                text = "Hapus File Sistem — Backup Dulu?",
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Target berada di partisi sistem:",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Text(
                    text = targetPath,
                    color = CyanAccent,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Disarankan untuk mencadangkan (backup) item ini ke /sdcard/Download/MTExplorer_Backup terlebih dahulu sebelum menghapus.",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onBackupAndDelete,
                colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                modifier = Modifier.testTag("btn_backup_and_delete")
            ) {
                Text("Backup & Hapus", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("btn_cancel_system_delete")
                ) {
                    Text("Batal", color = TextSecondary)
                }
                OutlinedButton(
                    onClick = onDeleteWithoutBackup,
                    modifier = Modifier.testTag("btn_delete_without_backup")
                ) {
                    Text("Hapus Langsung", color = DangerCrimson)
                }
            }
        }
    )
}

@Composable
fun ShellErrorAlertDialog(
    errorMessage: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        containerColor = DarkSurface,
        icon = {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = DangerCrimson
            )
        },
        title = {
            Text(
                text = "Peringatan / Error Operasi Root",
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = errorMessage,
                color = TextPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("btn_dismiss_shell_error")
            ) {
                Text("Tutup", color = CyanAccent, fontWeight = FontWeight.Bold)
            }
        }
    )
}
