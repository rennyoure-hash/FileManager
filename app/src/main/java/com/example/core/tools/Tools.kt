package com.example.core.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

object ChecksumTool {
    private const val BUFFER = 8192

    suspend fun compute(file: File, algorithm: String): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance(algorithm)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun computeDirectory(root: File): Map<String, String> = withContext(Dispatchers.IO) {
        val map = mutableMapOf<String, String>()
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            map[file.absolutePath] = compute(file, "SHA-256")
        }
        map
    }
}

class FileSplitter {
    suspend fun split(file: File, chunkSizeBytes: Long, outDir: File): List<File> = withContext(Dispatchers.IO) {
        outDir.mkdirs()
        val parts = mutableListOf<File>()
        FileInputStream(file).use { input ->
            var index = 0
            input.use {
                val buffer = ByteArray(chunkSizeBytes.toInt().coerceIn(1, 10 * 1024 * 1024))
                while (true) {
                    val partFile = File(outDir, "${file.name}.part$index")
                    FileOutputStream(partFile).use { output ->
                        var total = 0L
                        while (total < chunkSizeBytes) {
                            val toRead = (chunkSizeBytes - total).toInt().coerceAtMost(buffer.size)
                            val read = input.read(buffer, 0, toRead)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            total += read
                        }
                    }
                    parts.add(partFile)
                    index++
                    if (partFile.length() == 0L) break
                    // check EOF by peeking next byte via mark not available; check if we reached EOF by trying read
                    // Simplify: stop if last read was shorter than chunk
                    if (parts.last().length() < chunkSizeBytes) break
                }
            }
        }
        parts
    }

    suspend fun merge(parts: List<File>, output: File): Boolean = withContext(Dispatchers.IO) {
        try {
            output.parentFile?.mkdirs()
            FileOutputStream(output).use { out ->
                parts.sortedBy { it.name }.forEach { part ->
                    FileInputStream(part).use { input ->
                        input.copyTo(out)
                    }
                }
            }
            true
        } catch (_: Exception) { false }
    }
}

data class BatchRenameRule(val prefix: String = "", val suffix: String = "", val find: String = "", val replace: String = "")

class BatchRename {
    fun preview(files: List<File>, rule: BatchRenameRule): List<Pair<File, String>> {
        return files.map { file ->
            var name = file.name
            if (rule.find.isNotEmpty()) {
                name = name.replace(rule.find, rule.replace)
            }
            name = rule.prefix + name + rule.suffix
            file to name
        }
    }

    suspend fun apply(files: List<File>, rule: BatchRenameRule): Int = withContext(Dispatchers.IO) {
        var count = 0
        preview(files, rule).forEach { (file, newName) ->
            val newFile = File(file.parent, newName)
            if (file.renameTo(newFile)) count++
        }
        count
    }
}

data class DuplicateGroup(val checksum: String, val files: List<File>)

class DuplicateFinder {
    suspend fun findDuplicates(root: File): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val bySize = root.walkTopDown().filter { it.isFile }.groupBy { it.length() }
        val groups = mutableListOf<DuplicateGroup>()
        for ((_, sameSize) in bySize) {
            if (sameSize.size < 2) continue
            val byHash = sameSize.groupBy { computeBlocking(it, "SHA-256") }
            for ((hash, dups) in byHash) {
                if (dups.size > 1) groups.add(DuplicateGroup(hash, dups))
            }
        }
        groups
    }

    companion object {
        fun computeBlocking(file: File, algorithm: String): String {
            val digest = MessageDigest.getInstance(algorithm)
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

data class StorageUsage(val path: String, val sizeBytes: Long, val isDirectory: Boolean)

class StorageAnalyzer {
    suspend fun analyzeDir(root: File, maxDepth: Int = 2): List<StorageUsage> = withContext(Dispatchers.IO) {
        val results = mutableListOf<StorageUsage>()
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth) return
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            results.add(StorageUsage(dir.absolutePath, size, true))
            if (depth < maxDepth) {
                dir.listFiles()?.filter { it.isDirectory }?.forEach { walk(it, depth + 1) }
            }
        }
        walk(root, 0)
        results.sortedByDescending { it.sizeBytes }
    }
}
