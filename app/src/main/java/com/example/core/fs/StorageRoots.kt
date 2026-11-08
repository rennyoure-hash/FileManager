package com.example.core.fs

import android.content.Context
import android.os.Environment
import java.io.File

data class StorageRoot(
    val title: String,
    val path: VPath,
    val isPrimary: Boolean = false,
    val isRemovable: Boolean = false,
    val totalSpace: Long = 0L,
    val freeSpace: Long = 0L
) {
    val formattedFreeSpace: String
        get() {
            if (freeSpace <= 0) return ""
            val gb = freeSpace.toDouble() / (1024 * 1024 * 1024)
            val totalGb = totalSpace.toDouble() / (1024 * 1024 * 1024)
            return String.format("%.1f GB free of %.1f GB", gb, totalGb)
        }
}

object StorageRoots {
    fun getRoots(context: Context): List<StorageRoot> {
        val list = mutableListOf<StorageRoot>()

        // 1. Primary Internal Storage
        val primaryDir = Environment.getExternalStorageDirectory()
        if (primaryDir != null && primaryDir.exists()) {
            val total = primaryDir.totalSpace
            val free = primaryDir.freeSpace
            list.add(
                StorageRoot(
                    title = "Internal Storage",
                    path = VPath(primaryDir.absolutePath),
                    isPrimary = true,
                    isRemovable = false,
                    totalSpace = total,
                    freeSpace = free
                )
            )
        } else {
            val fallback = File("/storage/emulated/0")
            if (fallback.exists()) {
                list.add(
                    StorageRoot(
                        title = "Internal Storage",
                        path = VPath(fallback.absolutePath),
                        isPrimary = true,
                        isRemovable = false,
                        totalSpace = fallback.totalSpace,
                        freeSpace = fallback.freeSpace
                    )
                )
            }
        }

        // 2. Secondary storage (SD card / OTG USB)
        try {
            val extDirs = context.getExternalFilesDirs(null)
            for (dir in extDirs) {
                if (dir != null) {
                    val root = findStorageRootFromAppDir(dir)
                    if (root != null && root.exists() && root.canRead()) {
                        val pathStr = root.absolutePath
                        val isDuplicate = list.any { it.path.normalized == pathStr }
                        if (!isDuplicate) {
                            list.add(
                                StorageRoot(
                                    title = if (Environment.isExternalStorageRemovable(root)) "SD Card" else "Secondary Storage",
                                    path = VPath(pathStr),
                                    isPrimary = false,
                                    isRemovable = true,
                                    totalSpace = root.totalSpace,
                                    freeSpace = root.freeSpace
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 3. Fallback app internal storage if nothing else
        val appFiles = context.filesDir
        if (list.isEmpty() && appFiles != null) {
            list.add(
                StorageRoot(
                    title = "App Storage",
                    path = VPath(appFiles.absolutePath),
                    isPrimary = true,
                    totalSpace = appFiles.totalSpace,
                    freeSpace = appFiles.freeSpace
                )
            )
        }

        return list
    }

    private fun findStorageRootFromAppDir(appDir: File): File? {
        var current: File? = appDir
        while (current != null) {
            val parent = current.parentFile ?: break
            if (parent.name == "Android") {
                return parent.parentFile
            }
            current = parent
        }
        return null
    }

    fun getQuickLocations(context: Context): List<Pair<String, VPath>> {
        val primaryPath = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
        return listOf(
            "Downloads" to VPath("$primaryPath/Download"),
            "DCIM / Camera" to VPath("$primaryPath/DCIM"),
            "Documents" to VPath("$primaryPath/Documents"),
            "Pictures" to VPath("$primaryPath/Pictures"),
            "Music" to VPath("$primaryPath/Music"),
            "Android" to VPath("$primaryPath/Android")
        )
    }
}
