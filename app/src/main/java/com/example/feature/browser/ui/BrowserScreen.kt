package com.example.feature.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.core.fs.StorageRoots
import com.example.core.fs.VEntry
import com.example.core.fs.VPath
import com.example.feature.browser.FileManagerViewModel
import com.example.feature.browser.PaneId
import com.example.feature.browser.SortBy
import com.example.feature.browser.SortOrder
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DangerCrimson
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: FileManagerViewModel,
    onRequestPermission: () -> Unit,
    onRequestSafFallback: () -> Unit,
    onOpenFile: (VEntry) -> Unit,
    onShareFile: (List<VEntry>) -> Unit,
    onOpenTools: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activePaneId by viewModel.activePaneId.collectAsState()
    val paneAState by viewModel.paneAState.collectAsState()
    val paneBState by viewModel.paneBState.collectAsState()
    val storageRoots by viewModel.storageRoots.collectAsState()
    val taskProgress by viewModel.taskProgress.collectAsState()
    val activeProperties by viewModel.activeProperties.collectAsState()
    val hasStoragePermission by viewModel.hasStoragePermission.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val cloudBookmarks by viewModel.cloudBookmarks.collectAsState()
    val safDirectories by viewModel.safDirectories.collectAsState()
    val showRootUnavailableBanner by viewModel.showRootUnavailableBanner.collectAsState()
    val latestRemountFailure by viewModel.latestRemountFailure.collectAsState()
    val lastShellError by viewModel.lastShellError.collectAsState()
    val mountTable by viewModel.mountTable.collectAsState()
    val pendingSensitiveOp by viewModel.pendingSensitiveOp.collectAsState()
    val pendingSystemDelete by viewModel.pendingSystemDelete.collectAsState()

    val activeState = if (activePaneId == PaneId.PANE_A) paneAState else paneBState
    val activeMountEntry = remember(activeState.currentPath.normalized, mountTable) {
        if (activeState.currentPath.normalized.startsWith("content://")) {
            null
        } else {
            com.example.core.root.MountAnalyzer.findMountForPath(activeState.currentPath.normalized, mountTable)
                ?: com.example.core.root.RootManager.getMountForPath(activeState.currentPath.normalized)
        }
    }

    // Dialog states
    var showNewItemDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var contextTargetEntry by remember { mutableStateOf<Pair<PaneId, VEntry>?>(null) }
    var showStorageRootsSheet by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Intercept Back button safely
    BackHandler {
        if (activeState.isSelectionMode) {
            viewModel.clearSelection(activePaneId)
        } else if (activeState.isSearchOpen) {
            viewModel.toggleSearch(activePaneId)
        } else if (viewModel.navigateBack(activePaneId)) {
            // Navigated back in active pane
        } else if (activePaneId == PaneId.PANE_B) {
            viewModel.setActivePane(PaneId.PANE_A)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "MT Explorer",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                    },
                    actions = {
                        // Search toggle
                        IconButton(
                            onClick = { viewModel.toggleSearch(activePaneId) },
                            modifier = Modifier.testTag("topbar_btn_search")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = if (activeState.isSearchOpen) CyanAccent else TextPrimary
                            )
                        }

                        // Storage roots
                        IconButton(
                            onClick = { showStorageRootsSheet = true },
                            modifier = Modifier.testTag("topbar_btn_roots")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = "Storage Roots",
                                tint = TextPrimary
                            )
                        }

                        // User profile avatar & Sign out
                        IconButton(
                            onClick = { showProfileDialog = true },
                            modifier = Modifier.testTag("topbar_btn_profile")
                        ) {
                            if (currentUser?.photoUrl != null) {
                                AsyncImage(
                                    model = currentUser?.photoUrl,
                                    contentDescription = "User profile: ${currentUser?.displayName}",
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .border(1.5.dp, CyanAccent, CircleShape)
                                )
                            } else {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(DarkSurfaceHighlight)
                                        .border(1.dp, CyanAccent, CircleShape)
                                ) {
                                    val initial = currentUser?.displayName?.firstOrNull()?.toString()
                                        ?: currentUser?.email?.firstOrNull()?.toString()
                                        ?: "U"
                                    Text(
                                        text = initial.uppercase(),
                                        color = CyanAccent,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        // Refresh
                        IconButton(
                            onClick = { viewModel.refreshPane(activePaneId) },
                            modifier = Modifier.testTag("topbar_btn_refresh")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = TextPrimary
                            )
                        }

                        // Overflow menu
                        Box {
                            IconButton(
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.testTag("topbar_btn_overflow")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More options",
                                    tint = TextPrimary
                                )
                            }

                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                                modifier = Modifier.background(DarkSurface)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Account Profile", color = TextPrimary) },
                                    leadingIcon = {
                                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = CyanAccent)
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        showProfileDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort by...", color = TextPrimary) },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, tint = CyanAccent)
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        showSortMenu = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (activeState.showHidden) "Hide hidden files" else "Show hidden files",
                                            color = TextPrimary
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (activeState.showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null,
                                            tint = CyanAccent
                                        )
                                    },
                                    onClick = {
                                        viewModel.toggleShowHidden(activePaneId)
                                        showOverflowMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("New Folder / File", color = TextPrimary) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Add, contentDescription = null, tint = CyanAccent)
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        showNewItemDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Grant Folder (SAF)", color = TextPrimary) },
                                    leadingIcon = {
                                        Icon(Icons.Default.FolderShared, contentDescription = null, tint = CyanAccent)
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onRequestSafFallback()
                                    }
                                )
                                if (onOpenTools != null) {
                                    DropdownMenuItem(
                                        text = { Text("Tools", color = TextPrimary) },
                                        leadingIcon = {
                                            Icon(Icons.Default.Build, contentDescription = null, tint = CyanAccent)
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            onOpenTools()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Settings", color = TextPrimary) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Settings, contentDescription = null, tint = CyanAccent)
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        context.startActivity(
                                            android.content.Intent(context, com.example.feature.tools.ToolsActivity::class.java).apply {
                                                putExtra("tool", "SETTINGS")
                                            }
                                        )
                                    },
                                    modifier = Modifier.testTag("menu_item_settings")
                                )
                                DropdownMenuItem(
                                    text = { Text("Sign Out", color = DangerCrimson) },
                                    leadingIcon = {
                                        Icon(Icons.Default.ExitToApp, contentDescription = null, tint = DangerCrimson)
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.signOut()
                                    }
                                )
                            }

                            // Sort submenu
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                modifier = Modifier.background(DarkSurface)
                            ) {
                                SortBy.values().forEach { sort ->
                                    val isSelected = activeState.sortBy == sort
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = sort.name.lowercase().replaceFirstChar { it.uppercase() },
                                                    color = if (isSelected) CyanAccent else TextPrimary,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (isSelected) {
                                                    val arrow = if (activeState.sortOrder == SortOrder.ASCENDING) " ▲" else " ▼"
                                                    Text(arrow, color = CyanAccent, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        },
                                        onClick = {
                                            viewModel.setSort(activePaneId, sort)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = DarkSurface
                    )
                )

                // Tab Row for compact dual-pane switcher
                TabRow(
                    selectedTabIndex = if (activePaneId == PaneId.PANE_A) 0 else 1,
                    containerColor = DarkSurface,
                    contentColor = CyanAccent,
                    indicator = { tabPositions ->
                        val index = if (activePaneId == PaneId.PANE_A) 0 else 1
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[index]),
                            color = CyanAccent
                        )
                    }
                ) {
                    Tab(
                        selected = activePaneId == PaneId.PANE_A,
                        onClick = { viewModel.setActivePane(PaneId.PANE_A) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "A: ${paneAState.currentPath.name}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (activePaneId == PaneId.PANE_A) FontWeight.Bold else FontWeight.Normal,
                                    color = if (activePaneId == PaneId.PANE_A) CyanAccent else TextMuted
                                )
                            }
                        },
                        modifier = Modifier.testTag("tab_pane_a")
                    )

                    Tab(
                        selected = activePaneId == PaneId.PANE_B,
                        onClick = { viewModel.setActivePane(PaneId.PANE_B) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "B: ${paneBState.currentPath.name}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (activePaneId == PaneId.PANE_B) FontWeight.Bold else FontWeight.Normal,
                                    color = if (activePaneId == PaneId.PANE_B) CyanAccent else TextMuted
                                )
                            }
                        },
                        modifier = Modifier.testTag("tab_pane_b")
                    )
                }
            }
        },
        floatingActionButton = {
            if (!activeState.isSelectionMode) {
                FloatingActionButton(
                    onClick = { showNewItemDialog = true },
                    containerColor = CyanAccent,
                    contentColor = Color.Black,
                    modifier = Modifier.testTag("fab_new_item")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New item"
                    )
                }
            }
        },
        bottomBar = {
            if (activeState.isSelectionMode) {
                val selectedEntries = activeState.rawEntries.filter { activeState.selectedPaths.contains(it.path) }
                BottomActionBar(
                    selectedCount = activeState.selectedCount,
                    onClearSelection = { viewModel.clearSelection(activePaneId) },
                    onSelectAll = { viewModel.selectAll(activePaneId) },
                    onInvertSelection = { viewModel.invertSelection(activePaneId) },
                    onCopyToOtherPane = { viewModel.copySelectedToOtherPane() },
                    onMoveToOtherPane = { viewModel.moveSelectedToOtherPane() },
                    onDelete = { showDeleteConfirmDialog = true },
                    onRename = { showRenameDialog = true },
                    onShare = { onShareFile(selectedEntries) },
                    onProperties = { viewModel.calculateProperties(activePaneId) }
                )
            }
        },
        containerColor = DarkBackground,
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Startup banner when root is unavailable
            if (showRootUnavailableBanner) {
                RootUnavailableBanner(
                    onDismiss = { viewModel.dismissRootUnavailableBanner() }
                )
            }

            // Per-partition Mount R/W status bar & toggle for current active path
            activeMountEntry?.let { entry ->
                PartitionMountStatusBar(
                    mountEntry = entry,
                    onToggleMountRw = { targetEntry ->
                        viewModel.toggleMountRwForPath(targetEntry.mountPoint)
                    }
                )
            }

            // Permission notification banner if not yet granted
            if (!hasStoragePermission) {
                PermissionBanner(
                    onRequestPermission = onRequestPermission,
                    onRequestSafFallback = onRequestSafFallback
                )
            }

            // Task progress banner if copying/moving
            taskProgress?.let { task ->
                TaskProgressBanner(
                    task = task,
                    onCancel = { viewModel.cancelActiveTask() },
                    onDismiss = { viewModel.clearFinishedTask() }
                )
            }

            // Adaptive layout: Side-by-side on wide screens (>= 600dp), otherwise single active pane
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val isWideScreen = maxWidth >= 600.dp

                if (isWideScreen) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Pane A (Left)
                        FileListPane(
                            paneState = paneAState,
                            isActivePane = activePaneId == PaneId.PANE_A,
                            onNavigateTo = { viewModel.navigateTo(PaneId.PANE_A, it) },
                            onNavigateBack = { viewModel.navigateBack(PaneId.PANE_A) },
                            onNavigateForward = { viewModel.navigateForward(PaneId.PANE_A) },
                            onNavigateUp = { viewModel.navigateUp(PaneId.PANE_A) },
                            onOpenStorageRoots = {
                                viewModel.setActivePane(PaneId.PANE_A)
                                showStorageRootsSheet = true
                            },
                            onToggleSelect = {
                                viewModel.setActivePane(PaneId.PANE_A)
                                viewModel.toggleSelection(PaneId.PANE_A, it)
                            },
                            onOpenFile = onOpenFile,
                            onSearchQueryChange = { viewModel.setSearchQuery(PaneId.PANE_A, it) },
                            onCloseSearch = { viewModel.toggleSearch(PaneId.PANE_A) },
                            onCopyItem = { entry ->
                                viewModel.setActivePane(PaneId.PANE_A)
                                viewModel.copySingleToOtherPane(PaneId.PANE_A, entry)
                            },
                            onMoveItem = { entry ->
                                viewModel.setActivePane(PaneId.PANE_A)
                                viewModel.moveSingleToOtherPane(PaneId.PANE_A, entry)
                            },
                            onRenameItem = { entry ->
                                viewModel.setActivePane(PaneId.PANE_A)
                                contextTargetEntry = PaneId.PANE_A to entry
                                showRenameDialog = true
                            },
                            onDeleteItem = { entry ->
                                viewModel.setActivePane(PaneId.PANE_A)
                                contextTargetEntry = PaneId.PANE_A to entry
                                showDeleteConfirmDialog = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )

                        VerticalDivider(
                            color = DarkSurfaceHighlight,
                            thickness = 2.dp
                        )

                        // Pane B (Right)
                        FileListPane(
                            paneState = paneBState,
                            isActivePane = activePaneId == PaneId.PANE_B,
                            onNavigateTo = { viewModel.navigateTo(PaneId.PANE_B, it) },
                            onNavigateBack = { viewModel.navigateBack(PaneId.PANE_B) },
                            onNavigateForward = { viewModel.navigateForward(PaneId.PANE_B) },
                            onNavigateUp = { viewModel.navigateUp(PaneId.PANE_B) },
                            onOpenStorageRoots = {
                                viewModel.setActivePane(PaneId.PANE_B)
                                showStorageRootsSheet = true
                            },
                            onToggleSelect = {
                                viewModel.setActivePane(PaneId.PANE_B)
                                viewModel.toggleSelection(PaneId.PANE_B, it)
                            },
                            onOpenFile = onOpenFile,
                            onSearchQueryChange = { viewModel.setSearchQuery(PaneId.PANE_B, it) },
                            onCloseSearch = { viewModel.toggleSearch(PaneId.PANE_B) },
                            onCopyItem = { entry ->
                                viewModel.setActivePane(PaneId.PANE_B)
                                viewModel.copySingleToOtherPane(PaneId.PANE_B, entry)
                            },
                            onMoveItem = { entry ->
                                viewModel.setActivePane(PaneId.PANE_B)
                                viewModel.moveSingleToOtherPane(PaneId.PANE_B, entry)
                            },
                            onRenameItem = { entry ->
                                viewModel.setActivePane(PaneId.PANE_B)
                                contextTargetEntry = PaneId.PANE_B to entry
                                showRenameDialog = true
                            },
                            onDeleteItem = { entry ->
                                viewModel.setActivePane(PaneId.PANE_B)
                                contextTargetEntry = PaneId.PANE_B to entry
                                showDeleteConfirmDialog = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    // Mobile Portrait: single active pane
                    val currentPane = if (activePaneId == PaneId.PANE_A) paneAState else paneBState
                    FileListPane(
                        paneState = currentPane,
                        isActivePane = true,
                        onNavigateTo = { viewModel.navigateTo(activePaneId, it) },
                        onNavigateBack = { viewModel.navigateBack(activePaneId) },
                        onNavigateForward = { viewModel.navigateForward(activePaneId) },
                        onNavigateUp = { viewModel.navigateUp(activePaneId) },
                        onOpenStorageRoots = { showStorageRootsSheet = true },
                        onToggleSelect = { viewModel.toggleSelection(activePaneId, it) },
                        onOpenFile = onOpenFile,
                        onSearchQueryChange = { viewModel.setSearchQuery(activePaneId, it) },
                        onCloseSearch = { viewModel.toggleSearch(activePaneId) },
                        onCopyItem = { entry ->
                            viewModel.copySingleToOtherPane(activePaneId, entry)
                        },
                        onMoveItem = { entry ->
                            viewModel.moveSingleToOtherPane(activePaneId, entry)
                        },
                        onRenameItem = { entry ->
                            contextTargetEntry = activePaneId to entry
                            showRenameDialog = true
                        },
                        onDeleteItem = { entry ->
                            contextTargetEntry = activePaneId to entry
                            showDeleteConfirmDialog = true
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    // Modal Dialogs
    if (showNewItemDialog) {
        NewItemDialog(
            onDismiss = { showNewItemDialog = false },
            onCreateFolder = { viewModel.createFolder(activePaneId, it) },
            onCreateFile = { viewModel.createFile(activePaneId, it) }
        )
    }

    if (showRenameDialog) {
        val targetPair = contextTargetEntry ?: run {
            val selected = activeState.rawEntries.filter { activeState.selectedPaths.contains(it.path) }
            selected.firstOrNull()?.let { activePaneId to it }
        }
        if (targetPair != null) {
            val (paneId, item) = targetPair
            RenameDialog(
                currentName = item.name,
                onDismiss = {
                    showRenameDialog = false
                    contextTargetEntry = null
                },
                onConfirm = {
                    viewModel.renameItem(paneId, item, it)
                    contextTargetEntry = null
                }
            )
        }
    }

    if (showDeleteConfirmDialog) {
        val targetPair = contextTargetEntry
        DeleteConfirmDialog(
            count = if (targetPair != null) 1 else activeState.selectedCount,
            onDismiss = {
                showDeleteConfirmDialog = false
                contextTargetEntry = null
            },
            onConfirm = {
                if (targetPair != null) {
                    viewModel.deleteSingleItem(targetPair.first, targetPair.second)
                    contextTargetEntry = null
                } else {
                    viewModel.deleteSelected(activePaneId)
                }
            }
        )
    }

    val quickLocations = remember(context) { StorageRoots.getQuickLocations(context) }
    if (showStorageRootsSheet) {
        StorageRootsSheet(
            roots = storageRoots,
            quickLocations = quickLocations,
            currentUser = currentUser,
            cloudBookmarks = cloudBookmarks,
            safDirectories = safDirectories,
            onRequestSafFolder = onRequestSafFallback,
            onRevokeSafAccess = { uri -> viewModel.revokeSafAccess(uri) },
            onSignInGoogle = {
                (context as? android.app.Activity)?.let { activity ->
                    viewModel.signInWithGoogle(activity)
                }
            },
            onSignOut = { viewModel.signOut() },
            onAddCurrentBookmark = { viewModel.addCurrentPathToCloudBookmarks(activePaneId) },
            onDeleteBookmark = { bookmarkId -> viewModel.removeCloudBookmark(bookmarkId) },
            onSelectLocation = { viewModel.navigateTo(activePaneId, it) },
            onToggleRootMountRw = { root ->
                viewModel.toggleMountRwForPath(root.mountEntry?.mountPoint ?: root.path.normalized)
            },
            onDismiss = { showStorageRootsSheet = false }
        )
    }

    activeProperties?.let { info ->
        PropertiesDialog(
            info = info,
            onDismiss = { viewModel.dismissProperties() }
        )
    }

    // Overwrite conflict resolution dialog
    taskProgress?.conflict?.let { conflict ->
        OverwriteDialog(
            conflict = conflict,
            onResolve = { action, applyToAll ->
                viewModel.resolveConflict(action, applyToAll)
            }
        )
    }

    if (showProfileDialog) {
        UserProfileDialog(
            user = currentUser,
            bookmarkCount = cloudBookmarks.size,
            onSignOut = {
                showProfileDialog = false
                viewModel.signOut()
            },
            onDismiss = { showProfileDialog = false }
        )
    }

    // Remount Failure BottomSheet (Diagnosis + Magisk/OverlayFS info + Stay Read-Only)
    latestRemountFailure?.let { diagnosis ->
        RemountFailureBottomSheet(
            diagnosis = diagnosis,
            onDismiss = { viewModel.clearRemountFailure() }
        )
    }

    // Sensitive System Path Write Confirmation Dialog
    pendingSensitiveOp?.let { pendingOp ->
        SensitiveWriteConfirmDialog(
            targetPath = pendingOp.targetPath,
            operationName = pendingOp.operationName,
            onConfirm = { pendingOp.onConfirmed() },
            onDismiss = { viewModel.dismissSensitiveOp() }
        )
    }

    // System Area Delete Backup Offer Dialog
    pendingSystemDelete?.let { sysDel ->
        SystemDeleteBackupDialog(
            targetPath = sysDel.targetPath,
            onBackupAndDelete = { sysDel.onExecuteDelete(true) },
            onDeleteWithoutBackup = { sysDel.onExecuteDelete(false) },
            onDismiss = { viewModel.dismissSystemDelete() }
        )
    }

    // Shell stderr / command failure dialog
    lastShellError?.let { errText ->
        ShellErrorAlertDialog(
            errorMessage = errText,
            onDismiss = { viewModel.clearLastShellError() }
        )
    }
}
