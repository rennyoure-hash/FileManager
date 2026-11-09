package com.example.feature.browser

import com.example.core.fs.VEntry
import com.example.core.fs.VPath

enum class SortBy {
    NAME,
    SIZE,
    DATE,
    TYPE
}

enum class SortOrder {
    ASCENDING,
    DESCENDING
}

enum class PaneId {
    PANE_A,
    PANE_B
}

data class PropertiesInfo(
    val title: String,
    val path: String,
    val isDirectory: Boolean,
    val totalSize: Long,
    val formattedSize: String,
    val fileCount: Int,
    val folderCount: Int,
    val lastModified: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val isCalculating: Boolean = false
)

data class PaneState(
    val paneId: PaneId,
    val currentPath: VPath,
    val backHistory: List<VPath> = emptyList(),
    val forwardHistory: List<VPath> = emptyList(),
    val rawEntries: List<VEntry> = emptyList(),
    val selectedPaths: Set<VPath> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val isSearchOpen: Boolean = false,
    val sortBy: SortBy = SortBy.NAME,
    val sortOrder: SortOrder = SortOrder.ASCENDING,
    val showHidden: Boolean = false
) {
    val displayedEntries: List<VEntry> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        var list = rawEntries

        // Filter hidden
        if (!showHidden) {
            list = list.filter { !it.isHidden }
        }

        // Filter search
        if (searchQuery.isNotBlank()) {
            val query = searchQuery.trim().lowercase()
            list = list.filter { it.name.lowercase().contains(query) }
        }

        // Sort (directories always on top)
        list.sortedWith { a, b ->
            if (a.isDirectory && !b.isDirectory) {
                -1
            } else if (!a.isDirectory && b.isDirectory) {
                1
            } else {
                val comp = when (sortBy) {
                    SortBy.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                    SortBy.SIZE -> a.size.compareTo(b.size)
                    SortBy.DATE -> a.lastModified.compareTo(b.lastModified)
                    SortBy.TYPE -> a.extension.compareTo(b.extension, ignoreCase = true)
                }
                if (sortOrder == SortOrder.ASCENDING) comp else -comp
            }
        }
    }

    val isSelectionMode: Boolean
        get() = selectedPaths.isNotEmpty()

    val selectedCount: Int
        get() = selectedPaths.size
}

data class PendingSensitiveOperation(
    val targetPath: String,
    val operationName: String,
    val onConfirmed: () -> Unit
)

data class PendingSystemDelete(
    val targetPath: String,
    val onExecuteDelete: (backupFirst: Boolean) -> Unit
)
