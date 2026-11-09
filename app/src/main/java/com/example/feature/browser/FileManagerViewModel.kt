package com.example.feature.browser

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.app.Activity
import android.net.Uri
import com.example.core.firebase.AuthManager
import com.example.core.firebase.CloudBookmark
import com.example.core.firebase.CloudRepository
import com.example.core.fs.CompositeFileSystem
import com.example.core.fs.LocalFileSystem
import com.example.core.fs.SafFileSystem
import com.example.core.fs.StorageRoot
import com.example.core.fs.StorageRoots
import com.example.core.fs.VEntry
import com.example.core.fs.VPath
import com.example.core.permission.PermissionManager
import com.example.core.permission.SafDirectoryInfo
import com.example.core.permission.StorageAccessTier
import com.example.core.tasks.ConflictAction
import com.example.core.tasks.TaskEngine
import com.example.core.tasks.TaskProgress
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class FileManagerViewModel(application: Application) : AndroidViewModel(application) {

    val localFileSystem = LocalFileSystem()
    val safFileSystem = SafFileSystem(application)
    val fileSystem = CompositeFileSystem(localFileSystem, safFileSystem)
    val taskEngine = TaskEngine(viewModelScope)
    val taskProgress: StateFlow<TaskProgress?> = taskEngine.currentTask

    private val _storageRoots = MutableStateFlow<List<StorageRoot>>(emptyList())
    val storageRoots: StateFlow<List<StorageRoot>> = _storageRoots.asStateFlow()

    private val _safDirectories = MutableStateFlow<List<SafDirectoryInfo>>(emptyList())
    val safDirectories: StateFlow<List<SafDirectoryInfo>> = _safDirectories.asStateFlow()

    private val _activePaneId = MutableStateFlow(PaneId.PANE_A)
    val activePaneId: StateFlow<PaneId> = _activePaneId.asStateFlow()

    private val _paneAState = MutableStateFlow(
        PaneState(
            paneId = PaneId.PANE_A,
            currentPath = VPath(Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0")
        )
    )
    val paneAState: StateFlow<PaneState> = _paneAState.asStateFlow()

    private val _paneBState = MutableStateFlow(
        PaneState(
            paneId = PaneId.PANE_B,
            currentPath = VPath(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
                    ?: (Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0")
            )
        )
    )
    val paneBState: StateFlow<PaneState> = _paneBState.asStateFlow()

    private val _activeProperties = MutableStateFlow<PropertiesInfo?>(null)
    val activeProperties: StateFlow<PropertiesInfo?> = _activeProperties.asStateFlow()

    private val _storageAccessTier = MutableStateFlow(PermissionManager.getStorageAccessTier(application))
    val storageAccessTier: StateFlow<StorageAccessTier> = _storageAccessTier.asStateFlow()

    private val _hasStoragePermission = MutableStateFlow(PermissionManager.hasAnyStorageAccess(application))
    val hasStoragePermission: StateFlow<Boolean> = _hasStoragePermission.asStateFlow()

    val authManager = AuthManager(application, viewModelScope)
    val cloudRepository = CloudRepository(application)

    val currentUser: StateFlow<FirebaseUser?> = authManager.currentUser
    val authLoading: StateFlow<Boolean> = authManager.isLoading
    val authError: StateFlow<String?> = authManager.authError

    private val _cloudBookmarks = MutableStateFlow<List<CloudBookmark>>(emptyList())
    val cloudBookmarks: StateFlow<List<CloudBookmark>> = _cloudBookmarks.asStateFlow()

    init {
        refreshStorageRoots()
        refreshPane(PaneId.PANE_A)
        refreshPane(PaneId.PANE_B)
        authManager.attemptAutoSignIn()

        viewModelScope.launch {
            authManager.currentUser.collect { user ->
                if (user != null) {
                    cloudRepository.observeBookmarks(user.uid).collect { bookmarks ->
                        _cloudBookmarks.value = bookmarks
                    }
                } else {
                    _cloudBookmarks.value = emptyList()
                }
            }
        }
    }

    fun addCurrentPathToCloudBookmarks(paneId: PaneId) {
        val user = authManager.currentUser.value ?: return
        val current = if (paneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
        val name = current.currentPath.name.ifEmpty { "Root" }
        viewModelScope.launch {
            try {
                cloudRepository.addBookmark(user.uid, name, current.currentPath.normalized)
            } catch (_: Exception) {}
        }
    }

    fun removeCloudBookmark(bookmarkId: String) {
        val user = authManager.currentUser.value ?: return
        viewModelScope.launch {
            try {
                cloudRepository.deleteBookmark(user.uid, bookmarkId)
            } catch (_: Exception) {}
        }
    }

    fun signInWithGoogle(activity: Activity, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        authManager.signInWithGoogle(activity, onSuccess, onError)
    }

    fun signOut() {
        authManager.signOut()
    }

    fun checkHasStoragePermission(): Boolean {
        return PermissionManager.hasAnyStorageAccess(getApplication())
    }

    fun updatePermissionStatus() {
        val tier = PermissionManager.getStorageAccessTier(getApplication())
        _storageAccessTier.value = tier
        val granted = tier != StorageAccessTier.NONE
        _hasStoragePermission.value = granted
        refreshSafDirectories()
        if (granted) {
            refreshStorageRoots()
            refreshPane(PaneId.PANE_A)
            refreshPane(PaneId.PANE_B)
        }
    }

    fun refreshSafDirectories() {
        viewModelScope.launch {
            val uris = PermissionManager.getPersistedSafUris(getApplication())
            val list = uris.map { PermissionManager.getSafDirectoryInfo(getApplication(), it) }
            _safDirectories.value = list
        }
    }

    fun onSafFolderGranted(uri: Uri) {
        val success = PermissionManager.persistDocumentTreeUri(getApplication(), uri)
        if (success) {
            updatePermissionStatus()
            refreshSafDirectories()
            refreshStorageRoots()
            val info = PermissionManager.getSafDirectoryInfo(getApplication(), uri)
            val safPath = VPath("saf://${info.encodedPath}")
            navigateTo(_activePaneId.value, safPath)
        }
    }

    fun revokeSafAccess(uri: Uri) {
        PermissionManager.revokeSafAccess(getApplication(), uri)
        updatePermissionStatus()
        refreshSafDirectories()
        refreshStorageRoots()
    }

    fun refreshStorageRoots() {
        viewModelScope.launch {
            val roots = StorageRoots.getRoots(getApplication())
            _storageRoots.value = roots
            refreshSafDirectories()
        }
    }

    fun setActivePane(paneId: PaneId) {
        _activePaneId.value = paneId
    }

    fun refreshPane(paneId: PaneId) {
        val current = if (paneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
        loadDirectory(paneId, current.currentPath)
    }

    fun navigateTo(paneId: PaneId, targetPath: VPath, addToHistory: Boolean = true) {
        val current = if (paneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
        val newBackHistory = if (addToHistory) {
            current.backHistory + current.currentPath
        } else {
            current.backHistory
        }

        updatePane(paneId) {
            it.copy(
                currentPath = targetPath,
                backHistory = newBackHistory,
                forwardHistory = if (addToHistory) emptyList() else it.forwardHistory,
                selectedPaths = emptySet(),
                searchQuery = "",
                isSearchOpen = false
            )
        }
        loadDirectory(paneId, targetPath)
    }

    fun navigateBack(paneId: PaneId): Boolean {
        val current = if (paneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
        if (current.backHistory.isEmpty()) return false

        val previousPath = current.backHistory.last()
        val remainingHistory = current.backHistory.dropLast(1)
        val newForward = listOf(current.currentPath) + current.forwardHistory

        updatePane(paneId) {
            it.copy(
                currentPath = previousPath,
                backHistory = remainingHistory,
                forwardHistory = newForward,
                selectedPaths = emptySet(),
                searchQuery = "",
                isSearchOpen = false
            )
        }
        loadDirectory(paneId, previousPath)
        return true
    }

    fun navigateForward(paneId: PaneId): Boolean {
        val current = if (paneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
        if (current.forwardHistory.isEmpty()) return false

        val nextPath = current.forwardHistory.first()
        val remainingForward = current.forwardHistory.drop(1)
        val newBack = current.backHistory + current.currentPath

        updatePane(paneId) {
            it.copy(
                currentPath = nextPath,
                backHistory = newBack,
                forwardHistory = remainingForward,
                selectedPaths = emptySet()
            )
        }
        loadDirectory(paneId, nextPath)
        return true
    }

    fun navigateUp(paneId: PaneId) {
        val current = if (paneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
        val parent = current.currentPath.parent
        if (parent != null) {
            navigateTo(paneId, parent)
        }
    }

    private fun loadDirectory(paneId: PaneId, path: VPath) {
        viewModelScope.launch {
            updatePane(paneId) { it.copy(isLoading = true, error = null) }
            try {
                val entries = fileSystem.list(path)
                updatePane(paneId) {
                    it.copy(
                        rawEntries = entries,
                        isLoading = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                updatePane(paneId) {
                    it.copy(
                        isLoading = false,
                        error = e.localizedMessage ?: "Failed to read directory"
                    )
                }
            }
        }
    }

    fun toggleSelection(paneId: PaneId, path: VPath) {
        updatePane(paneId) { current ->
            val set = current.selectedPaths.toMutableSet()
            if (set.contains(path)) {
                set.remove(path)
            } else {
                set.add(path)
            }
            current.copy(selectedPaths = set)
        }
    }

    fun selectAll(paneId: PaneId) {
        updatePane(paneId) { current ->
            val all = current.displayedEntries.map { it.path }.toSet()
            current.copy(selectedPaths = all)
        }
    }

    fun invertSelection(paneId: PaneId) {
        updatePane(paneId) { current ->
            val all = current.displayedEntries.map { it.path }.toSet()
            val inverted = all - current.selectedPaths
            current.copy(selectedPaths = inverted)
        }
    }

    fun clearSelection(paneId: PaneId) {
        updatePane(paneId) { current ->
            current.copy(selectedPaths = emptySet())
        }
    }

    fun setSearchQuery(paneId: PaneId, query: String) {
        updatePane(paneId) { it.copy(searchQuery = query) }
    }

    fun toggleSearch(paneId: PaneId) {
        updatePane(paneId) {
            val nextOpen = !it.isSearchOpen
            it.copy(
                isSearchOpen = nextOpen,
                searchQuery = if (!nextOpen) "" else it.searchQuery
            )
        }
    }

    fun setSort(paneId: PaneId, sortBy: SortBy) {
        updatePane(paneId) {
            if (it.sortBy == sortBy) {
                val nextOrder = if (it.sortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
                it.copy(sortOrder = nextOrder)
            } else {
                it.copy(sortBy = sortBy, sortOrder = SortOrder.ASCENDING)
            }
        }
    }

    fun toggleShowHidden(paneId: PaneId) {
        updatePane(paneId) { it.copy(showHidden = !it.showHidden) }
    }

    fun createFolder(paneId: PaneId, folderName: String) {
        val trimmed = folderName.trim()
        if (trimmed.isEmpty()) return
        val current = if (paneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
        val newPath = current.currentPath.resolve(trimmed)

        viewModelScope.launch {
            val success = fileSystem.mkdir(newPath)
            if (success) {
                refreshPane(paneId)
            }
        }
    }

    fun createFile(paneId: PaneId, fileName: String) {
        val trimmed = fileName.trim()
        if (trimmed.isEmpty()) return
        val current = if (paneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
        val newPath = current.currentPath.resolve(trimmed)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                fileSystem.openWrite(newPath, append = false).use {
                    it.write(ByteArray(0))
                }
                refreshPane(paneId)
            } catch (_: Exception) {}
        }
    }

    fun renameItem(paneId: PaneId, entry: VEntry, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == entry.name) return

        viewModelScope.launch {
            val success = com.example.core.fs.DocumentFileOperations.rename(getApplication(), entry.path, trimmed)
                || (entry.path.parent?.let { fileSystem.rename(entry.path, it.resolve(trimmed)) } == true)
            if (success) {
                clearSelection(paneId)
                refreshPane(paneId)
                val otherPaneId = if (paneId == PaneId.PANE_A) PaneId.PANE_B else PaneId.PANE_A
                val other = if (otherPaneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
                val current = if (paneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
                if (other.currentPath == current.currentPath) {
                    refreshPane(otherPaneId)
                }
            }
        }
    }

    fun deleteSingleItem(paneId: PaneId, entry: VEntry) {
        viewModelScope.launch {
            val success = com.example.core.fs.DocumentFileOperations.delete(getApplication(), entry.path)
                || fileSystem.delete(entry.path, recursive = true)
            if (success) {
                clearSelection(paneId)
                refreshPane(paneId)
                val otherPaneId = if (paneId == PaneId.PANE_A) PaneId.PANE_B else PaneId.PANE_A
                val other = if (otherPaneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
                val current = if (paneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
                if (other.currentPath == current.currentPath) {
                    refreshPane(otherPaneId)
                }
            }
        }
    }

    fun copySingleToOtherPane(sourcePaneId: PaneId, entry: VEntry) {
        val targetPaneId = if (sourcePaneId == PaneId.PANE_A) PaneId.PANE_B else PaneId.PANE_A
        val targetState = if (targetPaneId == PaneId.PANE_A) _paneAState.value else _paneBState.value

        viewModelScope.launch {
            val docCopied = com.example.core.fs.DocumentFileOperations.copy(
                getApplication(),
                entry.path,
                targetState.currentPath
            )
            if (docCopied) {
                clearSelection(sourcePaneId)
                refreshPane(targetPaneId)
            } else {
                taskEngine.startCopy(
                    sources = listOf(entry),
                    targetDir = targetState.currentPath,
                    sourceFs = fileSystem,
                    targetFs = fileSystem
                ) {
                    clearSelection(sourcePaneId)
                    refreshPane(targetPaneId)
                }
            }
        }
    }

    fun moveSingleToOtherPane(sourcePaneId: PaneId, entry: VEntry) {
        val targetPaneId = if (sourcePaneId == PaneId.PANE_A) PaneId.PANE_B else PaneId.PANE_A
        val targetState = if (targetPaneId == PaneId.PANE_A) _paneAState.value else _paneBState.value

        viewModelScope.launch {
            val docMoved = com.example.core.fs.DocumentFileOperations.move(
                getApplication(),
                entry.path,
                targetState.currentPath
            )
            if (docMoved) {
                clearSelection(sourcePaneId)
                refreshPane(sourcePaneId)
                refreshPane(targetPaneId)
            } else {
                taskEngine.startMove(
                    sources = listOf(entry),
                    targetDir = targetState.currentPath,
                    sourceFs = fileSystem,
                    targetFs = fileSystem
                ) {
                    clearSelection(sourcePaneId)
                    refreshPane(sourcePaneId)
                    refreshPane(targetPaneId)
                }
            }
        }
    }

    fun deleteSelected(paneId: PaneId) {
        val current = if (paneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
        val selectedEntries = current.rawEntries.filter { current.selectedPaths.contains(it.path) }
        if (selectedEntries.isEmpty()) return

        taskEngine.startDelete(selectedEntries, fileSystem) {
            clearSelection(paneId)
            refreshPane(paneId)
            // If the other pane is also viewing the same dir, refresh it too
            val otherPaneId = if (paneId == PaneId.PANE_A) PaneId.PANE_B else PaneId.PANE_A
            val other = if (otherPaneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
            if (other.currentPath == current.currentPath) {
                refreshPane(otherPaneId)
            }
        }
    }

    fun copySelectedToOtherPane() {
        val sourcePaneId = _activePaneId.value
        val targetPaneId = if (sourcePaneId == PaneId.PANE_A) PaneId.PANE_B else PaneId.PANE_A

        val sourceState = if (sourcePaneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
        val targetState = if (targetPaneId == PaneId.PANE_A) _paneAState.value else _paneBState.value

        val selectedEntries = sourceState.rawEntries.filter { sourceState.selectedPaths.contains(it.path) }
        if (selectedEntries.isEmpty()) return

        taskEngine.startCopy(
            sources = selectedEntries,
            targetDir = targetState.currentPath,
            sourceFs = fileSystem,
            targetFs = fileSystem
        ) {
            clearSelection(sourcePaneId)
            refreshPane(targetPaneId)
        }
    }

    fun moveSelectedToOtherPane() {
        val sourcePaneId = _activePaneId.value
        val targetPaneId = if (sourcePaneId == PaneId.PANE_A) PaneId.PANE_B else PaneId.PANE_A

        val sourceState = if (sourcePaneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
        val targetState = if (targetPaneId == PaneId.PANE_A) _paneAState.value else _paneBState.value

        val selectedEntries = sourceState.rawEntries.filter { sourceState.selectedPaths.contains(it.path) }
        if (selectedEntries.isEmpty()) return

        taskEngine.startMove(
            sources = selectedEntries,
            targetDir = targetState.currentPath,
            sourceFs = fileSystem,
            targetFs = fileSystem
        ) {
            clearSelection(sourcePaneId)
            refreshPane(sourcePaneId)
            refreshPane(targetPaneId)
        }
    }

    fun calculateProperties(paneId: PaneId) {
        val current = if (paneId == PaneId.PANE_A) _paneAState.value else _paneBState.value
        val selected = current.rawEntries.filter { current.selectedPaths.contains(it.path) }
        val target = if (selected.isNotEmpty()) selected.first() else {
            VEntry(
                name = current.currentPath.name,
                path = current.currentPath,
                isDirectory = true
            )
        }

        _activeProperties.value = PropertiesInfo(
            title = target.name,
            path = target.path.normalized,
            isDirectory = target.isDirectory,
            totalSize = if (target.isDirectory) 0L else target.size,
            formattedSize = if (target.isDirectory) "Calculating..." else target.formattedSize,
            fileCount = if (target.isDirectory) 0 else 1,
            folderCount = if (target.isDirectory) 1 else 0,
            lastModified = target.formattedDate,
            canRead = target.canRead,
            canWrite = target.canWrite,
            isCalculating = target.isDirectory
        )

        if (target.isDirectory) {
            viewModelScope.launch(Dispatchers.IO) {
                var size = 0L
                var files = 0
                var folders = 0
                val stack = ArrayDeque<VPath>()
                stack.add(target.path)

                while (stack.isNotEmpty()) {
                    val dir = stack.removeFirst()
                    val children = try { fileSystem.list(dir) } catch (_: Exception) { emptyList() }
                    for (child in children) {
                        if (child.isDirectory) {
                            folders++
                            stack.add(child.path)
                        } else {
                            files++
                            size += child.size
                        }
                    }
                }

                val formatted = formatSize(size)
                withContext(Dispatchers.Main) {
                    _activeProperties.value = _activeProperties.value?.copy(
                        totalSize = size,
                        formattedSize = formatted,
                        fileCount = files,
                        folderCount = folders,
                        isCalculating = false
                    )
                }
            }
        }
    }

    fun dismissProperties() {
        _activeProperties.value = null
    }

    fun resolveConflict(action: ConflictAction, applyToAll: Boolean) {
        taskEngine.resolveConflict(action, applyToAll)
    }

    fun cancelActiveTask() {
        taskEngine.cancelActiveTask()
    }

    fun clearFinishedTask() {
        taskEngine.clearFinishedTask()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        if (digitGroups >= units.size) digitGroups = units.size - 1
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return if (digitGroups == 0) "$bytes B" else String.format(Locale.US, "%.2f %s", value, units[digitGroups])
    }

    private fun updatePane(paneId: PaneId, transform: (PaneState) -> PaneState) {
        if (paneId == PaneId.PANE_A) {
            _paneAState.value = transform(_paneAState.value)
        } else {
            _paneBState.value = transform(_paneBState.value)
        }
    }
}
