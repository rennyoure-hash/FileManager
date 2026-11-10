package com.example.core.fs

import android.content.Context
import android.os.Environment
import com.example.core.permission.PermissionManager
import com.example.core.root.MountEntry
import com.example.core.root.PartitionBadge
import com.example.core.root.RootManager
import java.io.File
import java.util.Locale

data class StorageRoot(
    val title: String,
    val path: VPath,
    val isPrimary: Boolean = false,
    val isRemovable: Boolean = false,
    val totalSpace: Long = 0L,
    val freeSpace: Long = 0L,
    val mountEntry: MountEntry? = null
) {
    val badge: PartitionBadge
        get() = mountEntry?.badge ?: PartitionBadge.RW

    val fsType: String
        get() = mountEntry?.fsType ?: ""

    val formattedFreeSpace: String
        get() {
            if (freeSpace <= 0) return ""
            val gb = freeSpace.toDouble() / (1024 * 1024 * 1024)
            val totalGb = totalSpace.toDouble() / (1024 * 1024 * 1024)
            return String.format(Locale.US, "%.1f GB free of %.1f GB", gb, totalGb)
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
            val mount = RootManager.getMountForPath(primaryDir.absolutePath)
            list.add(
                StorageRoot(
                    title = "Internal Storage",
                    path = VPath(primaryDir.absolutePath),
                    isPrimary = true,
                    isRemovable = false,
                    totalSpace = total,
                    freeSpace = free,
                    mountEntry = mount
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
                        freeSpace = fallback.freeSpace,
                        mountEntry = RootManager.getMountForPath(fallback.absolutePath)
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
                                    freeSpace = root.freeSpace,
                                    mountEntry = RootManager.getMountForPath(pathStr)
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 3. Storage Access Framework (SAF) Granted Directories
        try {
            val safUris = PermissionManager.getPersistedSafUris(context)
            for (safUri in safUris) {
                val info = PermissionManager.getSafDirectoryInfo(context, safUri)
                val safPath = VPath("saf://${info.encodedPath}")
                val isDuplicate = list.any { it.path == safPath }
                if (!isDuplicate) {
                    list.add(
                        StorageRoot(
                            title = "SAF: ${info.name}",
                            path = safPath,
                            isPrimary = false,
                            isRemovable = true,
                            totalSpace = 0L,
                            freeSpace = 0L,
                            mountEntry = MountEntry(
                                device = "saf",
                                mountPoint = safPath.normalized,
                                fsType = "documentfile",
                                options = if (info.canWrite) listOf("rw") else listOf("ro")
                            )
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        // 4. System partitions.
        //    /system, /vendor, /product, /system_ext, /apex and /etc are mounted world-readable
        //    (read-only), so they can be browsed without root - same behaviour as MT Manager.
        //    "/" and "/data" require real superuser access.
        try {
            val rootGranted = RootManager.isRootExplorerEnabled(context) && RootManager.isRootGranted()
            val systemRoots = listOf(
                "Root Directory (/)" to "/",
                "System (/system)" to "/system",
                "Vendor (/vendor)" to "/vendor",
                "Product (/product)" to "/product",
                "System Ext (/system_ext)" to "/system_ext",
                "APEX (/apex)" to "/apex",
                "etc (/etc)" to "/etc",
                "Data (/data)" to "/data"
            )
            for ((title, rawPath) in systemRoots) {
                val requiresRoot = rawPath == "/" || rawPath == "/data"
                if (requiresRoot && !rootGranted) continue

                val dir = File(rawPath)
                if (rawPath != "/" && !(dir.exists() && dir.canRead())) continue

                val mount = RootManager.getMountForPath(rawPath)
                    ?: if (requiresRoot) {
                        null
                    } else {
                        MountEntry(
                            device = "system",
                            mountPoint = rawPath,
                            fsType = "read-only",
                            options = listOf("ro")
                        )
                    }

                list.add(
                    StorageRoot(
                        title = title,
                        path = VPath(rawPath),
                        isPrimary = false,
                        isRemovable = false,
                        totalSpace = dir.totalSpace,
                        freeSpace = dir.freeSpace,
                        mountEntry = mount
                    )
                )
            }
        } catch (_: Exception) {
        }

        // 5. Fallback app internal storage if nothing else
        val appFiles = context.filesDir
        if (list.isEmpty() && appFiles != null) {
            list.add(
                StorageRoot(
                    title = "App Storage",
                    path = VPath(appFiles.absolutePath),
                    isPrimary = true,
                    totalSpace = appFiles.totalSpace,
                    freeSpace = appFiles.freeSpace,
                    mountEntry = RootManager.getMountForPath(appFiles.absolutePath)
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
