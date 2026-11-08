package com.example.feature.browser.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DangerCrimson
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.TextPrimary

@Composable
fun BottomActionBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onCopyToOtherPane: () -> Unit,
    onMoveToOtherPane: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onProperties: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = DarkSurfaceElevated,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 12.dp)
        ) {
            // Header: Selected count + quick selection controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onClearSelection,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear selection",
                            tint = CyanAccent
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$selectedCount selected",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Row {
                    FilledTonalButton(
                        onClick = onSelectAll,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text("Select All", fontSize = 12.sp)
                    }
                    FilledTonalButton(
                        onClick = onInvertSelection
                    ) {
                        Text("Invert", fontSize = 12.sp)
                    }
                }
            }

            // Action buttons row (scrollable for narrow screens)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                ActionIconButton(
                    icon = Icons.Default.ContentCopy,
                    label = "Copy to Pane",
                    onClick = onCopyToOtherPane,
                    testTag = "btn_action_copy"
                )
                ActionIconButton(
                    icon = Icons.AutoMirrored.Filled.DriveFileMove,
                    label = "Move to Pane",
                    onClick = onMoveToOtherPane,
                    testTag = "btn_action_move"
                )
                if (selectedCount == 1) {
                    ActionIconButton(
                        icon = Icons.Default.Edit,
                        label = "Rename",
                        onClick = onRename,
                        testTag = "btn_action_rename"
                    )
                }
                ActionIconButton(
                    icon = Icons.Default.Delete,
                    label = "Delete",
                    onClick = onDelete,
                    tint = DangerCrimson,
                    testTag = "btn_action_delete"
                )
                ActionIconButton(
                    icon = Icons.Default.Share,
                    label = "Share",
                    onClick = onShare,
                    testTag = "btn_action_share"
                )
                ActionIconButton(
                    icon = Icons.Default.Info,
                    label = "Info",
                    onClick = onProperties,
                    testTag = "btn_action_properties"
                )
            }
        }
    }
}

@Composable
private fun ActionIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    testTag: String,
    tint: androidx.compose.ui.graphics.Color = TextPrimary
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .testTag(testTag)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = label,
                color = tint,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
