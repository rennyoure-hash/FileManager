package com.example.feature.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.fs.VEntry
import com.example.core.fs.VPath
import com.example.feature.browser.PaneState
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurfaceHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

@Composable
fun FileListPane(
    paneState: PaneState,
    isActivePane: Boolean,
    onNavigateTo: (VPath) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateForward: () -> Unit,
    onNavigateUp: () -> Unit,
    onOpenStorageRoots: () -> Unit,
    onToggleSelect: (VPath) -> Unit,
    onOpenFile: (VEntry) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onCopyItem: (VEntry) -> Unit = {},
    onMoveItem: (VEntry) -> Unit = {},
    onRenameItem: (VEntry) -> Unit = {},
    onDeleteItem: (VEntry) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Breadcrumb bar
        BreadcrumbBar(
            currentPath = paneState.currentPath,
            canGoBack = paneState.backHistory.isNotEmpty(),
            canGoForward = paneState.forwardHistory.isNotEmpty(),
            onNavigateTo = onNavigateTo,
            onNavigateBack = onNavigateBack,
            onNavigateForward = onNavigateForward,
            onNavigateUp = onNavigateUp,
            onOpenStorageRoots = onOpenStorageRoots
        )

        HorizontalDivider(color = DarkSurfaceHighlight)

        // Search bar (if search is toggled)
        if (paneState.isSearchOpen) {
            OutlinedTextField(
                value = paneState.searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Filter current directory...", color = TextMuted) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = CyanAccent
                    )
                },
                trailingIcon = {
                    if (paneState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = TextSecondary
                            )
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .testTag("pane_search_input")
            )
        }

        // Content Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (paneState.isLoading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    CircularProgressIndicator(
                        color = CyanAccent,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp)
                    )
                }
            } else if (paneState.displayedEntries.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (paneState.searchQuery.isNotEmpty()) "No files match \"${paneState.searchQuery}\"" else "Empty Folder",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("file_lazy_column")
                ) {
                    items(
                        items = paneState.displayedEntries,
                        key = { it.path.normalized }
                    ) { entry ->
                        val isSelected = paneState.selectedPaths.contains(entry.path)
                        FileItemRow(
                            entry = entry,
                            isSelected = isSelected,
                            isSelectionMode = paneState.isSelectionMode,
                            onTap = {
                                if (paneState.isSelectionMode) {
                                    onToggleSelect(entry.path)
                                } else {
                                    if (entry.isDirectory) {
                                        onNavigateTo(entry.path)
                                    } else {
                                        onOpenFile(entry)
                                    }
                                }
                            },
                            onLongPress = {
                                onToggleSelect(entry.path)
                            },
                            onToggleSelect = {
                                onToggleSelect(entry.path)
                            },
                            onCopy = { onCopyItem(entry) },
                            onMove = { onMoveItem(entry) },
                            onRename = { onRenameItem(entry) },
                            onDelete = { onDeleteItem(entry) }
                        )
                        HorizontalDivider(
                            color = DarkSurfaceHighlight.copy(alpha = 0.5f),
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }
}
