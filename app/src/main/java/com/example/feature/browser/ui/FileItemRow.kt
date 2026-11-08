package com.example.feature.browser.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.fs.EntryType
import com.example.core.fs.VEntry
import com.example.ui.theme.CodeBlue
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.FolderGold
import com.example.ui.theme.MediaPurple
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ZipOrange

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItemRow(
    entry: VEntry,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    } else {
        Color.Transparent
    }

    val spokenDescription = buildString {
        if (entry.isDirectory) append("Folder ") else append("File ")
        append("${entry.name}, ")
        if (!entry.isDirectory && entry.formattedSize.isNotEmpty()) {
            append("${entry.formattedSize}, ")
        }
        if (entry.formattedDate.isNotEmpty()) {
            append("modified ${entry.formattedDate}, ")
        }
        if (isSelected) append("selected") else append("not selected")
    }

    Surface(
        color = backgroundColor,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics {
                this.selected = isSelected
            }
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress
            )
            .testTag("file_item_${entry.name}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
        ) {
            // Checkbox for selection mode
            if (isSelectionMode || isSelected) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = CyanAccent,
                        checkmarkColor = Color.Black
                    ),
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("checkbox_${entry.name}")
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // File Type Icon with Badge styling
            FileTypeBadge(entry = entry)

            Spacer(modifier = Modifier.width(12.dp))

            // File details: Name, Size, Date
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = entry.name,
                    color = if (entry.isHidden) TextMuted else TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    if (!entry.isDirectory) {
                        Text(
                            text = entry.formattedSize,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (entry.formattedDate.isNotEmpty()) {
                        Text(
                            text = entry.formattedDate,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FileTypeBadge(entry: VEntry) {
    val (icon, color) = when (entry.entryType) {
        EntryType.FOLDER -> Icons.Default.Folder to FolderGold
        EntryType.ARCHIVE -> Icons.Default.Archive to ZipOrange
        EntryType.IMAGE -> Icons.Default.Image to CyanAccent
        EntryType.VIDEO -> Icons.Default.VideoFile to MediaPurple
        EntryType.AUDIO -> Icons.Default.AudioFile to Color(0xFFF43F5E)
        EntryType.CODE -> Icons.Default.Code to CodeBlue
        EntryType.APK -> Icons.Default.Android to SuccessGreen
        EntryType.DOCUMENT -> Icons.Default.Description to Color(0xFF38BDF8)
        EntryType.GENERIC_FILE -> Icons.AutoMirrored.Filled.InsertDriveFile to TextMuted
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(28.dp)
        )
    }
}
