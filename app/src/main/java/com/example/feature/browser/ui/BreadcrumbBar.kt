package com.example.feature.browser.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.fs.VPath
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

@Composable
fun BreadcrumbBar(
    currentPath: VPath,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onNavigateTo: (VPath) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateForward: () -> Unit,
    onNavigateUp: () -> Unit,
    onOpenStorageRoots: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var isEditingPath by remember { mutableStateOf(false) }
    var typedPath by remember(currentPath.normalized) { mutableStateOf(currentPath.normalized) }

    LaunchedEffect(currentPath) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        color = DarkSurface,
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(if (isEditingPath) 56.dp else 48.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            // Quick Up Button
            IconButton(
                onClick = onNavigateUp,
                enabled = currentPath.parent != null,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("btn_navigate_up")
            ) {
                Icon(
                    imageVector = Icons.Default.North,
                    contentDescription = "Up one folder",
                    tint = if (currentPath.parent != null) CyanAccent else TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Storage Roots icon
            IconButton(
                onClick = onOpenStorageRoots,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("btn_storage_roots")
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = "Storage locations",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            if (isEditingPath) {
                OutlinedTextField(
                    value = typedPath,
                    onValueChange = { typedPath = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val clean = typedPath.trim()
                            if (clean.isNotEmpty()) {
                                onNavigateTo(VPath(clean))
                            }
                            isEditingPath = false
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp)
                        .testTag("input_editable_path")
                )
                IconButton(
                    onClick = {
                        val clean = typedPath.trim()
                        if (clean.isNotEmpty()) {
                            onNavigateTo(VPath(clean))
                        }
                        isEditingPath = false
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("btn_confirm_editable_path")
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Go to path", tint = CyanAccent)
                }
                IconButton(
                    onClick = {
                        typedPath = currentPath.normalized
                        isEditingPath = false
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel path edit", tint = TextMuted)
                }
            } else {
                // Scrollable Breadcrumbs
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(scrollState)
                ) {
                    // Home/Root
                    TextButton(
                        onClick = { onNavigateTo(VPath.ROOT) },
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = "Root",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Segments
                    val segments = currentPath.segments
                    var accumulated = ""
                    for (i in segments.indices) {
                        val segment = segments[i]
                        accumulated += "/$segment"
                        val segmentPath = VPath(accumulated)
                        val isLast = i == segments.size - 1

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )

                        TextButton(
                            onClick = { onNavigateTo(segmentPath) },
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                text = segment,
                                color = if (isLast) CyanAccent else TextPrimary,
                                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                // Edit path button (Type path directly)
                IconButton(
                    onClick = {
                        typedPath = currentPath.normalized
                        isEditingPath = true
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("btn_edit_path")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Ketik path",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Back & Forward history buttons
                if (canGoBack) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back history",
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (canGoForward) {
                    IconButton(
                        onClick = onNavigateForward,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Forward history",
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
