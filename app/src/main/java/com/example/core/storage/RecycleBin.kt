package com.example.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Recycle bin stored in app-private storage. Move = copy + delete (atomic-ish).
 */
class RecycleBin(private val binDir: File) {
    init { binDir.mkdirs() }

    val binPath: File get() = binDir

    suspend fun trash(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val target = uniqueName(File(binDir, file.name))
            if (file.isDirectory) {
                file.copyRecursively(target, overwrite = false)
                file.deleteRecursively()
            } else {
                FileInputStream(file).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
                // preserve timestamp
                target.setLastModified(file.lastModified())
                file.delete()
            }
            // Store metadata as sidecar
            writeMeta(target, file.absolutePath)
            true
        } catch (_: Exception) { false }
    }

    suspend fun listTrashed(): List<TrashedItem> = withContext(Dispatchers.IO) {
        binDir.listFiles()?.mapNotNull { file ->
            if (file.extension == "meta") return@mapNotNull null
            val originalPath = readMeta(file) ?: ""
            TrashedItem(
                name = file.name,
                path = file.absolutePath,
                originalPath = originalPath,
                size = if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length(),
                trashedAt = file.lastModified()
            )
        } ?: emptyList()
    }

    suspend fun restore(item: TrashedItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val dest = File(item.originalPath)
            dest.parentFile?.mkdirs()
            val src = File(item.path)
            if (src.isDirectory) {
                src.copyRecursively(dest, overwrite = true)
                src.deleteRecursively()
            } else {
                FileInputStream(src).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                src.delete()
            }
            deleteMeta(File(item.path))
            true
        } catch (_: Exception) { false }
    }

    suspend fun permanentDelete(item: TrashedItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(item.path)
            if (file.isDirectory) file.deleteRecursively() else file.delete()
            deleteMeta(file)
            true
        } catch (_: Exception) { false }
    }

    suspend fun emptyBin(): Int = withContext(Dispatchers.IO) {
        val count = binDir.listFiles()?.size ?: 0
        binDir.walkTopDown().sortedByDescending { it.absolutePath.length }.forEach { it.delete() }
        count
    }

    private fun uniqueName(base: File): File {
        if (!base.exists()) return base
        var counter = 1
        var candidate = File(base.parent, "${base.nameWithoutExtension} ($counter).${base.extension}")
        while (candidate.exists()) {
            candidate = File(base.parent, "${base.nameWithoutExtension} ($counter).${base.extension}")
            counter++
        }
        return candidate
    }

    private fun writeMeta(file: File, originalPath: String) {
        try {
            File(file.parent, "${file.name}.meta").writeText(originalPath)
        } catch (_: Exception) {}
    }

    private fun readMeta(file: File): String? {
        val meta = File(file.parent, "${file.name}.meta")
        return try { if (meta.exists()) meta.readText().trim() else null } catch (_: Exception) { null }
    }

    private fun deleteMeta(file: File) {
        try { File(file.parent, "${file.name}.meta").delete() } catch (_: Exception) {}
    }
}

data class TrashedItem(
    val name: String,
    val path: String,
    val originalPath: String,
    val size: Long,
    val trashedAt: Long
)
