package com.example.feature.browser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.tasks.TaskProgress
import com.example.core.tasks.TaskStatus
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DangerCrimson
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun TaskProgressBanner(
    task: TaskProgress,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRunning = task.status == TaskStatus.RUNNING || task.status == TaskStatus.SCANNING
    val isFinished = task.status == TaskStatus.COMPLETED || task.status == TaskStatus.FAILED || task.status == TaskStatus.CANCELLED

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .testTag("task_progress_banner")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val statusIcon = when (task.status) {
                        TaskStatus.COMPLETED -> Icons.Default.CheckCircle to SuccessGreen
                        TaskStatus.FAILED -> Icons.Default.Error to DangerCrimson
                        TaskStatus.CANCELLED -> Icons.Default.Cancel to TextMuted
                        else -> Icons.Default.Sync to CyanAccent
                    }

                    Icon(
                        imageVector = statusIcon.first,
                        contentDescription = null,
                        tint = statusIcon.second,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = task.title,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isRunning) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel task",
                            tint = DangerCrimson,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else if (isFinished) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (task.currentFileName.isNotEmpty()) {
                Text(
                    text = task.currentFileName,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (isRunning) {
                Spacer(modifier = Modifier.height(6.dp))
                if (task.status == TaskStatus.SCANNING || task.totalBytes <= 0) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = CyanAccent,
                        trackColor = Color(0xFF1E293B)
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { task.percent },
                        modifier = Modifier.fillMaxWidth(),
                        color = CyanAccent,
                        trackColor = Color(0xFF1E293B)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    val percentStr = String.format(Locale.US, "%.0f%%", task.percent * 100)
                    Text(
                        text = "$percentStr (${task.filesProcessed}/${task.totalFiles})",
                        color = TextMuted,
                        fontSize = 11.sp
                    )

                    if (task.speedBps > 0) {
                        val mbps = task.speedBps.toDouble() / (1024 * 1024)
                        Text(
                            text = String.format(Locale.US, "%.1f MB/s", mbps),
                            color = CyanAccent,
                            fontSize = 11.sp
                        )
                    }
                }
            } else if (task.status == TaskStatus.COMPLETED) {
                Text(
                    text = "Finished (${task.filesProcessed} items)",
                    color = SuccessGreen,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else if (task.status == TaskStatus.FAILED) {
                Text(
                    text = task.errorMessage ?: "Task failed",
                    color = DangerCrimson,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
